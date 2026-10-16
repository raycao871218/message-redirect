"""Authenticated SMS ingestion. Acknowledgements are returned only after commit."""
import hmac
import asyncio
from contextlib import asynccontextmanager
from typing import List
from uuid import UUID

from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from starlette.concurrency import run_in_threadpool

try:
    from . import database
    from .config import load_config
except ImportError:  # Also support `python server.py` in the container.
    import database
    from config import load_config

MAX_BODY_BYTES = 1024 * 1024

class Message(BaseModel):
    model_config = ConfigDict(extra="forbid")
    message_id: UUID
    sender: str = Field(max_length=256, strict=True)
    body: str = Field(max_length=50000, strict=True)
    sent_at: int = Field(ge=0, le=4102444800000, strict=True)
    captured_at: int = Field(ge=0, le=4102444800000, strict=True)
    source: str = Field(max_length=32, strict=True)

class Batch(BaseModel):
    model_config = ConfigDict(extra="forbid")
    device_id: UUID
    messages: List[Message] = Field(min_length=1, max_length=50)


fingerprint = database.fingerprint
connect = database.connect
initialize = database.initialize
ingest = database.ingest


def create_app(config, writer=None, bootstrap=True):
    token = config["api_token"]
    if not isinstance(token, str) or len(token) < 32:
        raise ValueError("api_token must contain at least 32 characters")

    @asynccontextmanager
    async def lifespan(app):
        if bootstrap:
            for attempt in range(10):
                try:
                    await run_in_threadpool(initialize, config)
                    break
                except database.ERRORS:
                    if attempt == 9:
                        raise RuntimeError("Database initialization failed; check private connection settings and permissions") from None
                    await asyncio.sleep(2)
        yield

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)

    @app.get("/health")
    def health():
        try:
            database.health(config)
        except database.ERRORS:
            raise HTTPException(503, "database unavailable")
        return {"status": "ok"}

    @app.post("/api/sms")
    async def receive(request: Request):
        auth = request.headers.get("authorization", "")
        if not hmac.compare_digest(auth.encode(), ("Bearer " + token).encode()):
            raise HTTPException(401, "unauthorized")
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
            return await run_in_threadpool(writer or ingest, config, batch)
        except database.ContentConflict:
            raise HTTPException(409, "message_id already exists with different content")
        except database.ERRORS:
            raise HTTPException(503, "database unavailable; retry later")

    return app


def main():
    import uvicorn
    config = load_config()
    uvicorn.run(create_app(config), host="0.0.0.0", port=config.get("port", 18081), access_log=False)

if __name__ == "__main__":
    main()
