"""Cookie-authenticated administration, separate from the public SMS access token."""
import hmac
import io
from pathlib import Path
import secrets
from typing import Literal
from urllib.parse import urlsplit

from fastapi import HTTPException, Request
from fastapi.responses import FileResponse, Response
from pydantic import BaseModel, ConfigDict, Field, ValidationError
import qrcode
import qrcode.image.svg

try:
    from . import database
    from .settings import VERSION, SESSION_SECONDS, service_url
except ImportError:
    import database
    from settings import VERSION, SESSION_SECONDS, service_url

COOKIE = "gugugu_admin"
STATIC = Path(__file__).with_name("static")


class Login(BaseModel):
    model_config = ConfigDict(extra="forbid")
    password: str = Field(min_length=1, max_length=1024, strict=True)


class PasswordChange(BaseModel):
    model_config = ConfigDict(extra="forbid")
    current_password: str = Field(min_length=1, max_length=1024, strict=True)
    new_password: str = Field(min_length=12, max_length=1024, strict=True)
    confirm_password: str = Field(min_length=12, max_length=1024, strict=True)


class DatabaseInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    type: Literal["sqlite", "postgresql", "mysql", "mariadb"]
    path: str = Field(default="/data/messages.sqlite3", max_length=1024, strict=True)
    host: str = Field(default="", max_length=253, strict=True)
    port: int = Field(default=5432, ge=1, le=65535, strict=True)
    dbname: str = Field(default="", max_length=128, strict=True)
    user: str = Field(default="", max_length=128, strict=True)
    password: str = Field(default="", max_length=4096, strict=True)
    sslmode: Literal["disable", "allow", "prefer", "require", "verify-ca", "verify-full"] | None = None


class PublicUrl(BaseModel):
    model_config = ConfigDict(extra="forbid")
    url: str = Field(min_length=1, max_length=2048, strict=True)


async def parse(request, model, error_message="配置内容不正确，请检查必填项和端口范围", max_bytes=8192):
    if request.headers.get("content-type", "").split(";")[0].strip() != "application/json":
        raise HTTPException(415, "请使用 JSON 请求")
    raw = bytearray()
    async for chunk in request.stream():
        raw.extend(chunk)
        if len(raw) > max_bytes:
            raise HTTPException(413, "配置内容过大")
    try:
        return model.model_validate_json(bytes(raw))
    except ValidationError:
        raise HTTPException(422, error_message) from None


def same_origin(request):
    origin = request.headers.get("origin")
    if origin and origin != str(request.base_url).rstrip("/"):
        raise HTTPException(403, "请从本服务的管理面板操作")


def require_session(request, state, mutate=False):
    session = state.session(request.cookies.get(COOKIE, "")) if state.enabled else None
    if not session:
        raise HTTPException(401, "请先登录管理面板")
    if mutate:
        same_origin(request)
        if not hmac.compare_digest(request.headers.get("x-csrf-token", ""), session["csrf"]):
            raise HTTPException(403, "页面验证已失效，请刷新后重试")
    return session


def candidate_database(value, current):
    old = current["database"]
    if value.type == "sqlite":
        path = value.path.strip()
        if not path or path == ":memory:" or "\x00" in path:
            raise HTTPException(422, "请填写持久化的 SQLite 文件路径")
        result = {"type": "sqlite", "path": path}
    else:
        if not all(part.strip() for part in (value.host, value.dbname, value.user)) or any(c.isspace() for c in value.host):
            raise HTTPException(422, "请填写数据库主机、数据库名和账号")
        same_target = all(old.get(key) == item for key, item in {
            "type": value.type, "host": value.host.strip(), "port": value.port,
            "dbname": value.dbname.strip(), "user": value.user.strip()}.items())
        if not value.password and (not same_target or not old.get("password")):
            raise HTTPException(422, "新增连接或更换主机、库名、账号时，请重新填写数据库密码")
        # Keep existing certificate/SSL policy when editing another endpoint of the same engine.
        result = dict(old) if same_target else {key: item for key, item in old.items()
                                               if old.get("type") == value.type and (key.startswith("ssl") or key in {"channel_binding", "gssencmode"})}
        result.update(type=value.type, host=value.host.strip(), port=value.port,
                      dbname=value.dbname.strip(), user=value.user.strip(), password=value.password or old["password"])
        if value.type == "postgresql" and value.sslmode is not None:
            result["sslmode"] = value.sslmode
    return dict(current, database=result)


