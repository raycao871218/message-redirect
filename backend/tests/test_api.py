import unittest
from uuid import uuid4
import psycopg
from fastapi.testclient import TestClient
from backend.server import create_app

class ApiTest(unittest.TestCase):
    def setUp(self):
        self.token = "test-token-" + "x" * 32
        self.calls = []
        def writer(config, batch):
            self.calls.append(batch)
            return {"accepted": [str(m.message_id) for m in batch.messages]}
        self.client = TestClient(create_app({"api_token": self.token}, writer=writer, bootstrap=False))
        self.headers = {"Authorization": "Bearer " + self.token}
        self.payload = {"device_id": str(uuid4()), "messages": [{"message_id": str(uuid4()), "sender": "10086", "body": "短信测试 ' ; DROP TABLE --", "sent_at": 1000, "captured_at": 2000, "source": "实时接收"}]}

    def test_unauthorized_never_writes(self):
        for headers in [{}, {"Authorization": "Bearer wrong"}]:
            self.assertEqual(401, self.client.post("/api/sms", json=self.payload, headers=headers).status_code)
        self.assertFalse(self.calls)

    def test_acknowledgement(self):
        r = self.client.post("/api/sms", json=self.payload, headers=self.headers)
        self.assertEqual(200, r.status_code)
        self.assertEqual([self.payload["messages"][0]["message_id"]], r.json()["accepted"])
        self.assertEqual(self.payload["messages"][0]["body"], self.calls[0].messages[0].body)

    def test_invalid_payload_no_content_leak(self):
        self.payload["messages"][0]["unexpected"] = "private-content"
        r = self.client.post("/api/sms", json=self.payload, headers=self.headers)
        self.assertEqual(422, r.status_code)
        self.assertNotIn("private-content", r.text)
        self.assertFalse(self.calls)

    def test_empty_batch(self):
        self.payload["messages"] = []
        self.assertEqual(422, self.client.post("/api/sms", json=self.payload, headers=self.headers).status_code)

    def test_oversized_body(self):
        self.assertEqual(413, self.client.post("/api/sms", content=b"x" * (1024 * 1024 + 1), headers=self.headers).status_code)
        self.assertFalse(self.calls)

    def test_invalid_timestamp(self):
        self.payload["messages"][0]["sent_at"] = True
        self.assertEqual(422, self.client.post("/api/sms", json=self.payload, headers=self.headers).status_code)

    def test_database_failure_is_retryable(self):
        def fail(config, batch):
            raise psycopg.OperationalError("sensitive database details")
        client = TestClient(create_app({"api_token": self.token}, writer=fail, bootstrap=False))
        r = client.post("/api/sms", json=self.payload, headers=self.headers)
        self.assertEqual(503, r.status_code)
        self.assertNotIn("sensitive", r.text)

if __name__ == "__main__": unittest.main()
