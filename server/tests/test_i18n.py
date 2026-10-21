from copy import deepcopy
from html.parser import HTMLParser
import json
from pathlib import Path
import tempfile
import unittest
from fastapi.testclient import TestClient
from server.i18n import CATALOG, language, translate
from server.server import create_app


class LocalizationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.seed = {"database": {"type": "sqlite", "path": str(root / "messages.sqlite3")},
                     "api_token": "fixture-access-" + "a" * 32,
                     "management": {"password": "fixture-admin-password", "state_dir": str(root / "state")}}
        self.app = create_app(deepcopy(self.seed))
        self.client = TestClient(self.app)
        self.client.__enter__()

    def tearDown(self):
        self.client.__exit__(None, None, None)
        self.temp.cleanup()

    def test_language_negotiation_and_fallback(self):
        self.assertEqual("en", language("en-GB"))
        self.assertEqual("zh", language("zh-TW"))
        self.assertEqual("ja", language("en;q=0.2, ja-JP;q=0.9, zh;q=0.4"))
        self.assertEqual("en", language("fr-FR, de;q=0.8"))
        self.assertEqual("en", language("ja;q=0, zh;q=invalid"))
        self.assertEqual("en", language("../../private.json"))

    def test_catalog_completeness_and_markup_bindings(self):
        self.assertEqual({"en", "zh", "ja"}, set(CATALOG))
        for values in CATALOG.values():
            self.assertEqual(set(CATALOG["en"]), set(values))
            self.assertTrue(all(isinstance(value, str) and value.strip() for value in values.values()))
        keys = []
        class Bindings(HTMLParser):
            def handle_starttag(self, tag, attrs):
                keys.extend(value for name, value in attrs if name == "data-i18n" or name.startswith("data-i18n-"))
        for page in ["/", "/docs"]:
            response = self.client.get(page)
            self.assertEqual(200, response.status_code)
            self.assertIn('/static/i18n.js', response.text)
            self.assertIn('id="language-button"', response.text)
            Bindings().feed(response.text)
        self.assertTrue(keys)
        self.assertFalse(set(keys) - set(CATALOG["en"]))

    def test_schema_localization_is_isolated_and_preserves_contract(self):
        en = self.client.get('/openapi.json?lang=en').json()
        zh = self.client.get('/openapi.json?lang=zh-CN').json()
        ja = self.client.get('/openapi.json?lang=ja-JP').json()
        self.assertEqual('GuGuGu SMS Archive API', en['info']['title'])
        self.assertEqual('咕咕咕短信归档 API', zh['info']['title'])
        self.assertEqual('GuGuGu SMSアーカイブAPI', ja['info']['title'])
        for schema in [zh, ja]:
            self.assertEqual(set(en['paths']), set(schema['paths']))
            self.assertEqual(en['components']['schemas']['Message']['required'], schema['components']['schemas']['Message']['required'])
            self.assertEqual(en['paths']['/api/sms']['post']['security'], schema['paths']['/api/sms']['post']['security'])
            self.assertEqual(set(en['components']['schemas']['Message']['properties']), set(schema['components']['schemas']['Message']['properties']))
        self.assertEqual(en, self.client.get('/openapi.json?lang=en').json())
        self.assertEqual(ja, self.client.get('/openapi.json', headers={'Accept-Language': 'ja-JP'}).json())
        self.assertEqual(en, self.client.get('/openapi.json?lang=fr').json())
        self.assertEqual('咕咕咕短信归档 API', self.app.openapi()['info']['title'])
        self.assertNotIn(self.seed['api_token'], json.dumps(ja))

    def test_admin_errors_are_localized_without_changing_auth_or_exposing_input(self):
        for selected in ['en', 'ja', 'zh']:
            response = self.client.get('/admin/api/config', headers={'Accept-Language': selected})
            self.assertEqual(401, response.status_code)
            self.assertEqual(translate('请先登录管理面板', selected), response.json()['detail'])
            response = self.client.post('/admin/api/login', json={'password': 'wrong-private-value'}, headers={'Accept-Language': selected})
            self.assertEqual(401, response.status_code)
            self.assertEqual(translate('管理密码不正确', selected), response.json()['detail'])
            self.assertNotIn('wrong-private-value', response.text)
        self.assertEqual('请先登录管理面板', self.client.get('/admin/api/config').json()['detail'])
        self.assertEqual({'detail': 'unauthorized'}, self.client.get('/api/sms', headers={'Accept-Language': 'ja'}).json())

    def test_success_messages_translate_without_switching_database(self):
        response = self.client.post('/admin/api/login', json={'password': 'fixture-admin-password'})
        csrf = response.json()['csrf']
        snapshot = self.app.state.settings.snapshot()
        for selected in ['en', 'zh', 'ja']:
            response = self.client.post('/admin/api/database/test', json={'type': 'sqlite', 'path': self.seed['database']['path']},
                                        headers={'X-CSRF-Token': csrf, 'Accept-Language': selected})
            self.assertEqual(200, response.status_code)
            self.assertEqual(translate('连接成功；保存前不会切换当前数据库', selected), response.json()['message'])
            self.assertEqual(snapshot, self.app.state.settings.snapshot())
