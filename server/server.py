"""Authenticated SMS ingestion. Acknowledgements are returned only after commit."""
import hmac
import asyncio
from contextlib import asynccontextmanager
from typing import List
from uuid import UUID

from fastapi import Depends, FastAPI, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.openapi.utils import get_openapi
from fastapi.responses import FileResponse, JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from starlette.concurrency import run_in_threadpool

try:
    from . import database
    from .config import load_config
    from .settings import SettingsStore, VERSION
    from .admin import STATIC, register_admin
except ImportError:  # Also support `python server.py` in the container.
    import database
    from config import load_config
    from settings import SettingsStore, VERSION
    from admin import STATIC, register_admin

MAX_BODY_BYTES = 1024 * 1024

class Message(BaseModel):
    model_config = ConfigDict(extra="forbid")
    message_id: UUID
    sender: str = Field(max_length=256, strict=True)
    body: str = Field(max_length=50000, strict=True)
    sent_at: int = Field(ge=0, le=4102444800000, strict=True, description="短信发送时间，Unix 毫秒时间戳")
    captured_at: int = Field(ge=0, le=4102444800000, strict=True, description="客户端接收时间，Unix 毫秒时间戳")
    source: str = Field(max_length=32, strict=True, description="客户端自行定义的来源标记，如 live")

class Batch(BaseModel):
    model_config = ConfigDict(extra="forbid")
    device_id: UUID
    messages: List[Message] = Field(min_length=1, max_length=50)


class Acknowledgement(BaseModel):
    accepted: List[UUID] = Field(description="已提交入库的短信 ID；客户端收到完整确认后再标记完成")


class ApiError(BaseModel):
    detail: str


class SmsRecord(Message):
    device_id: UUID = Field(description="接收设备的 UUID；与 message_id 共同唯一标识短信")
    received_at: int = Field(description="服务端归档时间，Unix 毫秒时间戳")
    is_read: bool = Field(description="归档中的共享阅读状态；查询不会自动标记已读")
    read_at: int | None = Field(description="首次标记已读的 Unix 毫秒时间戳；未读时为 null")


class SmsPage(BaseModel):
    items: List[SmsRecord]
    next_cursor: str | None = Field(description="下一页游标；null 表示结束。继续查询时保留相同筛选条件")


class ReadState(BaseModel):
    model_config = ConfigDict(extra="forbid")
    is_read: bool = Field(strict=True, description="true 标记已读；false 恢复未读")


fingerprint = database.fingerprint
connect = database.connect
initialize = database.initialize
ingest = database.ingest


