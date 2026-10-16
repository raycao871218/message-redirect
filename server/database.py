"""Transactional ingestion for PostgreSQL, SQLite and MySQL/MariaDB."""
import hashlib
import hmac
import json
import base64
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID

import psycopg
import pymysql

ERRORS = (psycopg.Error, pymysql.MySQLError, sqlite3.Error, OSError)


class ContentConflict(Exception):
    pass


class InvalidCursor(ValueError):
    pass


def kind(config):
    return config["database"].get("type", "postgresql").lower()


def fingerprint(message):
    # Preserve the legacy PostgreSQL payload hash, so retries across upgrades remain idempotent.
    return hashlib.sha256(json.dumps(message.model_dump(mode="json"), sort_keys=True, ensure_ascii=False).encode()).hexdigest()


@contextmanager
def connect(config):
    db = dict(config["database"])
    engine = db.pop("type", "postgresql").lower()
    if engine == "sqlite":
        conn = sqlite3.connect(db["path"], timeout=5)
    elif engine in {"mysql", "mariadb"}:
        allowed = {key: value for key, value in db.items() if key in {"host", "port", "user", "password", "ssl_ca", "ssl_cert", "ssl_key", "ssl_verify_cert", "ssl_verify_identity"}}
        conn = pymysql.connect(**allowed, database=db["dbname"], charset="utf8mb4", autocommit=False,
                               connect_timeout=5, read_timeout=15, write_timeout=15,
                               init_command="SET time_zone='+00:00', innodb_lock_wait_timeout=5")
    elif engine in {"postgresql", "postgres"}:
        conn = psycopg.connect(**db, connect_timeout=5, options="-c statement_timeout=10000 -c lock_timeout=5000")
    else:
        raise ValueError("Unsupported database type")
    try:
        yield conn
        conn.commit()
    except BaseException:
        conn.rollback()
        raise
    finally:
        conn.close()


def initialize(config):
    engine = kind(config)
    if engine == "sqlite":
        Path(config["database"]["path"]).parent.mkdir(parents=True, exist_ok=True)
    filename = "schema.sql" if engine in {"postgresql", "postgres"} else "schema_mysql.sql" if engine in {"mysql", "mariadb"} else "schema_sqlite.sql"
    with connect(config) as conn:
        if engine == "sqlite":
            conn.execute("PRAGMA journal_mode=WAL")
        # Schema files contain only fixed CREATE statements, never user input.
        with conn.cursor() if engine != "sqlite" else _sqlite_cursor(conn) as cursor:
            for statement in Path(__file__).with_name(filename).read_text(encoding="utf-8").split(";"):
                if statement.strip():
                    cursor.execute(statement)
            # CREATE TABLE IF NOT EXISTS also runs against pre-0.8.1 archives.
            if engine == "sqlite":
                cursor.execute("PRAGMA table_info(sms_messages)")
                present = any(row[1] == "read_at" for row in cursor.fetchall())
                table, column_type = "sms_messages", "TEXT"
            elif engine in {"postgresql", "postgres"}:
                cursor.execute("SELECT 1 FROM information_schema.columns WHERE table_schema='message_redirect' AND table_name='sms_messages' AND column_name='read_at'")
                present = cursor.fetchone() is not None
                table, column_type = "message_redirect.sms_messages", "TIMESTAMPTZ"
            else:
                cursor.execute("SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sms_messages' AND column_name='read_at'")
                present = cursor.fetchone() is not None
                table, column_type = "sms_messages", "DATETIME(6)"
            if not present:
                cursor.execute(f"ALTER TABLE {table} ADD COLUMN read_at {column_type} NULL")


@contextmanager
def _sqlite_cursor(conn):
    cursor = conn.cursor()
    try:
        yield cursor
    finally:
        cursor.close()


def health(config):
    with connect(config) as conn:
        with _sqlite_cursor(conn) if kind(config) == "sqlite" else conn.cursor() as cursor:
            cursor.execute("SELECT 1")
            cursor.fetchone()


def ingest(config, batch):
    engine = kind(config)
    table = "message_redirect.sms_messages" if engine in {"postgresql", "postgres"} else "sms_messages"
    placeholder = "?" if engine == "sqlite" else "%s"
    conflict = "ON DUPLICATE KEY UPDATE message_id=message_id" if engine in {"mysql", "mariadb"} else "ON CONFLICT (device_id,message_id) DO NOTHING"
    insert = f"INSERT INTO {table} (device_id,message_id,sender,body,sent_at,captured_at,source,payload_hash) VALUES ({','.join([placeholder] * 8)}) {conflict}"
    select = f"SELECT payload_hash FROM {table} WHERE device_id={placeholder} AND message_id={placeholder}"
    if engine in {"mysql", "mariadb"}:
        select += " FOR UPDATE"
    accepted = []
    with connect(config) as conn:
        with _sqlite_cursor(conn) if engine == "sqlite" else conn.cursor() as cursor:
            for message in batch.messages:
                digest = fingerprint(message)
                device_id = batch.device_id if engine in {"postgresql", "postgres"} else str(batch.device_id)
                message_id = message.message_id if engine in {"postgresql", "postgres"} else str(message.message_id)
                sent = datetime.fromtimestamp(message.sent_at / 1000, timezone.utc)
                captured = datetime.fromtimestamp(message.captured_at / 1000, timezone.utc)
                if engine == "sqlite":
                    sent, captured = sent.isoformat(), captured.isoformat()
                elif engine in {"mysql", "mariadb"}:
                    sent, captured = sent.replace(tzinfo=None), captured.replace(tzinfo=None)
                cursor.execute(insert, (device_id, message_id, message.sender, message.body, sent, captured, message.source, digest))
                cursor.execute(select, (device_id, message_id))
                old = cursor.fetchone()
                if old is None or not hmac.compare_digest(old[0], digest):
                    raise ContentConflict()
                accepted.append(str(message.message_id))
    # The context manager has committed before we acknowledge any IDs.
    return {"accepted": accepted}