def register_admin(app, state):
    @app.get("/", include_in_schema=False)
    def panel():
        return FileResponse(STATIC / "index.html")

    @app.get("/admin/api/session", include_in_schema=False)
    def session(request: Request):
        value = state.session(request.cookies.get(COOKIE, "")) if state.enabled else None
        return {"configured": state.enabled, "authenticated": bool(value), "csrf": value["csrf"] if value else None}

    @app.post("/admin/api/login", include_in_schema=False)
    async def login(request: Request):
        same_origin(request)
        if not state.enabled:
            raise HTTPException(503, "请先设置至少 12 位的 SMS_ADMIN_PASSWORD 或 SMS_ADMIN_PASSWORD_FILE，并挂载 /data 状态卷")
        data = await parse(request, Login)
        from starlette.concurrency import run_in_threadpool
        cookie, value = await run_in_threadpool(state.login, data.password, request.client.host if request.client else "local")
        if cookie == "limited":
            raise HTTPException(429, "尝试次数过多，请五分钟后再试", headers={"Retry-After": "300"})
        if cookie == "invalid":
            raise HTTPException(401, "管理密码不正确")
        from fastapi.responses import JSONResponse
        response = JSONResponse({"csrf": value["csrf"]})
        response.set_cookie(COOKIE, cookie, max_age=SESSION_SECONDS, httponly=True,
                            secure=request.url.scheme == "https", samesite="strict", path="/")
        return response

    @app.post("/admin/api/logout", include_in_schema=False)
    def logout(request: Request):
        require_session(request, state, True)
        state.logout(request.cookies.get(COOKIE, ""))
        from fastapi.responses import JSONResponse
        response = JSONResponse({"ok": True})
        response.delete_cookie(COOKIE, path="/")
        return response

    @app.post("/admin/api/password", include_in_schema=False)
    async def change_password(request: Request):
        require_session(request, state, True)
        data = await parse(request, PasswordChange, "请填写当前密码，新密码和确认密码需要 12–1024 个字符", max_bytes=16384)
        if not hmac.compare_digest(data.new_password.encode(), data.confirm_password.encode()):
            raise HTTPException(422, "两次输入的新密码不一致")
        if not data.new_password.strip() or any(c in data.new_password for c in "\r\n\x00"):
            raise HTTPException(422, "新密码不能全为空格或包含换行、空字符")
        from starlette.concurrency import run_in_threadpool
        try:
            result = await run_in_threadpool(state.change_password, request.cookies.get(COOKIE, ""),
                                            data.current_password, data.new_password, request.client.host if request.client else "local")
        except OSError:
            raise HTTPException(503, "密码保存失败，原密码仍可使用；请检查状态卷写入权限") from None
        if result == "limited":
            raise HTTPException(429, "尝试次数过多，请五分钟后再试", headers={"Retry-After": "300"})
        if result == "expired":
            raise HTTPException(401, "登录已失效，请重新登录")
        if result == "invalid":
            raise HTTPException(400, "当前密码不正确")
        if result == "same":
            raise HTTPException(422, "新密码不能与当前密码相同")
        from fastapi.responses import JSONResponse
        response = JSONResponse({"ok": True, "message": "登录密码已更新，请使用新密码重新登录"})
        response.delete_cookie(COOKIE, path="/")
        return response

    @app.get("/admin/api/config", include_in_schema=False)
    def config(request: Request):
        require_session(request, state)
        with state.operation() as current:
            db = current["database"]
            visible = {key: db[key] for key in ["type", "path", "host", "port", "dbname", "user", "sslmode"] if key in db}
            visible["password_saved"] = bool(db.get("password"))
            return {"version": VERSION, "database": visible, "api_token": current["api_token"],
                    "public_url": state.public_url, "database_ready": state.database_ready}

    @app.post("/admin/api/database/test", include_in_schema=False)
    async def test_database(request: Request):
        require_session(request, state, True)
        data = await parse(request, DatabaseInput)
        from starlette.concurrency import run_in_threadpool
        def check():
            with state.operation() as current:
                candidate = candidate_database(data, current)
                try:
                    # SQLite connection checks must not create a typo'd path or empty database file.
                    if data.type == "sqlite" and not Path(candidate["database"]["path"]).is_file():
                        return {"ok": True, "message": "此文件尚未创建，保存时会创建并验证数据库；当前连接保持不变"}
                    database.health(candidate)
                except database.ERRORS:
                    raise HTTPException(400, "连接失败，请检查地址、账号、密码、网络和数据库访问权限") from None
                return {"ok": True, "message": "连接成功；保存前不会切换当前数据库"}
        return await run_in_threadpool(check)

    @app.post("/admin/api/database", include_in_schema=False)
    async def save_database(request: Request):
        require_session(request, state, True)
        data = await parse(request, DatabaseInput)
        from starlette.concurrency import run_in_threadpool
        def save():
            with state.operation() as current:
                candidate = candidate_database(data, current)
                try:
                    database.initialize(candidate)
                    state.replace(candidate)
                except database.ERRORS:
                    raise HTTPException(400, "保存失败，原连接保持不变。请检查数据库连接、建表权限和状态卷写入权限") from None
                state.database_ready = True
            return {"ok": True, "message": "已保存。后续短信使用此数据库，原数据库中的短信保持原样"}
        return await run_in_threadpool(save)

    @app.post("/admin/api/token/reset", include_in_schema=False)
    def reset_token(request: Request):
        require_session(request, state, True)
        with state.operation() as current:
            token = secrets.token_urlsafe(36)
            try:
                state.replace(dict(current, api_token=token))
            except OSError:
                raise HTTPException(503, "密钥保存失败，原密钥保持有效；请检查状态卷写入权限") from None
        return {"api_token": token}

    @app.post("/admin/api/public-url", include_in_schema=False)
    async def save_url(request: Request):
        require_session(request, state, True)
        data = await parse(request, PublicUrl)
        try:
            value = service_url(data.url)
            with state.operation() as current:
                state.replace(current, value)
        except ValueError as error:
            raise HTTPException(422, str(error)) from None
        except OSError:
            raise HTTPException(503, "服务地址保存失败，请检查状态卷写入权限") from None
        return {"ok": True, "url": value}

    @app.get("/admin/api/pairing", include_in_schema=False)
    def pairing(request: Request):
        require_session(request, state)
        return pairing_data(state)

    @app.get("/admin/api/pairing.svg", include_in_schema=False)
    def pairing_qr(request: Request):
        require_session(request, state)
        import json
        value = json.dumps(pairing_data(state), ensure_ascii=False, separators=(",", ":"))
        qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=4, box_size=8)
        qr.add_data(value); qr.make(fit=True)
        image = qr.make_image(image_factory=qrcode.image.svg.SvgPathFillImage)
        buffer = io.BytesIO(); image.save(buffer)
        return Response(buffer.getvalue(), media_type="image/svg+xml")


def pairing_data(state):
    with state.operation() as current:
        if not state.public_url:
            raise HTTPException(409, "请先保存手机可以访问的服务地址")
        return {"type": "gugugu.server", "version": 1, "name": "咕咕咕服务",
                "url": state.public_url, "api_token": current["api_token"]}
