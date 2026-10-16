# 自托管部署

以下部署命令均在仓库的 `deploy/` 目录执行（先运行 `cd deploy`）。该目录内的 `.env`、`.secrets/` 和 `compose.yaml` 仅保存在本地。

## 内置数据库或已有数据库

默认 `compose.example.yaml` 使用 SQLite，运行服务即可，不需要独立数据库容器。数据在 `/data/messages.sqlite3`，应备份整个数据卷（包括 WAL）；最简单的备份方式是停止服务后备份卷。

选择 PostgreSQL / MySQL / MariaDB 模板时，数据库账号、数据库名由 Compose 的环境参数设置；随机密码由 `../scripts/init-secrets.py` 生成并以文件挂载。容器初始化账号只在空数据卷第一次启动时创建，之后改变秘密文件不会自动修改数据库中的密码。

数据库名称或账号改变时，应先迁移/授权后再调整连接配置。更换数据库引擎仅改变新短信的写入位置，不会自动搬迁旧记录。

## 连接已有数据库

```sh
cp compose.external.example.yaml compose.yaml
mkdir -p .secrets
cp ../server/config.example.json .secrets/server.json
```

编辑本地 `server.json`，例如 MySQL / MariaDB：

```json
{
  "database": {
    "type": "mysql",
    "host": "database.example.internal",
    "port": 3306,
    "dbname": "sms_archive",
    "user": "sms_ingest",
    "password": "填写自己的数据库密码"
  },
  "api_token": "填写至少32字符的随机访问令牌",
  "port": 18081
}
```

PostgreSQL 使用 `type: postgresql`，端口一般为 5432；MariaDB 使用 `type: mariadb`，端口一般为 3306。主机可以是数据库容器同网络内的服务名、宿主机的 `host.docker.internal`，或可访问的数据库地址。不要用容器内的 `localhost` 指代宿主机。

数据库须事先建立，账号需要在指定数据库中创建表/索引及读写的权限；PostgreSQL 还需要创建或访问 `message_redirect` schema 的权限。PostgreSQL 继续使用 `message_redirect.sms_messages`；其他引擎使用所选数据库中的 `sms_messages`。固定表结构由服务创建，账号、主机、库名和凭据由用户配置。

PostgreSQL 可在 `database` 内加入 `sslmode`、`sslrootcert` 等 Psycopg/libpq 参数。MySQL/MariaDB 支持 `ssl_ca`、`ssl_cert`、`ssl_key`、`ssl_verify_cert`、`ssl_verify_identity`；证书应额外挂载只读路径，并启用证书与主机名校验。

Linux 下可将 `server.json` 权限设为 0600，并在本地 `.env` 设置 `SMS_UID`、`SMS_GID`，使外部数据库模板中的进程账号能读取文件。现有 NAS 的这些值、固定地址和子网应保留，不要照抄另一台机器的配置。

## 环境变量

服务接受 JSON 或下列变量，明确设置的环境变量覆盖 JSON。`*_FILE` 优先于同名明文变量，内容只去除文件末尾换行，不输出到日志。

| 变量 | 用途 / 默认值 |
| --- | --- |
| `SMS_CONFIG_FILE` | 指定 JSON 路径；指定后找不到会报错，不会切到其他数据库 |
| `SMS_DB_TYPE` | `sqlite` / `postgresql` / `mysql` / `mariadb` |
| `SMS_DB_PATH` | SQLite 文件，默认 `/data/messages.sqlite3`，必须持久化 |
| `SMS_DB_HOST` / `SMS_DB_PORT` | 数据库主机、端口 |
| `SMS_DB_NAME` / `SMS_DB_USER` | 数据库名、账号 |
| `SMS_DB_PASSWORD` / `SMS_DB_PASSWORD_FILE` | 数据库密码或密码文件 |
| `SMS_DB_SSLMODE` | PostgreSQL SSL 模式 |
| `SMS_API_TOKEN` / `SMS_API_TOKEN_FILE` | 至少 32 字符的访问令牌或令牌文件 |
| `SMS_PORT` | 容器内 HTTP 端口，默认 18081；修改后需同步端口映射与健康检查 |

不显式指定 JSON 时，会兼容已有 `/run/secrets/server.json`，再尝试项目本地 `.secrets/server.json`。旧 JSON 中的 `database.type` 缺省时仍按 PostgreSQL 处理。新模板未挂载这些 JSON，默认用环境变量选择的数据库。

Compose 的 `SMS_HTTP_PORT`、`SMS_BIND_ADDRESS` 控制宿主机端口与绑定地址；`SMS_UID` / `SMS_GID` 仅用于外部数据库模板，不是数据库连接凭据。

## 单独运行 Docker 镜像

当前仓库提供源码构建，不要求已经存在的公开预构建镜像：

```sh
docker build -t message-redirect:local ../server
python3 ../scripts/init-secrets.py
docker volume create sms-data
docker run -d --name message-redirect --restart unless-stopped \
  -p 127.0.0.1:18081:18081 \
  --mount type=volume,src=sms-data,dst=/data \
  --mount type=bind,src="$(pwd)/.secrets/api-token",dst=/run/secrets/api-token,readonly \
  -e SMS_DB_TYPE=sqlite -e SMS_API_TOKEN_FILE=/run/secrets/api-token \
  --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges:true \
  message-redirect:local
```

通过环境变量可改为外部数据库。服务内置 `/health`；只有数据库可连接才返回 200，故障返回 503。`POST /api/sms` 需要 `Authorization: Bearer <token>`，只在事务提交后确认 ID。没有短信查询接口。

## 升级、备份与离线构建

升级前备份数据库、本地配置和持久化卷。保留现有 Compose 中的网络与挂载，再执行 `docker compose build message-redirect`、`docker compose up -d message-redirect`。本次保留原 PostgreSQL schema、数据类型、主键和 payload 哈希算法，无需迁移现有短信表。

数据库容器请使用相应的 `pg_dump` / `mysqldump` / `mariadb-dump` 或一致性备份方案。数据卷应纳入日常备份；服务容器本身可以重建。不要在未备份的情况下执行 `docker compose down -v`。

默认 Dockerfile 在线安装依赖，支持 x86_64 / ARM64（取决于对应基础镜像和依赖 wheel）。需要离线安装依赖时，在可联网机器运行 `../scripts/download-wheels.sh`，将 `server/wheels/`（相对仓库根目录） 一并带到构建机器，使用 `docker build -f ../server/Dockerfile.offline -t message-redirect:local ../server`。基础 Python 镜像仍需事先下载。ARM64 目标可用 `WHEEL_ARCH=aarch64 ../scripts/download-wheels.sh`。
