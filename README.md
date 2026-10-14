# Message Redirect · 短信转存

原生 Android App（Java，Android 6.0+）及自托管 Python/PostgreSQL 写入服务。接收普通短信，在手机持久化后批量上传；数据库密码保留在服务器，手机只保存服务 URL 和独立 Bearer 令牌。首次安装默认关闭上传，不内置地址或令牌。

## 功能与边界

- 新短信通过 `SMS_RECEIVED` 接收，合并分段并存入 SQLite。手动授权后可导入最近 100 条收件箱短信。
- 上传每批最多 5 条。只有 HTTP 200 且确认 ID 与当前批次完全匹配，才标记上传完成；失败保留本地数据。
- JobScheduler 在网络可用时上传，失败指数退避；每 15 分钟另检查待传队列，无数据时不请求服务器。系统省电限制可能延迟任务。
- 服务端参数化 SQL，按 `(device_id,message_id)` 幂等写入；相同 ID、不同内容返回 409 并回滚整个批次。
- 本地按号码/正文/发送时间去重；卸载或清除数据会丢失本地记录、生成新的设备 UUID，重新导入可能再次入库。
- 不处理 MMS、RCS 或其他聊天应用，不发送短信，不申请成为默认短信 App。
- 服务不提供短信读取 API。需要阅读归档时，可另行部署 [NASBridge](https://github.com/raycao871218/NASBridge)。

“强行停止”后需重新打开 App；厂商后台限制也可能影响接收和上传。配置令牌后仍需用户授权短信权限。正式应用分发需要自行处理签名与平台的短信权限要求。

## Android 构建与使用

依赖 JDK 17、Android SDK Platform 34 和 Build Tools 34.0.0。设置自己的 `JAVA_HOME` 和未跟踪的 `local.properties`：

```properties
sdk.dir=/path/to/Android/sdk
```

```sh
./gradlew :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

在“服务器配置”填写自己的 HTTP/HTTPS 地址和至少 32 字符令牌，开启自动上传；授权接收新短信后可点击立即上传或手动导入历史短信。默认仅允许 HTTPS，以及本机/模拟器的 HTTP 测试地址。自建局域网必须使用 HTTP 时，在 `app/src/main/res/xml/network_security_config.xml` 添加自己的域名或地址后重新构建。明文 HTTP 会暴露传输内容，跨可信网络访问时使用 HTTPS。不要提交私钥、签名文件、令牌、真实短信或构建 APK。

## 服务端部署

需要 Docker Compose 和自行准备的 PostgreSQL 数据库/账号。服务启动会执行 `backend/schema.sql` 创建 `message_redirect.sms_messages` 及索引；账号需要相应的 schema/table 权限。时间字段使用 `timestamptz`。数据库不包含在 Compose 中。

```sh
mkdir -p .secrets
cp backend/config.example.json .secrets/server.json
cp compose.example.yaml compose.yaml
cp .env.example .env
```

编辑 `.secrets/server.json` 填写数据库连接，生成独立随机令牌（例如运行 `python3 -c 'import secrets; print(secrets.token_urlsafe(32))'` 后自行填入）。配置文件按只读卷挂载，不复制到镜像。

Compose 默认仅绑定 `127.0.0.1:18081`。如需手机访问，在本地 `.env` 中设置自己的 `SMS_BIND_ADDRESS`，或通过 HTTPS 反向代理暴露。配置文件所有者 UID/GID 必须与 `SMS_UID/SMS_GID` 对应，权限可设为 600；根据宿主机修改，示例默认 1026:100。

构建使用 Linux x86_64 / Python 3.12 wheels，需要先下载；其他 CPU 架构应调整下载脚本和基础镜像环境：

```sh
./scripts/download-wheels.sh
docker compose -f compose.yaml config --quiet
docker compose -f compose.yaml up -d --build
docker compose -f compose.yaml ps
docker compose -f compose.yaml logs --tail=30 message-redirect
```

示例使用独立 Docker 子网与固定容器地址；如与现有网络冲突请修改。PostgreSQL 防火墙和 `pg_hba.conf` 需允许对应客户端。可选 `scripts/configure-pg.py --help` 工具由管理员在数据库主机执行，显式指定数据库、用户、容器地址、HBA 路径及备份目录；它会修改访问规则并 reload，部署前应单独审核。

## 测试与可选管理脚本

本地后端单元测试使用 mock，不连接生产数据库或读取私人短信：

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r backend/requirements.txt httpx
python -m unittest discover -s backend/tests -p test_api.py -v
```

专用模拟器测试使用 `test-messages.db` 和 `test-sync`，覆盖本地去重、排序、持久化与失败确认处理：

```sh
./gradlew :app:assembleDebugAndroidTest
adb -e install -r app/build/outputs/apk/debug/app-debug.apk
adb -e install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -e shell am instrument -w com.nas.messageredirect.test/com.nas.messageredirect.SmsInstrumentation
```

可选脚本 `provision-phone.py <serial> --server-url <your-url>` 从本地 `.secrets/server.json` 为调试 App 配置令牌；使用已授权 ADB，要求调试包支持 `run-as`。不会将令牌写入源码。

`nas-admin.py` 使用显式 `--host` 或 `NAS_SSH_TARGET` 以及本地 `.secrets/ssh-password` 执行管理员命令。`integration-nas.py` 会在远端容器中运行真实 API/数据库集成测试，创建临时记录并按随机 UUID 清理；只有明确要操作对应测试环境时才运行。常规单元测试不执行这些管理脚本。

仓库不包含任何私密配置、短信数据库、设备标识、运行截图、下载 wheels 或 APK。应用代码按 [MIT](LICENSE) 开源，第三方说明见 [THIRD_PARTY.md](THIRD_PARTY.md)。
