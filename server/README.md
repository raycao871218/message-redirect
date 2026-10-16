# 咕咕咕归档服务

独立的 Python / FastAPI 服务，接收 Android App 的 HTTP 请求，支持 SQLite、PostgreSQL、MySQL 和 MariaDB。SMTP 转发由手机完成。

- `/`：蜡笔风管理面板，有珠颈斑鸠、数据库连接设置、密钥重置和 App 配对码。
- `/docs`、`/openapi.json`：公开的开发者文档，Swagger 资源随镜像提供，可离线打开。
- `POST /api/sms`：Bearer 密钥保护的事务入库接口。
- `GET /api/sms`：游标分页列表，可按设备、发送方、发送时间和已读状态筛选。
- `GET /api/sms/{device_id}/{message_id}`：单条短信详情。
- `PATCH /api/sms/{device_id}/{message_id}/read`：共享已读／未读状态，查询不自动标记。
- `/health`：数据库可用性检查。

管理面板由单独的 `SMS_ADMIN_PASSWORD` 或 `SMS_ADMIN_PASSWORD_FILE` 启用（至少 12 位）。通过 HttpOnly 会话和 CSRF 校验管理设置，短信访问密钥不能代替管理登录。配置及重置密钥原子保存到 `/data/settings.json`；请挂载持久化卷，使用默认单进程启动方式。未设置初始密码且无已保存密码哈希时，旧版写入接口仍能按原配置运行，面板只显示启用说明。

面板的「登录密码」卡片支持验证当前密码并设置新密码；修改后立即退出所有浏览器会话。新密码仅以加盐 PBKDF2 哈希原子保存到同一状态卷的 `admin-auth.json`（0600），优先于初始密码，重启后持续生效。不会修改 App/API 密钥或数据库设置。恢复方式见[部署说明](../docs/deployment.md)。

在本目录构建镜像：

```sh
docker build -t message-redirect:local .
```

在仓库根目录运行服务端测试：

```sh
python -m pip install -r server/requirements.txt httpx
python -m unittest discover -s server/tests -p 'test_*.py' -v
python scripts/test-containers.py
```

新部署使用 [`deploy/`](../deploy/) 下的 Compose 模板；环境变量、外部数据库和备份见[部署说明](../docs/deployment.md)。直接运行 Python 时建议显式设置 `SMS_CONFIG_FILE`；旧的仓库根目录 `.secrets/server.json` 仍兼容。

0.8.1 会为旧短信表增加可空的 `read_at` 列，原记录初始视为未读，不修改内容或去重哈希。所有客户端共享阅读状态，与手机收件箱无关；重复上传保留已读状态。升级前备份数据库，数据库账号需有该短信表的 ALTER 权限。
