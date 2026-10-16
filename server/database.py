"""Transactional ingestion for PostgreSQL, SQLite and MySQL/MariaDB."""
import hashlib
import hmac
import json
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

import psycopg
import pymysql

ERRORS = (psycopg.Error, pymysql.MySQLError, sqlite3.Error, OSError)


class ContentConflict(Exception):
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
