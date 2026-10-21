"""Runtime configuration; a private state volume overrides initial environment seeds."""
from collections import defaultdict, deque
from contextlib import contextmanager
from copy import deepcopy
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import tempfile
import threading
import time
from urllib.parse import urlsplit


VERSION = "0.9.4"
SESSION_SECONDS = 8 * 60 * 60
PASSWORD_ITERATIONS = 310000


def service_url(value):
    value = value.strip().rstrip("/")
    try:
        parts = urlsplit(value)
        if (len(value) > 2048 or parts.scheme not in {"http", "https"} or not parts.hostname
                or parts.username is not None or parts.password is not None or parts.query or parts.fragment
                or any(character.isspace() for character in value)):
            raise ValueError()
        if parts.port is not None and not 1 <= parts.port <= 65535:
            raise ValueError()
    except ValueError:
        raise ValueError("请填写完整的 HTTP/HTTPS 服务地址，不要包含账号、查询参数或片段") from None
    return value


class SettingsStore:
    def __init__(self, config):
        self.lock = threading.RLock()
        self.current = deepcopy(config)
        admin = self.current.pop("management", {})
        self.directory = Path(admin.get("state_dir", "/data"))
        self.path = self.directory / "settings.json"
        self.auth_path = self.directory / "admin-auth.json"
        password = admin.get("password", "")
        self.enabled = bool(password)
        self.salt = secrets.token_bytes(16)
        self.password_hash = b""
        if self.auth_path.exists():
            try:
                auth = json.loads(self.auth_path.read_text(encoding="utf-8"))
                if (set(auth) != {"version", "algorithm", "iterations", "salt", "hash"}
                        or type(auth["version"]) is not int or auth["version"] != 1
                        or auth["algorithm"] != "pbkdf2_sha256"
                        or type(auth["iterations"]) is not int or auth["iterations"] != PASSWORD_ITERATIONS):
                    raise ValueError()
                self.salt, self.password_hash = bytes.fromhex(auth["salt"]), bytes.fromhex(auth["hash"])
                if len(self.salt) != 16 or len(self.password_hash) != 32:
                    raise ValueError()
                self.enabled = True
            except (OSError, KeyError, ValueError, TypeError):
                raise ValueError("Cannot read private admin-auth.json; preserve it and check its contents") from None
        elif self.enabled:
            if not 12 <= len(password) <= 1024:
                raise ValueError("SMS_ADMIN_PASSWORD must contain 12–1024 characters")
            self.password_hash = self._password(password)
        self.public_url = service_url(admin["public_url"]) if admin.get("public_url") else ""
        self.sessions = {}
        self.failures = defaultdict(deque)
        self.database_ready = False
        if self.enabled:
            self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        # Removing the management password must never resurrect an obsolete access key.
        if self.path.exists():
            try:
                saved = json.loads(self.path.read_text(encoding="utf-8"))
                if saved.get("version") != 1 or not isinstance(saved["database"], dict):
                    raise ValueError()
                self.current.update(database=saved["database"], api_token=saved["api_token"])
                self.public_url = service_url(saved["public_url"]) if saved.get("public_url") else ""
            except (OSError, KeyError, ValueError, TypeError):
                raise ValueError("Cannot read private state settings.json; preserve it and check its contents") from None
        elif self.enabled and not self.current.get("api_token"):
            self.current["api_token"] = secrets.token_urlsafe(36)
        token = self.current.get("api_token")
        if not isinstance(token, str) or not 32 <= len(token) <= 512:
            raise ValueError("api_token must contain 32–512 characters")
        if self.enabled and not self.path.exists():
            self.persist(self.current, self.public_url)

    def _password(self, password, salt=None):
        return hashlib.pbkdf2_hmac("sha256", password.encode(), self.salt if salt is None else salt, PASSWORD_ITERATIONS)

    def persist(self, config, public_url):
        value = {"version": 1, "database": config["database"], "api_token": config["api_token"], "public_url": public_url}
        self._write_private(self.path, value)

    def _write_private(self, path, value):
        fd, name = tempfile.mkstemp(prefix="."+path.stem+"-", dir=self.directory)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                json.dump(value, stream, ensure_ascii=False, indent=2)
                stream.flush()
                os.fsync(stream.fileno())
            os.chmod(name, 0o600)
            os.replace(name, path)
        finally:
            if os.path.exists(name):
                os.unlink(name)

    @contextmanager
    def operation(self):
        # Keep in-flight writes on the previous database until they commit before switching.
        with self.lock:
            yield self.current

    def snapshot(self):
        with self.lock:
            return deepcopy(self.current)

    def replace(self, config, public_url=None):
        with self.lock:
            target_url = self.public_url if public_url is None else public_url
            self.persist(config, target_url)
            self.current = deepcopy(config)
            self.public_url = target_url

    def _verify_password(self, password, peer):
        now = time.monotonic()
        self.failures = defaultdict(deque, {key: deque(x for x in rows if x > now - 300)
                                          for key, rows in self.failures.items() if rows and rows[-1] > now - 300})
        if len(self.failures[peer]) >= 10 or sum(map(len, self.failures.values())) >= 100:
            return "limited"
        if not self.enabled or not hmac.compare_digest(self._password(password), self.password_hash):
            self.failures[peer].append(now)
            return "invalid"
        self.failures.pop(peer, None)
        return "ok"

    def login(self, password, peer):
        with self.lock:
            now = time.monotonic()
            result = self._verify_password(password, peer)
            if result != "ok":
                return result, None
            self.sessions = {key: value for key, value in self.sessions.items() if value["expires"] > now}
            if len(self.sessions) >= 32:
                del self.sessions[next(iter(self.sessions))]
            cookie = secrets.token_urlsafe(32)
            session = {"csrf": secrets.token_urlsafe(32), "expires": now + SESSION_SECONDS}
            self.sessions[hashlib.sha256(cookie.encode()).hexdigest()] = session
            return cookie, session

    def persist_password(self, salt, password_hash):
        self._write_private(self.auth_path, {"version": 1, "algorithm": "pbkdf2_sha256", "iterations": PASSWORD_ITERATIONS,
                                            "salt": salt.hex(), "hash": password_hash.hex()})

    def change_password(self, cookie, current_password, new_password, peer):
        with self.lock:
            # Recheck after acquiring the lock: another password change may have revoked this session.
            if not self.session(cookie):
                return "expired"
            result = self._verify_password(current_password, peer)
            if result != "ok":
                return result
            if hmac.compare_digest(current_password.encode(), new_password.encode()):
                return "same"
            salt = secrets.token_bytes(16)
            password_hash = self._password(new_password, salt)
            # Commit before replacing live credentials or invalidating any sessions.
            self.persist_password(salt, password_hash)
            self.salt, self.password_hash = salt, password_hash
            self.sessions.clear()
            return "ok"

    def session(self, cookie):
        with self.lock:
            key = hashlib.sha256(cookie.encode()).hexdigest()
            value = self.sessions.get(key)
            if value and value["expires"] > time.monotonic():
                return dict(value)
            self.sessions.pop(key, None)
            return None

    def logout(self, cookie):
        with self.lock:
            self.sessions.pop(hashlib.sha256(cookie.encode()).hexdigest(), None)
