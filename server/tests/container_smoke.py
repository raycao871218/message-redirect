"""Run inside an isolated API test container; uses synthetic messages only."""
import json
import os
from uuid import UUID, uuid5
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from config import load_config
from settings import SettingsStore
import database

initial = load_config()
config = SettingsStore(initial).snapshot()
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

def admin_smoke():
    import http.cookiejar
    from urllib.request import build_opener, HTTPCookieProcessor
    opener = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def admin(path, value=None, csrf=None):
        headers = {'Content-Type': 'application/json'}
        if csrf:
            headers['X-CSRF-Token'] = csrf
        body = json.dumps(value).encode() if value is not None else None
        with opener.open(Request('http://127.0.0.1:18081'+path, data=body, headers=headers), timeout=15) as response:
            return json.loads(response.read())
    csrf = admin('/admin/api/login', {'password': initial['management']['password']})['csrf']
    visible = admin('/admin/api/config')
    assert 'password' not in visible['database']
    assert visible['database']['type'] == engine
    admin('/admin/api/public-url', {'url': 'https://fixture.example.invalid'}, csrf)
    old = config['api_token']
    new = admin('/admin/api/token/reset', {}, csrf)['api_token']
    assert post(payload)[0] == 401, 'old key still accepted'
    config['api_token'] = new
    assert post(payload)[0] == 200
    pairing = admin('/admin/api/pairing')
    assert pairing['api_token'] == new and pairing['url'] == 'https://fixture.example.invalid'
    with opener.open('http://127.0.0.1:18081/admin/api/pairing.svg') as response:
        assert b'<svg' in response.read()
    assert old != new
    print('PASS:', engine, 'admin login, key rotation, QR generation and updated ingestion key')


def query_smoke(verify=False):
    def request(path, method='GET', value=None, token=None):
        headers = {'Authorization': 'Bearer '+(config['api_token'] if token is None else token)}
        if value is not None: headers['Content-Type'] = 'application/json'
        body = json.dumps(value).encode() if value is not None else None
        try:
            with urlopen(Request('http://127.0.0.1:18081'+path, data=body, headers=headers, method=method), timeout=20) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            return error.code, json.loads(error.read())
    path = '/api/sms/'+device+'/'+message_id
    if verify:
        status, value = request(path)
        assert status == 200 and value['is_read'] and isinstance(value['read_at'], int), 'shared read status lost on container replacement'
        print('PASS:', engine, 'shared read timestamp survived API container replacement')
        return
    # Recreate the legacy column shape in the isolated archive, retaining its real row/hash.
    with database.connect(config) as conn:
        cursor = conn.cursor()
        try: cursor.execute('ALTER TABLE '+table+' DROP COLUMN read_at')
        finally: cursor.close()
    database.initialize(config); database.initialize(config)
    status, value = request(path)
    assert status == 200 and value['body'] == message['body'] and not value['is_read'] and value['sent_at'] == message['sent_at']
    assert request(path, token='invalid')[0] == 401 and request('/api/sms', token='invalid')[0] == 401
    assert request(path+'/read', 'PATCH', {'is_read': True}, token='invalid')[0] == 401
    assert request('/api/sms/'+device+'/'+str(uuid5(UUID(device), 'missing')))[0] == 404
    status, first = request(path+'/read', 'PATCH', {'is_read': True})
    assert status == 200 and first['is_read'] and isinstance(first['read_at'], int)
    assert request(path+'/read', 'PATCH', {'is_read': True})[1]['read_at'] == first['read_at']
    assert post(payload)[0] == 200 and request(path)[1]['read_at'] == first['read_at']
    assert request('/api/sms?device_id='+device+'&is_read=true')[1]['items'][0]['message_id'] == message_id
    assert request('/api/sms?device_id='+device+'&is_read=false')[1]['items'] == []
    # Exercise SQL cursor comparison and filters on each real adapter without leaving extra rows.
    other_device = str(uuid5(UUID(device), 'pagination-fixture'))
    messages = [dict(message, message_id=str(uuid5(UUID(device), 'page-'+str(i))), sent_at=message['sent_at']+i) for i in range(3)]
    assert post({'device_id': other_device, 'messages': messages})[0] == 200
    from urllib.parse import urlencode
    page = request('/api/sms?'+urlencode({'device_id': other_device, 'limit': 1}))[1]
    found = [v['message_id'] for v in page['items']]
    while page['next_cursor']:
        page = request('/api/sms?'+urlencode({'device_id': other_device, 'limit': 1, 'cursor': page['next_cursor']}))[1]
        found.extend(v['message_id'] for v in page['items'])
    assert found == [v['message_id'] for v in reversed(messages)]
    with database.connect(config) as conn:
        cursor = conn.cursor()
        try: cursor.execute('DELETE FROM '+table+' WHERE device_id='+placeholder, (other_device,))
        finally: cursor.close()
    assert request(path+'/read', 'PATCH', {'is_read': False})[1]['read_at'] is None
    assert request(path+'/read', 'PATCH', {'is_read': True})[1]['is_read']
    print('PASS:', engine, 'legacy schema migration, list/detail authentication, cursor pagination, shared read/unread and duplicate retry preservation')


