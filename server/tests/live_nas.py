"""Execute inside the deployed container; test rows are removed by exact UUID."""
import json
from pathlib import Path
from uuid import uuid4
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from server import connect

config=json.loads(Path('/run/secrets/server.json').read_text())
device_id=str(uuid4())
message_id=str(uuid4())
payload={'device_id':device_id,'messages':[{'message_id':message_id,'sender':'MESSAGE_REDIRECT_TEST','body':"中文持久化验证 ' ; DROP TABLE --",'sent_at':1791972000000,'captured_at':1791972001000,'source':'integration-test'}]}

def post(data, authenticated=True):
    headers={'Content-Type':'application/json'}
    if authenticated: headers['Authorization']='Bearer '+config['api_token']
    try:
        with urlopen(Request('http://127.0.0.1:18081/api/sms',data=json.dumps(data).encode(),headers=headers),timeout=15) as r:
            return r.status,json.loads(r.read())
    except HTTPError as e:
        return e.code,json.loads(e.read())

try:
    assert post(payload,False)[0]==401
    assert post(payload)==(200,{'accepted':[message_id]})
    assert post(payload)==(200,{'accepted':[message_id]})
    with connect(config) as c:
        rows=c.execute('SELECT body,source FROM message_redirect.sms_messages WHERE device_id=%s',(device_id,)).fetchall()
        assert rows==[(payload['messages'][0]['body'],'integration-test')], rows
    conflict=dict(payload['messages'][0],body='different payload')
    rollback_id=str(uuid4())
    new_message=dict(payload['messages'][0],message_id=rollback_id)
    assert post({'device_id':device_id,'messages':[new_message,conflict]})[0]==409
    with connect(config) as c:
        assert c.execute('SELECT COUNT(*) FROM message_redirect.sms_messages WHERE device_id=%s',(device_id,)).fetchone()[0]==1
        print('PASS: authenticated API, PostgreSQL commit, Unicode, duplicate retry, conflicting-ID rollback')
        print('Database:',c.execute("SELECT current_database(),current_user,current_setting('server_encoding')").fetchone())
finally:
    with connect(config) as c:
        c.execute('DELETE FROM message_redirect.sms_messages WHERE device_id=%s',(device_id,))
