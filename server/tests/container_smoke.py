"""Run inside an isolated API test container; uses synthetic messages only."""
import json
import os
from uuid import UUID, uuid5
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from config import load_config
import database

config = load_config()
device = os.environ["SMS_TEST_DEVICE_ID"]
message_id = str(uuid5(UUID(device), "original"))
message = {"message_id": message_id, "sender": "SYNTHETIC_TEST", "body": "短信测试 🍠 ' ; DROP TABLE --", "sent_at": 1792000000123, "captured_at": 1792000001234, "source": "container-test"}
payload = {"device_id": device, "messages": [message]}
engine = database.kind(config)
table = "message_redirect.sms_messages" if engine == "postgresql" else "sms_messages"
placeholder = "?" if engine == "sqlite" else "%s"

def post(data, authenticated=True):
    headers = {"Content-Type": "application/json"}
    if authenticated:
        headers["Authorization"] = "Bearer " + config["api_token"]
    try:
        with urlopen(Request("http://127.0.0.1:18081/api/sms", data=json.dumps(data).encode(), headers=headers), timeout=20) as response:
            return response.status, json.loads(response.read())
    except HTTPError as error:
        return error.code, json.loads(error.read())

def rows():
    with database.connect(config) as conn:
        cursor = conn.cursor()
        try:
            cursor.execute(f"SELECT body FROM {table} WHERE device_id={placeholder}", (device,))
            return cursor.fetchall()
        finally:
            cursor.close()

if os.environ.get("SMS_TEST_PHASE") == "verify":
    assert len(rows()) == 1 and rows()[0][0] == message["body"], "data lost after container replacement"
else:
    assert post(payload, False)[0] == 401
    assert post(payload) == (200, {"accepted": [message_id]})
    assert post(payload) == (200, {"accepted": [message_id]})
    assert len(rows()) == 1 and rows()[0][0] == message["body"]
    conflict = dict(message, body="conflicting synthetic payload")
    extra = dict(message, message_id=str(uuid5(UUID(device), "rolled-back")))
    assert post({"device_id": device, "messages": [extra, conflict]})[0] == 409
    assert len(rows()) == 1, "conflicting batch was not fully rolled back"
with urlopen("http://127.0.0.1:18081/health", timeout=8) as response:
    assert response.status == 200
print("PASS:", engine, "persistence, Unicode, authentication, duplicate retry, conflict rollback" if not os.environ.get("SMS_TEST_PHASE") else "data preserved across API container replacement")
