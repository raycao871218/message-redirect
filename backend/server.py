"""Authenticated SMS ingestion. Acknowledgements are returned only after commit."""
import hashlib
import hmac
import json
import os
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import List
from uuid import UUID

import psycopg
from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from starlette.concurrency import run_in_threadpool

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


def fingerprint(message):
    return hashlib.sha256(json.dumps(message.model_dump(mode="json"), sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def connect(config):
    return psycopg.connect(**config["database"], connect_timeout=5,
                           options="-c statement_timeout=10000 -c lock_timeout=5000")


def initialize(config):
    with connect(config) as conn:
        conn.execute(Path(__file__).with_name("schema.sql").read_text())


def ingest(config, batch):
    accepted = []
    with connect(config) as conn:
        for message in batch.messages:
            digest = fingerprint(message)
            row = conn.execute("""
                INSERT INTO message_redirect.sms_messages
                    (device_id,message_id,sender,body,sent_at,captured_at,source,payload_hash)
                VALUES (%s,%s,%s,%s,%s,%s,%s,%s)
                ON CONFLICT (device_id,message_id) DO NOTHING RETURNING message_id
            """, (batch.device_id, message.message_id, message.sender, message.body,
                  datetime.fromtimestamp(message.sent_at / 1000, timezone.utc),
                  datetime.fromtimestamp(message.captured_at / 1000, timezone.utc),
                  message.source, digest)).fetchone()
            if row is None:
                old = conn.execute("SELECT payload_hash FROM message_redirect.sms_messages WHERE device_id=%s AND message_id=%s",
                                   (batch.device_id, message.message_id)).fetchone()
                if old is None or not hmac.compare_digest(old[0], digest):
                    raise HTTPException(409, "message_id already exists with different content")
            accepted.append(str(message.message_id))
    return {"accepted": accepted}


def create_app(config, writer=None, bootstrap=True):
    token = config["api_token"]
    if not isinstance(token, str) or len(token) < 32:
        raise ValueError("api_token must contain at least 32 characters")

    @asynccontextmanager
    async def lifespan(app):
        if bootstrap:
            await run_in_threadpool(initialize, config)
        yield

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)

    @app.get("/health")
    def health():
        try:
            with connect(config) as conn:
                conn.execute("SELECT 1").fetchone()
        except psycopg.Error:
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
        except psycopg.Error:
            raise HTTPException(503, "database unavailable; retry later")

    return app


def main():
    import uvicorn
    config_path = os.environ.get("SMS_CONFIG_FILE", str(Path(__file__).resolve().parent.parent / ".secrets/server.json"))
    config = json.loads(Path(config_path).read_text())
    uvicorn.run(create_app(config), host="0.0.0.0", port=config.get("port", 18081), access_log=False)

if __name__ == "__main__":
    main()