def password_smoke(verify=False):
    import http.cookiejar
    import pathlib
    import stat
    from urllib.request import build_opener, HTTPCookieProcessor
    password = 'fixture-updated-'+device
    old_password = initial['management']['password']
    opener = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def admin(path, value=None, csrf=None, client=opener):
        headers = {'Content-Type': 'application/json'}
        if csrf: headers['X-CSRF-Token'] = csrf
        body = json.dumps(value).encode() if value is not None else None
        try:
            with client.open(Request('http://127.0.0.1:18081'+path, data=body, headers=headers), timeout=20) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            return error.code, json.loads(error.read())
    if not verify:
        csrf = admin('/admin/api/login', {'password': old_password})[1]['csrf']
        other = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
        assert admin('/admin/api/login', {'password': old_password}, client=other)[0] == 200
        value = {'current_password': old_password, 'new_password': password, 'confirm_password': password}
        assert admin('/admin/api/password', value, csrf)[0] == 200
        assert admin('/admin/api/config')[0] == 401 and admin('/admin/api/config', client=other)[0] == 401
    assert admin('/admin/api/login', {'password': old_password})[0] == 401
    assert admin('/admin/api/login', {'password': password})[0] == 200
    assert admin('/admin/api/config')[1]['api_token'] == config['api_token']
    auth = pathlib.Path('/data/admin-auth.json')
    assert stat.S_IMODE(auth.stat().st_mode) == 0o600
    text = auth.read_text(); assert password not in text and old_password not in text
    print('PASS:', engine, 'changed admin password survives container replacement' if verify else 'admin password change, session revocation, private hashed credentials and unchanged API token')

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
    admin_smoke()
query_smoke(os.environ.get("SMS_TEST_PHASE") == "verify")
password_smoke(os.environ.get("SMS_TEST_PHASE") == "verify")
if os.environ.get("SMS_TEST_PHASE") == "verify":
    assert post(payload)[0] == 200, 'rotated access key lost on container replacement'
with urlopen('http://127.0.0.1:18081/openapi.json', timeout=8) as response:
    assert '/api/sms' in json.loads(response.read())['paths']
with urlopen('http://127.0.0.1:18081/docs', timeout=8) as response:
    assert b'/static/vendor/swagger-ui-bundle.js' in response.read()
with urlopen("http://127.0.0.1:18081/health", timeout=8) as response:
    assert response.status == 200
print("PASS:", engine, "persistence, Unicode, authentication, duplicate retry, conflict rollback" if not os.environ.get("SMS_TEST_PHASE") else "data preserved across API container replacement")
