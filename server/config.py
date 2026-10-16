"""Private JSON configuration with optional environment/file overrides for containers."""
import json
import os
from pathlib import Path


def _secret(env, name, fallback=""):
    if env.get(name + "_FILE"):
        try:
            return Path(env[name + "_FILE"]).read_text(encoding="utf-8").rstrip("\r\n")
        except OSError:
            raise ValueError(f"Cannot read {name}_FILE") from None
    return env.get(name, fallback)


def load_config(env=None, fallback_paths=None):
    env = os.environ if env is None else env
    config = {}
    path = env.get("SMS_CONFIG_FILE")
    if path:
        try:
            config = json.loads(Path(path).read_text(encoding="utf-8"))
        except (OSError, ValueError):
            raise ValueError("Cannot load SMS_CONFIG_FILE") from None
    else:
        # Keep the existing NAS read-only secret mount working without changing its Compose file.
        candidates = [Path("/run/secrets/server.json"), Path(__file__).resolve().parent.parent / ".secrets/server.json"] if fallback_paths is None else fallback_paths
        for candidate in candidates:
            if candidate.is_file():
                config = json.loads(candidate.read_text(encoding="utf-8"))
                break
    database = dict(config.get("database", {}))
    default_type = database.get("type", "postgresql" if database.get("host") else "sqlite")
    kind = env.get("SMS_DB_TYPE", default_type).lower()
    if kind == "postgres":
        kind = "postgresql"
    if kind not in {"sqlite", "postgresql", "mysql", "mariadb"}:
        raise ValueError("SMS_DB_TYPE must be sqlite, postgresql, mysql or mariadb")
    database["type"] = kind
    if kind == "sqlite":
        database["path"] = env.get("SMS_DB_PATH", database.get("path", "/data/messages.sqlite3"))
        if not database["path"] or database["path"] == ":memory:":
            raise ValueError("SQLite requires a persistent file path")
    else:
        for variable, key, fallback in [("SMS_DB_HOST", "host", "database"), ("SMS_DB_NAME", "dbname", "sms_archive"), ("SMS_DB_USER", "user", "sms_ingest")]:
            database[key] = env.get(variable, database.get(key, fallback))
        database["password"] = _secret(env, "SMS_DB_PASSWORD", database.get("password", ""))
        try:
            database["port"] = int(env.get("SMS_DB_PORT", database.get("port", 5432 if kind == "postgresql" else 3306)))
            if not 1 <= database["port"] <= 65535:
                raise ValueError()
        except (TypeError, ValueError):
            raise ValueError("Invalid database port") from None
        if kind == "postgresql" and "SMS_DB_SSLMODE" in env:
            database["sslmode"] = env["SMS_DB_SSLMODE"]
    try:
        port = int(env.get("SMS_PORT", config.get("port", 18081)))
        if not 1 <= port <= 65535:
            raise ValueError()
    except (TypeError, ValueError):
        raise ValueError("Invalid HTTP port") from None
    return {"database": database, "api_token": _secret(env, "SMS_API_TOKEN", config.get("api_token", "")), "port": port}