def _utc(value):
    if isinstance(value, str):
        value = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)


def _milliseconds(value):
    delta = _utc(value) - datetime(1970, 1, 1, tzinfo=timezone.utc)
    return delta.days * 86400000 + delta.seconds * 1000 + delta.microseconds // 1000


def _db_time(engine, value):
    if engine == "sqlite":
        return value.isoformat()
    return value.replace(tzinfo=None) if engine in {"mysql", "mariadb"} else value


_READ_COLUMNS = "device_id,message_id,sender,body,sent_at,captured_at,source,received_at,read_at"


def _message(row):
    value = dict(zip(_READ_COLUMNS.split(","), row))
    value["device_id"], value["message_id"] = str(value["device_id"]), str(value["message_id"])
    value["is_read"] = value["read_at"] is not None
    for key in ("sent_at", "captured_at", "received_at", "read_at"):
        value[key] = _milliseconds(value[key]) if value[key] is not None else None
    return value


def _query_parts(config):
    engine = kind(config)
    return engine, "message_redirect.sms_messages" if engine in {"postgresql", "postgres"} else "sms_messages", "?" if engine == "sqlite" else "%s"


def list_messages(config, *, limit=50, cursor=None, device_id=None, sender=None, is_read=None, sent_from=None, sent_until=None):
    engine, table, mark = _query_parts(config)
    filters = {"device_id": str(device_id) if device_id else None, "sender": sender, "is_read": is_read, "sent_from": sent_from, "sent_until": sent_until}
    signature = hashlib.sha256(json.dumps(filters, sort_keys=True).encode()).hexdigest()
    where, parameters = [], []
    for key in ("device_id", "sender"):
        if filters[key] is not None:
            where.append(f"{key}={mark}"); parameters.append(filters[key])
    if is_read is not None:
        where.append("read_at IS NOT NULL" if is_read else "read_at IS NULL")
    for value, comparison in ((sent_from, ">="), (sent_until, "<=")):
        if value is not None:
            where.append(f"sent_at{comparison}{mark}")
            parameters.append(_db_time(engine, datetime.fromtimestamp(value / 1000, timezone.utc)))
    if cursor:
        try:
            value = json.loads(base64.b64decode(cursor + "=" * (-len(cursor) % 4), altchars=b"-_", validate=True))
            if set(value) != {"v", "sent_at", "device_id", "message_id", "filters"} or value["v"] != 1 or value["filters"] != signature:
                raise ValueError()
            moment = _utc(value["sent_at"])
            boundary = value["sent_at"] if engine == "sqlite" else _db_time(engine, moment)
            device, message = str(UUID(value["device_id"])), str(UUID(value["message_id"]))
            where.append(f"(sent_at,device_id,message_id)<({mark},{mark},{mark})")
            parameters.extend([boundary, device, message])
        except (ValueError, TypeError, KeyError, AttributeError, OverflowError):
            raise InvalidCursor() from None
    clause = " WHERE " + " AND ".join(where) if where else ""
    with connect(config) as conn:
        with _sqlite_cursor(conn) if engine == "sqlite" else conn.cursor() as q:
            q.execute(f"SELECT {_READ_COLUMNS} FROM {table}{clause} ORDER BY sent_at DESC,device_id DESC,message_id DESC LIMIT {mark}", (*parameters, limit + 1))
            rows = q.fetchall()
    next_cursor = None
    if len(rows) > limit:
        last = rows[limit - 1]
        value = {"v": 1, "sent_at": last[4] if isinstance(last[4], str) else last[4].isoformat(),
                 "device_id": str(last[0]), "message_id": str(last[1]), "filters": signature}
        next_cursor = base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).decode().rstrip("=")
    return {"items": [_message(row) for row in rows[:limit]], "next_cursor": next_cursor}


def get_message(config, device_id, message_id):
    engine, table, mark = _query_parts(config)
    with connect(config) as conn:
        with _sqlite_cursor(conn) if engine == "sqlite" else conn.cursor() as q:
            q.execute(f"SELECT {_READ_COLUMNS} FROM {table} WHERE device_id={mark} AND message_id={mark}", (str(device_id), str(message_id)))
            row = q.fetchone()
    return _message(row) if row else None


def set_read_state(config, device_id, message_id, is_read):
    engine, table, mark = _query_parts(config)
    now = datetime.now(timezone.utc)
    now = now.replace(microsecond=now.microsecond // 1000 * 1000)
    value = _db_time(engine, now) if is_read else None
    with connect(config) as conn:
        with _sqlite_cursor(conn) if engine == "sqlite" else conn.cursor() as q:
            # Repeated "read" calls keep the first timestamp; ingest never changes this column.
            expression = f"COALESCE(read_at,{mark})" if is_read else mark
            q.execute(f"UPDATE {table} SET read_at={expression} WHERE device_id={mark} AND message_id={mark}", (value, str(device_id), str(message_id)))
            q.execute(f"SELECT {_READ_COLUMNS} FROM {table} WHERE device_id={mark} AND message_id={mark}", (str(device_id), str(message_id)))
            row = q.fetchone()
    return _message(row) if row else None
