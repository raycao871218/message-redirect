import concurrent.futures
from copy import deepcopy
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import AsyncMock, patch
from uuid import uuid4

from fastapi.testclient import TestClient
from server import database
from server.server import create_app


class AdminTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.seed = {"database": {"type": "sqlite", "path": str(self.root/'archive.sqlite3')},
                     "api_token": "fixture-access-"+'a'*32,
                     "management": {"password": "fixture-admin-password", "state_dir": str(self.root/'state')}}
        self.app = create_app(deepcopy(self.seed))
        self.client = TestClient(self.app)
        self.client.__enter__()
        self.payload = {"device_id": str(uuid4()), "messages": [{"message_id": str(uuid4()), "sender": "FIXTURE", "body": "synthetic", "sent_at": 1000, "captured_at": 2000, "source": "test"}]}
        self.csrf = ""

    def tearDown(self):
        self.client.__exit__(None, None, None)
        self.temp.cleanup()

    def login(self):
        response = self.client.post('/admin/api/login', json={"password": "fixture-admin-password"})
        self.assertEqual(200, response.status_code)
        self.csrf = response.json()['csrf']
        self.assertIn('HttpOnly', response.headers['set-cookie'])
        self.assertIn('SameSite=strict', response.headers['set-cookie'])
        return response

    def post(self, path, data):
        return self.client.post(path, json=data, headers={"X-CSRF-Token": self.csrf})

    def sms(self, token):
        return self.client.post('/api/sms', json=self.payload, headers={"Authorization": 'Bearer '+token})

    def test_swagger_is_local_public_and_describes_complete_ingest_contract(self):
        schema = self.client.get('/openapi.json').json()
        self.assertEqual({'/health', '/api/sms', '/api/sms/{device_id}/{message_id}', '/api/sms/{device_id}/{message_id}/read'}, set(schema['paths']))
        operation = schema['paths']['/api/sms']['post']
        self.assertEqual([{'SmsAccessKey': []}], operation['security'])
        self.assertEqual('bearer', schema['components']['securitySchemes']['SmsAccessKey']['scheme'])
        self.assertIn('Batch', schema['components']['schemas'])
        self.assertIn('Message', schema['components']['schemas'])
        self.assertEqual('#/components/schemas/Batch', operation['requestBody']['content']['application/json']['schema']['$ref'])
        self.assertTrue({'200', '401', '409', '413', '422', '503'}.issubset(operation['responses']))
        text = self.client.get('/docs').text
        self.assertIn('/static/vendor/swagger-ui-bundle.js', text)
        self.assertNotIn('https://cdn', text)
        self.assertEqual(200, self.client.get('/static/vendor/swagger-ui-bundle.js').status_code)
        self.assertNotIn(self.seed['api_token'], json.dumps(schema))

    def test_admin_requires_separate_login_and_csrf(self):
        self.assertEqual(401, self.client.get('/admin/api/config', headers={'Authorization': 'Bearer '+self.seed['api_token']}).status_code)
        self.assertEqual(401, self.client.get('/admin/api/pairing.svg').status_code)
        response = self.client.post('/admin/api/login', json={'password': 'wrong-secret'})
        self.assertEqual(401, response.status_code)
        self.assertNotIn('wrong-secret', response.text)
        self.login()
        self.assertEqual(403, self.client.post('/admin/api/token/reset', json={}).status_code)
        self.assertEqual(403, self.client.post('/admin/api/token/reset', json={}, headers={'X-CSRF-Token': self.csrf, 'Origin': 'https://other.invalid'}).status_code)
        self.assertEqual(403, self.client.post('/admin/api/login', json={'password': 'fixture-admin-password'}, headers={'Origin': 'https://other.invalid'}).status_code)
        self.assertEqual('no-store', self.client.get('/admin/api/config').headers['cache-control'])
        self.assertIn("frame-ancestors 'none'", self.client.get('/').headers['content-security-policy'])

    def test_key_rotation_updates_ingest_qr_and_survives_restart(self):
        self.login()
        self.assertEqual(409, self.client.get('/admin/api/pairing').status_code)
        self.assertEqual(200, self.post('/admin/api/public-url', {'url': 'https://sms.example.invalid/'}).status_code)
        old = self.seed['api_token']
        self.assertEqual(200, self.sms(old).status_code)
        new = self.post('/admin/api/token/reset', {}).json()['api_token']
        self.assertNotEqual(old, new)
        self.assertEqual(401, self.sms(old).status_code)
        self.assertEqual(200, self.sms(new).status_code)
        pairing = self.client.get('/admin/api/pairing').json()
        self.assertEqual({'type': 'gugugu.server', 'version': 1, 'name': '咕咕咕服务', 'url': 'https://sms.example.invalid', 'api_token': new}, pairing)
        qr = self.client.get('/admin/api/pairing.svg')
        self.assertEqual(200, qr.status_code)
        self.assertIn('image/svg+xml', qr.headers['content-type'])
        self.assertEqual('no-store', qr.headers['cache-control'])
        self.assertIn('<svg', qr.text)
        with TestClient(create_app(deepcopy(self.seed))) as restarted:
            response = restarted.post('/api/sms', json=self.payload, headers={'Authorization': 'Bearer '+old})
            self.assertEqual(401, response.status_code)
            self.assertEqual(200, restarted.post('/api/sms', json=self.payload, headers={'Authorization': 'Bearer '+new}).status_code)
        state = json.loads((self.root/'state/settings.json').read_text())
        self.assertEqual(new, state['api_token'])
        self.assertNotIn('fixture-admin-password', json.dumps(state))

    def test_database_switch_is_atomic_and_retains_old_archive(self):
        self.login(); self.assertEqual(200, self.sms(self.seed['api_token']).status_code)
        target = str(self.root/'new.sqlite3')
        original = self.client.get('/admin/api/config').json()['database']
        test = self.post('/admin/api/database/test', {'type': 'sqlite', 'path': target})
        self.assertEqual(200, test.status_code)
        self.assertFalse(Path(target).exists())
        self.assertEqual(original, self.client.get('/admin/api/config').json()['database'])
        response = self.post('/admin/api/database', {'type': 'sqlite', 'path': target})
        self.assertEqual(200, response.status_code)
        self.assertEqual(200, self.sms(self.seed['api_token']).status_code)
        for path in [self.seed['database']['path'], target]:
            with database.connect({'database': {'type': 'sqlite', 'path': path}}) as connection:
                self.assertEqual(1, connection.execute('SELECT count(*) FROM sms_messages').fetchone()[0])
        with TestClient(create_app(deepcopy(self.seed))) as restarted:
            self.assertEqual(target, restarted.app.state.settings.snapshot()['database']['path'])

    def test_failed_save_and_failed_key_persistence_preserve_live_configuration(self):
        self.login()
        before = self.app.state.settings.snapshot()
        with patch('server.admin.database.initialize', side_effect=OSError('private failure')):
            response = self.post('/admin/api/database', {'type': 'sqlite', 'path': str(self.root/'unusable.db')})
        self.assertEqual(400, response.status_code)
        self.assertNotIn('private failure', response.text)
        self.assertEqual(before, self.app.state.settings.snapshot())
        with patch.object(self.app.state.settings, 'persist', side_effect=OSError('private storage failure')):
            self.assertEqual(503, self.post('/admin/api/token/reset', {}).status_code)
        self.assertEqual(200, self.sms(before['api_token']).status_code)

    def test_password_is_redacted_and_blank_only_keeps_same_target(self):
        self.login()
        state = self.app.state.settings
        with state.operation() as current:
            changed = dict(current, database={'type': 'postgresql', 'host': 'db.invalid', 'port': 5432, 'dbname': 'fixture', 'user': 'fixture', 'password': 'never-show-this', 'sslmode': 'require'})
            state.replace(changed)
        response = self.client.get('/admin/api/config')
        self.assertNotIn('never-show-this', response.text)
        self.assertTrue(response.json()['database']['password_saved'])
        same = {key: value for key, value in changed['database'].items() if key != 'password'}
        with patch('server.admin.database.initialize') as initialize:
            self.assertEqual(200, self.post('/admin/api/database', same).status_code)
            self.assertEqual('never-show-this', initialize.call_args[0][0]['database']['password'])
        with patch('server.admin.database.initialize') as initialize:
            self.assertEqual(422, self.post('/admin/api/database', dict(same, host='other.invalid')).status_code)
            initialize.assert_not_called()
        with patch('server.admin.database.initialize') as initialize:
            self.assertEqual(200, self.post('/admin/api/database', dict(same, host='other.invalid', password='replacement-secret')).status_code)
            self.assertEqual('require', initialize.call_args[0][0]['database']['sslmode'])

    def test_broken_database_does_not_prevent_login_and_repair(self):
        seed = deepcopy(self.seed); seed['management']['state_dir'] = str(self.root/'broken-state')
        with patch('server.server.initialize', side_effect=OSError('private bad address')), patch('server.server.asyncio.sleep', new_callable=AsyncMock):
            app = create_app(seed)
            with TestClient(app) as client:
                self.assertEqual(503, client.get('/health').status_code)
                self.assertEqual(200, client.get('/').status_code)
                login = client.post('/admin/api/login', json={'password': 'fixture-admin-password'})
                self.assertEqual(200, login.status_code)
                headers = {'X-CSRF-Token': login.json()['csrf']}
                self.assertEqual(200, client.post('/admin/api/database', json={'type': 'sqlite', 'path': str(self.root/'repaired.db')}, headers=headers).status_code)
                self.assertEqual(200, client.get('/health').status_code)

    def test_logout_expiry_login_rate_limit_and_invalid_input(self):
        self.login()
        self.assertEqual(422, self.post('/admin/api/database', {'type': 'mysql', 'host': 'missing', 'password': 'DO-NOT-ECHO', 'port': True}).status_code)
        for address in ['javascript:alert(1)', 'https://user:pass@example.invalid', 'https://example.invalid/#secret', 'https://example.invalid/?token=secret', 'https://example.invalid:70000']:
            self.assertEqual(422, self.post('/admin/api/public-url', {'url': address}).status_code)
        self.assertEqual(200, self.post('/admin/api/logout', {}).status_code)
        self.assertEqual(401, self.client.get('/admin/api/config').status_code)
        for _ in range(10):
            self.assertEqual(401, self.client.post('/admin/api/login', json={'password': 'invalid'}).status_code)
        self.assertEqual(429, self.client.post('/admin/api/login', json={'password': 'fixture-admin-password'}).status_code)

    def test_first_admin_start_generates_a_persistent_app_key(self):
        seed = deepcopy(self.seed); seed['api_token'] = ''; seed['management']['state_dir'] = str(self.root/'new-state')
        with TestClient(create_app(seed)) as client:
            first = client.app.state.settings.snapshot()['api_token']
            self.assertGreaterEqual(len(first), 32)
        with TestClient(create_app(seed)) as client:
            self.assertEqual(first, client.app.state.settings.snapshot()['api_token'])

    def test_password_change_revokes_all_sessions_and_preserves_api_configuration(self):
        self.login()
        before = self.app.state.settings.snapshot()
        with TestClient(self.app) as other:
            self.assertEqual(200, other.post('/admin/api/login', json={'password': 'fixture-admin-password'}).status_code)
            response = self.post('/admin/api/password', {'current_password': 'fixture-admin-password', 'new_password': '新密码-fixture-12345', 'confirm_password': '新密码-fixture-12345'})
            self.assertEqual(200, response.status_code)
            self.assertIn('Max-Age=0', response.headers['set-cookie'])
            self.assertEqual(401, other.get('/admin/api/config').status_code)
        self.assertEqual(401, self.client.get('/admin/api/config').status_code)
        self.assertEqual({}, self.app.state.settings.sessions)
        self.assertEqual(before, self.app.state.settings.snapshot())
        self.assertEqual(200, self.sms(before['api_token']).status_code)
        self.assertEqual(401, self.client.post('/admin/api/login', json={'password': 'fixture-admin-password'}).status_code)
        self.assertEqual(200, self.client.post('/admin/api/login', json={'password': '新密码-fixture-12345'}).status_code)
        text = (self.root/'state/admin-auth.json').read_text()
        self.assertNotIn('新密码', text); self.assertNotIn('fixture-admin-password', text)
        self.assertEqual('pbkdf2_sha256', json.loads(text)['algorithm'])
        self.assertNotIn('hash', self.client.get('/admin/api/config').json())

    def test_changed_password_survives_restart_and_bootstrap_secret_removal(self):
        self.login()
        value = {'current_password': 'fixture-admin-password', 'new_password': 'replacement-fixture-secret', 'confirm_password': 'replacement-fixture-secret'}
        self.assertEqual(200, self.post('/admin/api/password', value).status_code)
        for seed_password in ['fixture-admin-password', '', 'another-bootstrap-password']:
            seed = deepcopy(self.seed); seed['management']['password'] = seed_password
            with TestClient(create_app(seed)) as client:
                self.assertTrue(client.get('/admin/api/session').json()['configured'])
                self.assertEqual(401, client.post('/admin/api/login', json={'password': 'fixture-admin-password'}).status_code)
                self.assertEqual(200, client.post('/admin/api/login', json={'password': value['new_password']}).status_code)
                self.assertEqual(200, client.post('/api/sms', json=self.payload, headers={'Authorization': 'Bearer '+seed['api_token']}).status_code)
        # Writing ordinary settings never replaces the independently persisted password hash.
        login = self.client.post('/admin/api/login', json={'password': value['new_password']})
        self.csrf = login.json()['csrf']; original = (self.root/'state/admin-auth.json').read_bytes()
        self.assertEqual(200, self.post('/admin/api/public-url', {'url': 'https://fixture.example.invalid'}).status_code)
        self.assertEqual(original, (self.root/'state/admin-auth.json').read_bytes())

    def test_password_change_requires_login_current_password_csrf_and_matching_new_password(self):
        value = {'current_password': 'fixture-admin-password', 'new_password': 'replacement-fixture-secret', 'confirm_password': 'replacement-fixture-secret'}
        self.assertEqual(401, self.post('/admin/api/password', value).status_code)
        self.login()
        self.assertEqual(403, self.client.post('/admin/api/password', json=value).status_code)
        self.assertEqual(403, self.client.post('/admin/api/password', json=value, headers={'X-CSRF-Token': self.csrf, 'Origin': 'https://other.invalid'}).status_code)
        for changed in [dict(value, new_password='short', confirm_password='short'), dict(value, confirm_password='mismatched-fixture-secret'),
                        dict(value, new_password=' '*12, confirm_password=' '*12), dict(value, new_password='fixture-admin-password', confirm_password='fixture-admin-password'),
                        dict(value, new_password='bad\nfixture-password', confirm_password='bad\nfixture-password'), dict(value, new_password=123), dict(value, unexpected='DO-NOT-ECHO')]:
            response = self.post('/admin/api/password', changed)
            self.assertEqual(422, response.status_code); self.assertNotIn('DO-NOT-ECHO', response.text)
            self.assertNotIn(value['new_password'], response.text)
        response = self.post('/admin/api/password', dict(value, current_password='wrong-fixture-current-password'))
        self.assertEqual(400, response.status_code); self.assertNotIn('wrong-fixture', response.text)
        response = self.post('/admin/api/password', dict(value, current_password='wrong-fixture-current-password', new_password='🍠'*1024, confirm_password='🍠'*1024))
        self.assertEqual(400, response.status_code)
        self.assertEqual(200, self.client.get('/admin/api/config').status_code)
        self.assertFalse((self.root/'state/admin-auth.json').exists())

    def test_failed_password_persistence_keeps_original_password_and_session(self):
        self.login()
        value = {'current_password': 'fixture-admin-password', 'new_password': 'replacement-fixture-secret', 'confirm_password': 'replacement-fixture-secret'}
        with patch.object(self.app.state.settings, 'persist_password', side_effect=OSError('PRIVATE-DISK-ERROR')):
            response = self.post('/admin/api/password', value)
        self.assertEqual(503, response.status_code); self.assertNotIn('PRIVATE-DISK-ERROR', response.text)
        self.assertEqual(200, self.client.get('/admin/api/config').status_code)
        self.assertEqual(401, self.client.post('/admin/api/login', json={'password': value['new_password']}).status_code)
        self.assertEqual(200, self.client.post('/admin/api/login', json={'password': value['current_password']}).status_code)
        self.assertFalse((self.root/'state/admin-auth.json').exists())
        self.csrf = self.client.post('/admin/api/login', json={'password': value['current_password']}).json()['csrf']
        self.assertEqual(200, self.post('/admin/api/password', value).status_code)
        self.csrf = self.client.post('/admin/api/login', json={'password': value['new_password']}).json()['csrf']
        auth = self.root/'state/admin-auth.json'; original = auth.read_bytes()
        replacement = dict(value, current_password=value['new_password'], new_password='another-fixture-password', confirm_password='another-fixture-password')
        with patch('server.settings.os.replace', side_effect=OSError('PRIVATE-RENAME-FAILURE')):
            response = self.post('/admin/api/password', replacement)
        self.assertEqual(503, response.status_code); self.assertNotIn('PRIVATE-RENAME-FAILURE', response.text)
        self.assertEqual(original, auth.read_bytes()); self.assertEqual([], list(auth.parent.glob('.admin-auth-*')))
        self.assertEqual(200, self.client.get('/admin/api/config').status_code)
        self.assertEqual(200, self.client.post('/admin/api/login', json={'password': value['new_password']}).status_code)

    def test_password_change_wrong_current_password_is_rate_limited(self):
        self.login()
        value = {'current_password': 'wrong-fixture-secret', 'new_password': 'replacement-fixture-secret', 'confirm_password': 'replacement-fixture-secret'}
        for _ in range(10):
            self.assertEqual(400, self.post('/admin/api/password', value).status_code)
        response = self.post('/admin/api/password', dict(value, current_password='fixture-admin-password'))
        self.assertEqual(429, response.status_code); self.assertEqual('300', response.headers['retry-after'])
        self.assertFalse((self.root/'state/admin-auth.json').exists())

    def test_corrupt_saved_auth_never_falls_back_to_old_bootstrap_password(self):
        self.login()
        value = {'current_password': 'fixture-admin-password', 'new_password': 'replacement-fixture-secret', 'confirm_password': 'replacement-fixture-secret'}
        self.assertEqual(200, self.post('/admin/api/password', value).status_code)
        auth = self.root/'state/admin-auth.json'; original = json.loads(auth.read_text())
        for changed in [dict(original, hash='broken'), dict(original, iterations=999999999), dict(original, algorithm='unsupported'), {'hash': 'broken'}]:
            auth.write_text(json.dumps(changed))
            with self.assertRaisesRegex(ValueError, 'Cannot read private admin-auth.json'):
                create_app(deepcopy(self.seed))
        auth.write_text(json.dumps(original))

    def test_disabling_admin_does_not_resurrect_old_key(self):
        self.login()
        token = self.post('/admin/api/token/reset', {}).json()['api_token']
        seed = deepcopy(self.seed); seed['management']['password'] = ''
        with TestClient(create_app(seed)) as client:
            self.assertFalse(client.get('/admin/api/session').json()['configured'])
            self.assertEqual(401, client.post('/api/sms', json=self.payload, headers={'Authorization': 'Bearer '+self.seed['api_token']}).status_code)
            self.assertEqual(200, client.post('/api/sms', json=self.payload, headers={'Authorization': 'Bearer '+token}).status_code)

    def test_expired_admin_session_cannot_read_credentials(self):
        self.login()
        expires = next(iter(self.app.state.settings.sessions.values()))['expires']
        with patch('server.settings.time.monotonic', return_value=expires + 1):
            self.assertEqual(401, self.client.get('/admin/api/config').status_code)
            self.assertEqual(401, self.client.get('/admin/api/pairing.svg').status_code)

    def test_database_change_waits_for_in_flight_write_to_commit(self):
        self.login()
        state = self.app.state.settings
        entered, release = threading.Event(), threading.Event()
        def old_write():
            with state.operation() as current:
                entered.set(); release.wait(3)
                database.ingest(current, __import__('server.server', fromlist=['Batch']).Batch.model_validate(self.payload))
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            active = executor.submit(old_write); self.assertTrue(entered.wait(1))
            save = executor.submit(self.post, '/admin/api/database', {'type': 'sqlite', 'path': str(self.root/'after.db')})
            self.assertFalse(save.done()); release.set(); active.result()
            self.assertEqual(200, save.result().status_code)
        with database.connect(self.seed) as connection:
            self.assertEqual(1, connection.execute('SELECT count(*) FROM sms_messages').fetchone()[0])


if __name__ == '__main__':
    unittest.main()
