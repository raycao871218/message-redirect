"""Real SQLite integration checks for shared archive reading and legacy upgrades."""
from copy import deepcopy
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from uuid import UUID, uuid4

from fastapi.testclient import TestClient
from server import database
from server.server import create_app


class QueryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.config = {"database": {"type": "sqlite", "path": str(Path(self.temp.name)/'messages.sqlite3')},
                       "api_token": 'fixture-access-'+'a'*32}
        self.client = TestClient(create_app(deepcopy(self.config)))
        self.client.__enter__()
        self.headers = {"Authorization": 'Bearer '+self.config['api_token']}
        self.device = str(UUID(int=1))
        self.message = {"message_id": str(UUID(int=1)), "sender": "FIXTURE", "body": "合成短信 🍠 ' ; DROP TABLE --", "sent_at": 1001, "captured_at": 2002, "source": "fixture"}

    def tearDown(self):
        self.client.__exit__(None, None, None)
        self.temp.cleanup()

    def upload(self, message=None, device=None):
        response = self.client.post('/api/sms', headers=self.headers, json={"device_id": device or self.device, "messages": [message or self.message]})
        self.assertEqual(200, response.status_code)
        return response

    def detail(self, device=None, message=None):
        return f"/api/sms/{device or self.device}/{message or self.message['message_id']}"

    def test_all_query_and_read_state_routes_require_bearer(self):
        self.upload()
        for headers in ({}, {"Authorization": 'Bearer invalid'}):
            for method, path, kwargs in [('get', '/api/sms', {}), ('get', self.detail(), {}), ('patch', self.detail()+'/read', {'json': {'is_read': True}})]:
                with self.subTest(method=method, path=path):
                    response = getattr(self.client, method)(path, headers=headers, **kwargs)
                    self.assertEqual(401, response.status_code)
                    self.assertNotIn(self.message['body'], response.text)

    def test_detail_is_complete_and_queries_do_not_mark_read(self):
        self.upload()
        self.assertEqual(1, len(self.client.get('/api/sms', headers=self.headers).json()['items']))
        response = self.client.get(self.detail(), headers=self.headers)
        self.assertEqual(200, response.status_code)
        record = response.json()
        self.assertEqual({**self.message, 'device_id': self.device}, {key: record[key] for key in [*self.message, 'device_id']})
        self.assertIsInstance(record['received_at'], int)
        self.assertFalse(record['is_read']); self.assertIsNone(record['read_at'])
        self.assertNotIn('payload_hash', record)
        self.assertEqual('no-store', response.headers['cache-control'])
        self.assertEqual(404, self.client.get(self.detail(device=str(uuid4())), headers=self.headers).status_code)
        self.assertEqual(404, self.client.patch(self.detail(message=str(uuid4()))+'/read', headers=self.headers, json={'is_read': True}).status_code)

    def test_shared_read_state_is_idempotent_and_survives_duplicate_upload_and_restart(self):
        self.upload()
        first = self.client.patch(self.detail()+'/read', headers=self.headers, json={'is_read': True}).json()
        self.assertTrue(first['is_read']); self.assertIsInstance(first['read_at'], int)
        second = self.client.patch(self.detail()+'/read', headers=self.headers, json={'is_read': True}).json()
        self.assertEqual(first['read_at'], second['read_at'])
        self.upload()
        self.assertEqual(first, self.client.get(self.detail(), headers=self.headers).json())
        with TestClient(create_app(deepcopy(self.config))) as other_client:
            self.assertEqual(first, other_client.get(self.detail(), headers=self.headers).json())
        response = self.client.patch(self.detail()+'/read', headers=self.headers, json={'is_read': False})
        self.assertEqual(200, response.status_code)
        self.assertFalse(response.json()['is_read']); self.assertIsNone(response.json()['read_at'])
        unread = self.client.get('/api/sms', params={'is_read': 'false'}, headers=self.headers).json()
        self.assertEqual(1, len(unread['items']))
        self.assertEqual([], self.client.get('/api/sms', params={'is_read': 'true'}, headers=self.headers).json()['items'])

    def test_cursor_pagination_with_ties_does_not_skip_or_repeat_on_new_arrival(self):
        expected = set()
        for device in [str(UUID(int=1)), str(UUID(int=2))]:
            for number in range(1, 5):
                message = dict(self.message, message_id=str(UUID(int=number)), sent_at=1001 if number<4 else 3000)
                self.upload(message, device); expected.add((device, message['message_id']))
        page = self.client.get('/api/sms', params={'limit': 3}, headers=self.headers).json()
        found = [(v['device_id'], v['message_id']) for v in page['items']]
        self.assertIsNotNone(page['next_cursor'])
        self.upload(dict(self.message, message_id=str(uuid4()), sent_at=5000))
        while page['next_cursor']:
            page = self.client.get('/api/sms', params={'limit': 2, 'cursor': page['next_cursor']}, headers=self.headers).json()
            found.extend((v['device_id'], v['message_id']) for v in page['items'])
        self.assertEqual(expected, set(found)); self.assertEqual(len(expected), len(found))

    def test_filters_cursor_validation_and_composite_identity(self):
        self.upload()
        second = dict(self.message, sender="OTHER", sent_at=3000)
        self.upload(second, str(UUID(int=2)))
        self.assertEqual('OTHER', self.client.get(self.detail(device=str(UUID(int=2))), headers=self.headers).json()['sender'])
        self.client.patch(self.detail()+'/read', headers=self.headers, json={'is_read': True})
        for params in [{'device_id': self.device}, {'sender': 'FIXTURE'}, {'is_read': 'true'}, {'sent_from': 1001, 'sent_until': 1001}]:
            data = self.client.get('/api/sms', headers=self.headers, params=params).json()['items']
            self.assertEqual([self.device], [v['device_id'] for v in data])
        self.assertEqual([], self.client.get('/api/sms', headers=self.headers, params={'sender': "' OR 1=1 --"}).json()['items'])
        cursor = self.client.get('/api/sms', headers=self.headers, params={'limit': 1}).json()['next_cursor']
        self.assertEqual(400, self.client.get('/api/sms', headers=self.headers, params={'cursor': cursor, 'sender': 'FIXTURE'}).status_code)
        for value in ['!!!', 'W10', 'bnVsbA', 'e30']:
            self.assertEqual(400, self.client.get('/api/sms', headers=self.headers, params={'cursor': value}).status_code)
        for params in [{'limit': 0}, {'limit': 101}, {'sent_from': -1}, {'sent_from': 2, 'sent_until': 1}, {'device_id': 'bad-id'}]:
            self.assertEqual(422, self.client.get('/api/sms', headers=self.headers, params=params).status_code)
        for value in [{'is_read': 'true'}, {'is_read': 1}, {'is_read': True, 'extra': 'DO-NOT-ECHO'}, {}]:
            response = self.client.patch(self.detail()+'/read', headers=self.headers, json=value)
            self.assertEqual(422, response.status_code); self.assertNotIn('DO-NOT-ECHO', response.text)

    def test_database_outage_does_not_leak_private_details(self):
        self.client.app.state.settings.current['database']['path'] = str(Path(self.temp.name)/'private-missing'/'archive.sqlite3')
        for method, path, kwargs in [('get', '/api/sms', {}), ('get', self.detail(), {}), ('patch', self.detail()+'/read', {'json': {'is_read': True}})]:
            response = getattr(self.client, method)(path, headers=self.headers, **kwargs)
            self.assertEqual(503, response.status_code); self.assertNotIn('private-missing', response.text)

    def test_schema_upgrade_preserves_legacy_rows_and_hash(self):
        path = str(Path(self.temp.name)/'legacy.sqlite3')
        legacy = (Path(__file__).parents[1]/'schema_sqlite.sql').read_text().replace('    read_at TEXT NULL,\n', '')
        with sqlite3.connect(path) as db:
            db.executescript(legacy)
            db.execute("INSERT INTO sms_messages(device_id,message_id,sender,body,sent_at,captured_at,source,payload_hash) VALUES(?,?,?,?,?,?,?,?)",
                       (self.device, self.message['message_id'], 'FIXTURE', 'legacy body', '1970-01-01T00:00:01.001000+00:00', '1970-01-01T00:00:02.002000+00:00', 'fixture', 'legacy-hash'))
        db.close()
        config = dict(self.config, database={'type': 'sqlite', 'path': path})
        for _ in range(2): database.initialize(config)
        self.assertFalse(database.get_message(config, self.device, self.message['message_id'])['is_read'])
        with sqlite3.connect(path) as db:
            self.assertEqual([('legacy body', 'legacy-hash', None)], db.execute('SELECT body,payload_hash,read_at FROM sms_messages').fetchall())
        db.close()


if __name__ == '__main__':
    unittest.main()
