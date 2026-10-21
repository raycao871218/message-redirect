<h1 align="center">咕咕咕 · Message Redirect</h1>

<p align="center">
  <img src="docs/assets/dove-courtship.gif" alt="咕咕咕斑鸠鞠躬求偶动画" width="280">
</p>

<p align="center">
  <a href="README.zh-CN.md"><img src="https://img.shields.io/badge/🇨🇳_简体中文-当前-blue" alt="简体中文（当前）"></a>
  <a href="README.md"><img src="https://img.shields.io/badge/🇺🇸_English-Available-green" alt="English"></a>
</p>

**下载 App 即可使用，无需部署服务器、Docker 或数据库。** 咕咕咕是一款原生 Android 短信转发 App，在手机上配置邮箱后，就能直接通过 SMTP 转发短信和验证码。

家里有多部手机，出门又不方便全部带上，验证码往往发到了留在家里的那一部。咕咕咕就是替你在家收短信的小信使：在留家的 Android 手机上运行，将短信投递到你随身手机能查看的邮箱。

自托管归档是可选的进阶功能，主要面向需要进一步开发、接入自有系统，或对短信隐私与存储控制有更高要求的用户。项目代码采用 [MIT 许可证](LICENSE)。

## 下载即用（无需部署）

1. 前往 [下载最新版 App](https://github.com/raycao871218/message-redirect/releases/latest)，在发布页的 **Assets** 中下载 `.apk`，安装到接收短信的 Android 手机上（Android 6.0+）。
2. 打开 App，在设置向导中选择「邮件转发」，按提示配置 SMTP 邮箱和收件地址。Gmail 使用应用专用密码，QQ、网易使用客户端授权码。
3. 设置接收手机号，授予短信接收权限，开启首页的转发开关。

手机保持联网后，新收到的短信会直接发送到指定邮箱。你可以在随身手机上查看邮件，无需自建服务，也无需自行编译 App。仅转发新短信，不自动导入历史收件箱。

App 默认跟随系统语言，支持中文（简体、繁体）、英语和日语；其他系统语言回退到英语。点击首页或设置向导第一页的 **语言 / Language / 言語** 按钮，可选择「跟随系统、简体中文、English、日本語」，选择后立即生效。Android 13 及以上会同步系统中的应用语言设置。

## App 使用说明

Android 6.0+。首次选择“邮件转发”或“自建数据库”，设置接收手机号并授予短信接收权限。后续通过“管理转发配置”选择卡片；总开关在首页控制，切换卡片不会擅自开启已暂停的转发。仅接收新短信，不自动导入历史收件箱。

Gmail 使用应用专用密码；QQ、网易使用客户端授权码。邮箱授权码用 Android Keystore 加密保存在手机私有配置中，编辑时留空保留；更换服务器或账号须重新填写。

发送中断时，App 可能无法判断邮件是否寄出，为避免重复不会自动重发这封邮件。打开“更多操作 → 检查邮件是否收到”，先检查收件箱和垃圾箱，再选择“已收到”或“没收到，重发”；重发可能产生重复邮件。首页只在确实需要人工检查时显示对应数量，正常发送中的邮件单独标明。

“更多操作 → 关于咕咕咕”显示当前安装版本、构建号和斑鸠。“更多操作 → 使用说明”集中解释自动转发、失败重试、邮件检查、配置切换和后台运行。暂停时新短信仍保留在手机，重新开启后继续发送；已经开始发送的短信保留原收件邮箱或服务地址，切回对应配置后继续发送。系统后台/省电限制可能延迟处理；强行停止后需重新打开 App。

## 功能

- 首次使用通过设置向导完成配置。配置保存成卡片，点卡片切换；仅在“编辑配置 / 新增配置”时进入详细表单，可保存多个邮箱或数据库目标。
- 邮件由手机直接发送，无需部署后端。内置 Gmail、QQ、网易和自定义 SMTP 引导；微软个人邮箱需 OAuth2，本版仅提供参数说明，暂未接入微软登录。
- 接收手机号可授权从 SIM 读取或手动填写，支持双卡。邮件注明接收手机号与原发送号码；号码随短信保存，不改写历史。不确定号码时显示“未记录”。
- 首页只显示状态，不展示短信列表或正文。短信先保存在手机队列，HTTP 入库成功确认后才标记完成。邮件提交结果不明时暂停重发，避免盲目重复投递。
- 服务支持持久化、健康检查、访问令牌验证与事务入库。相同 `(device_id, message_id)` 重试不重复保存；相同 ID 内容冲突返回 409 并回滚整批。
- 后端自带独立管理面板：测试/保存数据库连接、重置 App 访问密钥和生成配对二维码。App 扫码后先预览，确认填入并保存才生效。
- `/docs` 提供本地 Swagger UI，`/openapi.json` 提供接入规范，方便开发自己的客户端；无需 NASBridge 或外部文档 CDN。
- 蜡笔风界面与丑萌珠颈斑鸠小信使（灰褐羽色、黑底白点颈斑）。选择自建数据库后，界面中的斑鸠换上赛博头盔与夹克盔甲，切回邮件恢复默认；向导和编辑页可即时预览。图标适配 Android 自适应图标和单色主题图标，生成说明见 [图标说明](docs/branding.md)。

## 可选：自托管归档部署

需要通过 API 开发自己的客户端、接入自有系统，或希望短信不经过邮箱服务商、归档到自己控制的数据库时，可以部署配套服务。支持 SQLite、PostgreSQL、MySQL / MariaDB；数据库账号密码保留在服务端。

自托管服务可以只部署在家庭内网，无需向公网开放。外出时可连接家庭 VPN 访问归档。下列 Docker 和数据库配置仅针对这条可选路径。

### Docker 快速启动

需要 Docker Engine / Docker Desktop 和 Docker Compose v2。以下从仓库根目录进入 `deploy/` 操作；第一次使用默认选择 SQLite，数据保存在 Docker 命名卷。

```sh
cd deploy
cp .env.example .env
python3 ../scripts/init-secrets.py
cp compose.example.yaml compose.yaml
docker compose up -d --build
docker compose ps
```

`init-secrets.py` 为新部署生成随机令牌及数据库密码，已有文件不会覆盖。新部署的实际凭据在已忽略的 `deploy/.secrets/` 内；新建目录权限为 0700，文件允许容器以只读挂载方式读取。请保留父目录的私有访问权限。

浏览器打开服务地址进入「服务小窝」，用本地 `deploy/.secrets/admin-password` 中的管理密码登录。该密码与 App 访问密钥独立。面板中填写手机可访问的服务地址，生成二维码；App 新增「自建数据库」配置时点「扫码配置」，确认后保存。相机仅在扫码页面使用，退出即关闭；不授权相机也可手动填写。

数据库与重置后的访问密钥保存在 `/data/settings.json`，需保留数据卷。首次启动时从环境变量或旧 JSON 导入连接和密钥；此后面板保存的状态优先，重建容器不会恢复旧密钥。更换连接只切换后续写入，不会自动迁移旧短信。

Compose 默认只绑定 `127.0.0.1:18081`。手机需要连接时，将本地 `.env` 的 `SMS_BIND_ADDRESS` 改为服务器可访问的地址，或配置 HTTPS 反向代理。`SMS_HTTP_PORT` 可以修改宿主机端口。

App 新建“自建数据库”配置卡：

- **服务地址**：`https://你的服务域名`，或可信局域网的服务地址。
- **访问令牌**：读取本地 `.secrets/api-token` 的内容。不会在源码、镜像或示例中内置真实令牌。

App 连接配套归档服务，数据库账号密码留在服务端。管理面板用于配置；开发者可通过带 Bearer 密钥的 API 分页读取短信、查看详情和维护共享已读状态，完整协议见 [API 文档](docs/api.md)。查询不会自动标记已读，也不改变手机收件箱状态。PostgreSQL 用户也可自行部署 [NASBridge](https://github.com/raycao871218/NASBridge)，它不是咕咕咕存储服务的依赖。

### 选择数据库

模板均位于 `deploy/`。首次部署选择一个模板复制为本地 `compose.yaml`，再执行上面的启动命令。

| 模板 | 部署内容 | 数据位置 |
| --- | --- | --- |
| `compose.example.yaml` | 服务 + SQLite | `sms-data` 命名卷 |
| `compose.postgres.example.yaml` | 服务 + PostgreSQL 17 | `postgres-data` 命名卷 |
| `compose.mysql.example.yaml` | 服务 + MySQL 8.4 | `mysql-data` 命名卷 |
| `compose.mariadb.example.yaml` | 服务 + MariaDB 11.4 | `mariadb-data` 命名卷 |
| `compose.external.example.yaml` | 仅服务，连接已有数据库 | 用户自己的数据库 |

容器数据库的名称与应用账号可通过 `.env` 中 `SMS_DB_NAME`、`SMS_DB_USER` 设置；应用密码和管理密码使用不同的本地秘密文件。数据库端口不发布到宿主机。删除/重建服务容器不会删除命名卷；`docker compose down -v` 会删除数据卷，请先备份。

完整参数、外部数据库、升级与备份说明见 [部署文档](docs/deployment.md)。
自行开发客户端见 [API 与二维码格式](docs/api.md)。

**已有部署请保留自己的 `compose.yaml`、网络、卷和数据库配置，直接备份后升级服务镜像。不要用新模板覆盖正在使用的部署。** 旧版 `.secrets/server.json` 没有 `database.type` 时，仍按 PostgreSQL 处理，保留 `message_redirect.sms_messages` 表和原有幂等哈希。

## 项目结构

```text
apps/android/  原生 Android 工程，独立的 Gradle 构建
server/        归档 API、数据库适配、Dockerfile 和服务端测试
deploy/        Docker Compose 模板和环境变量示例
docs/          部署、品牌等文档
scripts/       仓库级测试、部署及本地维护工具
```

App 和服务端通过 HTTP API 连接，可分别开发、构建和发布。目前保留一个 Git 仓库。

## 从源码构建 Android App

构建依赖 JDK 17、Android SDK Platform 34 / Build Tools 34.0.0。在未跟踪的 `apps/android/local.properties` 配置 SDK：

```properties
sdk.dir=/path/to/Android/sdk
```

```sh
./apps/android/gradlew -p apps/android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
```

覆盖更新必须保持原签名。个人调试密钥可放在仓库根目录已忽略的 `.secrets/android-debug.keystore`。不要因签名不一致而直接卸载，卸载会丢失本地队列和加密配置。

App 默认允许 HTTPS 和本机测试地址。确实需要局域网 HTTP 时，可将 `apps/android/app/src/main/res/xml/network_security_config.xml` 复制到已忽略的 `apps/android/app/src/debug/res/xml/network_security_config.xml`，仅在本地覆盖文件中加入自己的地址；跨不可信网络使用 HTTPS。

不处理 MMS、RCS 或其他聊天应用，不发送短信，不申请成为默认短信 App。正式分发需自行处理签名和平台的短信权限要求。

## 开发与测试

以下命令在仓库根目录执行；Windows 可使用 `apps/android/gradlew.bat -p apps/android`。

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r server/requirements.txt httpx
python -m unittest discover -s server/tests -p 'test_*.py' -v
```

这些测试使用 mock 或临时 SQLite，覆盖认证、输入校验、事务提交/回滚、并发重复提交、故障与配置兼容，不连接生产数据库。Docker 实测使用独立容器、网络和随机测试凭据：

```sh
python3 scripts/test-containers.py
# 或只测一种
python3 scripts/test-containers.py --engines mysql
```

脚本检查四种数据库的真实入库、Unicode、重复重试、冲突回滚、服务容器重建后的数据保留，并清理本次创建的测试资源。GitHub Actions 会运行这些检查。

Android 测试使用合成短信与本机 SMTP fixture，不发送真实邮件。UI 回归可用 `-PuiPreview` 构建独立 `.preview` 测试包；正常安装不会包含测试配置。

```sh
./apps/android/gradlew -p apps/android -PuiPreview :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
adb install -r apps/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.nas.messageredirect.preview.test/com.nas.messageredirect.SmsInstrumentation
adb shell am instrument -w -e ui_flow true com.nas.messageredirect.preview.test/com.nas.messageredirect.SmsInstrumentation
```

## 开源与隐私

本仓库只保存代码、公共模板和示例。实际 `.env`、`.secrets/`、`compose.yaml`、短信数据库、日志、运行截图、APK 和签名密钥都已忽略。公开代码前也需检查历史提交，忽略规则不会移除已经提交过的秘密。

应用代码采用 [MIT](LICENSE)；依赖的许可证和说明见 [THIRD_PARTY.md](THIRD_PARTY.md)。
