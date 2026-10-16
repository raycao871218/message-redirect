import concurrent.futures
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from uuid import uuid4
from fastapi.testclient import TestClient
from server import database
from server.config import load_config
from server.server import Batch, create_app


class ConfigTest(unittest.TestCase):
    def test_sqlite_defaults_without_private_files(self):
        config = load_config({"SMS_API_TOKEN": "fixture-" + "x" * 32}, fallback_paths=[])
        self.assertEqual("sqlite", config["database"]["type"])
        self.assertEqual("/data/messages.sqlite3", config["database"]["path"])

    def test_legacy_json_and_environment_overrides(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config.json"
            path.write_text(json.dumps({"database": {"host": "fixture.invalid", "dbname": "original", "user": "fixture", "password": "original"}, "api_token": "x" * 32, "port": 18081}))
            config = load_config({"SMS_CONFIG_FILE": str(path)}, fallback_paths=[])
            self.assertEqual("postgresql", config["database"]["type"])
            self.assertEqual(5432, config["database"]["port"])
            secret = Path(directory) / "password"; secret.write_text("fixture-file-secret\n")
            config = load_config({"SMS_CONFIG_FILE": str(path), "SMS_DB_TYPE": "mysql", "SMS_DB_PORT": "3307", "SMS_DB_NAME": "custom", "SMS_DB_PASSWORD": "ignored", "SMS_DB_PASSWORD_FILE": str(secret)}, fallback_paths=[])
            self.assertEqual(("mysql", 3307, "custom", "fixture-file-secret"), tuple(config["database"][key] for key in ["type", "port", "dbname", "password"]))

    def test_explicit_bad_config_and_invalid_type_fail_closed(self):
        for env in [{"SMS_CONFIG_FILE": "nonexistent-fixture.json"}, {"SMS_DB_TYPE": "unexpected"}, {"SMS_DB_TYPE": "sqlite", "SMS_DB_PATH": ":memory:"}, {"SMS_DB_TYPE": "mysql", "SMS_DB_PORT": "not-a-port"}, {"SMS_PORT": "70000"}]:
            with self.subTest(env=env), self.assertRaises(ValueError):
                load_config(env, fallback_paths=[])


class SQLiteTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = str(Path(self.temp.name) / "archive.sqlite3")
        self.config = {"api_token": "fixture-" + "x" * 32, "database": {"type": "sqlite", "path": self.path}}
        self.payload = {"device_id": str(uuid4()), "messages": [{"message_id": str(uuid4()), "sender": "10086", "body": "短信 🍠 ' ; DROP TABLE --", "sent_at": 1001, "captured_at": 2002, "source": "fixture"}]}
        database.initialize(self.config)

    def tearDown(self):
        self.temp.cleanup()

    def rows(self):
        with database.connect(self.config) as conn:
            return conn.execute("SELECT message_id, body FROM sms_messages").fetchall()

    def test_authenticated_commit_duplicate_and_atomic_conflict(self):
        with TestClient(create_app(self.config)) as client:
            headers = {"Authorization": "Bearer " + self.config["api_token"]}
            self.assertEqual(401, client.post("/api/sms", json=self.payload).status_code)
            self.assertEqual([], self.rows())
            for _ in range(2):
                response = client.post("/api/sms", json=self.payload, headers=headers)
                self.assertEqual(200, response.status_code)
                self.assertEqual([self.payload["messages"][0]["message_id"]], response.json()["accepted"])
            self.assertEqual([(self.payload["messages"][0]["message_id"], self.payload["messages"][0]["body"])], self.rows())
            extra = dict(self.payload["messages"][0], message_id=str(uuid4()))
            conflict = dict(self.payload["messages"][0], body="conflict")
            response = client.post("/api/sms", json=dict(self.payload, messages=[extra, conflict]), headers=headers)
            self.assertEqual(409, response.status_code)
            self.assertEqual(1, len(self.rows()))
            self.assertEqual(200, client.get("/health").status_code)
        # Opening a new app/connection does not reset the persisted archive.
        with TestClient(create_app(self.config)) as client:
            self.assertEqual(1, len(self.rows()))

    def test_concurrent_retries_commit_one_record(self):
        batch = Batch.model_validate(self.payload)
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as workers:
            results = list(workers.map(lambda _: database.ingest(self.config, batch), range(12)))
        self.assertEqual(12, len(results))
        self.assertEqual(1, len(self.rows()))

    def test_database_outage_is_retryable_and_does_not_leak_details(self):
        config = dict(self.config, database={"type": "sqlite", "path": str(Path(self.temp.name) / "missing/secret-database.sqlite3")})
        with TestClient(create_app(config, bootstrap=False)) as client:
            self.assertEqual(503, client.get("/health").status_code)
            response = client.post("/api/sms", json=self.payload, headers={"Authorization": "Bearer " + config["api_token"]})
            self.assertEqual(503, response.status_code)
            self.assertNotIn("secret-database", response.text)


if __name__ == "__main__":
    unittest.main()
