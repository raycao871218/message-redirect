# 咕咕咕写入服务

独立的 Python / FastAPI 服务，接收 Android App 的 HTTP 请求，支持 SQLite、PostgreSQL、MySQL 和 MariaDB。SMTP 转发由手机完成。

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
