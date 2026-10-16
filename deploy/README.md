# Docker 部署

以下命令在本目录执行：

```sh
cp .env.example .env
python3 ../scripts/init-secrets.py
cp compose.example.yaml compose.yaml
docker compose up -d --build
```

默认模板使用 SQLite，其他模板可选择 PostgreSQL、MySQL、MariaDB 或已有数据库。构建上下文指向相邻的 `../server/`，不包含 Android 工程和本地私密文件。

启动后浏览器打开服务地址，使用本目录 `.secrets/admin-password` 内的密码登录管理面板。面板可以测试/保存数据库连接、重置访问密钥、生成供 App 扫描的配置二维码。Swagger 位于 `/docs`，无需登录管理面板；调用写入接口仍需访问密钥。

所有模板都挂载 `/data` 状态卷，保存面板设置和重置后的密钥。SQLite 模板还在此卷保存短信；其他模板的短信留在各自数据库卷或外部数据库。不要删除状态卷，否则面板保存的配置会丢失。

实际配置保存在本目录的 `.env`、`.secrets/` 和 `compose.yaml`，均已忽略。已有部署继续保留自己的 Compose、网络、挂载和凭据；目录整理不要求修改正在运行的 NAS。

完整参数与备份方法见[部署说明](../docs/deployment.md)。