def create_app(config, writer=None, bootstrap=True):
    state = SettingsStore(config)
    state.database_ready = not bootstrap

    @asynccontextmanager
    async def lifespan(app):
        if bootstrap:
            for attempt in range(3):
                try:
                    await run_in_threadpool(initialize, state.snapshot())
                    state.database_ready = True
                    break
                except database.ERRORS:
                    # Keep the panel available to repair a broken database connection.
                    if attempt < 2:
                        await asyncio.sleep(1)
        yield

    app = FastAPI(title="咕咕咕短信归档 API", version=VERSION, lifespan=lifespan,
                  description="独立自托管短信归档服务，无需 NASBridge。先在管理面板取得访问密钥，再点击 Authorize 填写密钥。"
                              "相同 device_id + message_id 重试不会重复入库；内容冲突返回 409，整批回滚。"
                              "支持分页查询、单条详情及共享已读状态。读取不会自动标记已读，重复上传不会重置阅读状态。时间戳单位为毫秒。",
                  docs_url=None, redoc_url=None)
    app.state.settings = state
    app.mount("/static", StaticFiles(directory=STATIC), name="static")
    register_admin(app, state)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request, error):
        # Pydantic errors may contain private input. Never echo request contents.
        return JSONResponse({"detail": "invalid request"}, status_code=422)

    @app.middleware("http")
    async def private_response_headers(request, call_next):
        response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["Content-Security-Policy"] = (
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            "img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
        if not request.url.path.startswith("/static/"):
            response.headers["Cache-Control"] = "no-store"
        return response

    @app.get("/docs", include_in_schema=False)
    def docs():
        return FileResponse(STATIC / "docs.html")

    bearer = HTTPBearer(auto_error=False, scheme_name="SmsAccessKey", description="管理面板中的短信访问密钥，不是管理密码")

    def authorize(credentials: HTTPAuthorizationCredentials | None = Depends(bearer)):
        token = state.snapshot()["api_token"]
        if credentials is None or credentials.scheme.lower() != "bearer" or not hmac.compare_digest(credentials.credentials.encode(), token.encode()):
            raise HTTPException(401, "unauthorized")

    @app.get("/health", tags=["服务状态"], summary="检查数据库是否可用", responses={503: {"model": ApiError}})
    def health():
        try:
            with state.operation() as current:
                if not state.database_ready:
                    raise HTTPException(503, "database unavailable")
                database.health(current)
        except database.ERRORS:
            raise HTTPException(503, "database unavailable")
        return {"status": "ok"}

    def archive_operation(operation, *args, **kwargs):
        try:
            with state.operation() as current:
                if bootstrap and not state.database_ready:
                    initialize(current)
                    state.database_ready = True
                return operation(current, *args, **kwargs)
        except database.InvalidCursor:
            raise HTTPException(400, "invalid cursor or changed filters") from None
        except database.ERRORS:
            raise HTTPException(503, "database unavailable; retry later") from None

    read_errors = {401: {"model": ApiError, "description": "访问密钥无效"},
                   422: {"model": ApiError, "description": "参数格式不正确"},
                   503: {"model": ApiError, "description": "数据库暂时不可用"}}

    @app.get("/api/sms", tags=["短信查询"], summary="分页读取短信列表", response_model=SmsPage,
             dependencies=[Depends(authorize)], responses={**read_errors, 400: {"model": ApiError, "description": "游标无效或筛选条件已改变"}},
             description="按发送时间倒序，相同时间按设备和短信 ID 倒序。首次请求不传 cursor，后续使用 next_cursor 并保持筛选条件一致。不会自动标记已读。")
    def sms_list(limit: int = Query(50, ge=1, le=100), cursor: str | None = Query(None, min_length=1, max_length=1024),
                 device_id: UUID | None = None, sender: str | None = Query(None, max_length=256), is_read: bool | None = None,
                 sent_from: int | None = Query(None, ge=0, le=4102444800000, description="发送时间起点（含），Unix 毫秒"),
                 sent_until: int | None = Query(None, ge=0, le=4102444800000, description="发送时间终点（含），Unix 毫秒")):
        if sent_from is not None and sent_until is not None and sent_from > sent_until:
            raise HTTPException(422, "sent_from must not be later than sent_until")
        return archive_operation(database.list_messages, limit=limit, cursor=cursor, device_id=device_id, sender=sender,
                                 is_read=is_read, sent_from=sent_from, sent_until=sent_until)

    @app.get("/api/sms/{device_id}/{message_id}", tags=["短信查询"], summary="读取单条短信", response_model=SmsRecord,
             dependencies=[Depends(authorize)], responses={**read_errors, 404: {"model": ApiError, "description": "短信不存在"}},
             description="device_id 与 message_id 共同定位短信，避免不同设备使用相同短信 ID 时读错记录。不会自动标记已读。")
    def sms_detail(device_id: UUID, message_id: UUID):
        value = archive_operation(database.get_message, device_id, message_id)
        if value is None:
            raise HTTPException(404, "message not found")
        return value

    @app.patch("/api/sms/{device_id}/{message_id}/read", tags=["阅读状态"], summary="标记已读或未读", response_model=SmsRecord,
               dependencies=[Depends(authorize)], responses={**read_errors, 404: {"model": ApiError, "description": "短信不存在"}},
               description="所有客户端共享归档阅读状态，与手机收件箱无关。重复标记已读保留首次时间；标记未读会清空 read_at。")
    def sms_read_state(device_id: UUID, message_id: UUID, value: ReadState):
        record = archive_operation(database.set_read_state, device_id, message_id, value.is_read)
        if record is None:
            raise HTTPException(404, "message not found")
        return record

    @app.post("/api/sms", tags=["短信归档"], summary="批量保存短信", response_model=Acknowledgement,
              dependencies=[Depends(authorize)], responses={code: {"model": ApiError, "description": detail} for code, detail in {
                  401: "访问密钥无效", 409: "相同 ID 的内容冲突，整批未写入", 413: "请求超过 1 MiB", 422: "短信格式不正确", 503: "数据库暂时不可用，可稍后重试"}.items()},
              openapi_extra={"requestBody": {"required": True, "content": {"application/json": {
                  "schema": {"$ref": "#/components/schemas/Batch"},
                  "example": {"device_id": "ac280128-8cb8-41c1-9cd4-95ee2ebf2d0e", "messages": [{
                      "message_id": "5172727d-f189-44dc-bbf8-1692f50fdfb1", "sender": "EXAMPLE", "body": "这是一条用于接入验证的合成短信",
                      "sent_at": 1792000000000, "captured_at": 1792000001000, "source": "api-demo"}]}}}}})
    async def receive(request: Request):
        data = bytearray()
        async for chunk in request.stream():
            data.extend(chunk)
            if len(data) > MAX_BODY_BYTES:
                raise HTTPException(413, "batch too large")
        try:
            batch = Batch.model_validate_json(bytes(data))
        except ValidationError:
            # Validation errors can include the original SMS body; do not return it.
            raise HTTPException(422, "invalid SMS batch")
        try:
            def write():
                with state.operation() as current:
                    if bootstrap and not state.database_ready:
                        initialize(current)
                        state.database_ready = True
                    return (writer or ingest)(current, batch)
            return await run_in_threadpool(write)
        except database.ContentConflict:
            raise HTTPException(409, "message_id already exists with different content")
        except database.ERRORS:
            raise HTTPException(503, "database unavailable; retry later")

    def documented_schema():
        if app.openapi_schema is None:
            schema = get_openapi(title=app.title, version=VERSION, description=app.description, routes=app.routes)
            batch_schema = Batch.model_json_schema(ref_template="#/components/schemas/{model}")
            definitions = batch_schema.pop("$defs", {})
            schema.setdefault("components", {}).setdefault("schemas", {}).update(definitions, Batch=batch_schema)
            app.openapi_schema = schema
        return app.openapi_schema
    app.openapi = documented_schema
    return app


def main():
    import uvicorn
    config = load_config()
    uvicorn.run(create_app(config), host="0.0.0.0", port=config.get("port", 18081), access_log=False)

if __name__ == "__main__":
    main()
