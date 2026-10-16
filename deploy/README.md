# Docker 部署

以下命令在本目录执行：

```sh
cp .env.example .env
python3 ../scripts/init-secrets.py
cp compose.example.yaml compose.yaml
docker compose up -d --build
```

默认模板使用 SQLite，其他模板可选择 PostgreSQL、MySQL、MariaDB 或已有数据库。构建上下文指向相邻的 `../server/`，不包含 Android 工程和本地私密文件。

实际配置保存在本目录的 `.env`、`.secrets/` 和 `compose.yaml`，均已忽略。已有部署继续保留自己的 Compose、网络、挂载和凭据；目录整理不要求修改正在运行的 NAS。

完整参数与备份方法见[部署说明](../docs/deployment.md)。
