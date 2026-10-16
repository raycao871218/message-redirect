# 客户端接入与配对

本项目独立提供短信接收 API，无需 NASBridge。Swagger UI：服务地址的 `/docs`；OpenAPI 3.1 JSON：`/openapi.json`。Swagger 自带的示例全部为合成数据；点击 Try it out / Execute 会真实入库，请在自己的测试环境使用。

## 批量入库

向 `POST /api/sms` 发送 JSON，头部携带 `Authorization: Bearer <访问密钥>`。密钥从后端管理面板获取，不是管理密码。

```json
{
  "device_id": "ac280128-8cb8-41c1-9cd4-95ee2ebf2d0e",
  "messages": [{
    "message_id": "5172727d-f189-44dc-bbf8-1692f50fdfb1",
    "sender": "EXAMPLE",
    "body": "合成测试短信",
    "sent_at": 1792000000000,
    "captured_at": 1792000001000,
    "source": "api-demo"
  }]
}
```

`device_id` 为稳定的客户端 UUID；`message_id` 是每条短信的 UUID，重试时保持原值。时间戳为 Unix 毫秒整数，不是秒或日期字符串。批次为 1–50 条、总请求不超过 1 MiB。字段限制与完整 Schema 见 Swagger。

成功响应：

```json
{"accepted":["5172727d-f189-44dc-bbf8-1692f50fdfb1"]}
```

收到 HTTP 200 且确认 ID 与本次发送集合一致，才能标记完成。相同设备和 ID、相同内容的重试返回同样确认，不重复保存；同 ID 内容冲突返回 409，整批回滚。不要为每次重试生成新 ID。

| HTTP 状态 | 含义与处理 |
| --- | --- |
| 401 | 密钥缺失、错误或已重置，需要重新配置 |
| 409 | 同 ID 内容冲突，整批未提交，需检查客户端标识和数据 |
| 413 | 请求超过大小限制，拆分批次 |
| 422 | JSON 或字段不符合规范，修正后再发送 |
| 503 / 网络中断 | 保留原数据，退避后重试；若上次已提交，重试仍保持幂等 |

所有短信查询、详情及阅读状态接口同样要求 `Authorization: Bearer <访问密钥>`；此密钥可读写整个归档，应只交给可信客户端。管理密码不用于普通客户端接入。接收本机手机号暂未纳入入库协议；App 的本机号码标记仍保存在手机并用于邮件。

## 查询短信

`GET /api/sms` 返回分页列表，默认每页 50 条，`limit` 可设 1–100。按 `sent_at` 倒序，相同时间再按 `device_id`、`message_id` 倒序，使用游标避免新短信到达时造成传统页码偏移。

| 参数 | 说明 |
| --- | --- |
| `cursor` | 上次响应中的 `next_cursor`；首次查询省略 |
| `device_id` | 按接收设备 UUID 筛选 |
| `sender` | 按发送方精确匹配 |
| `is_read` | `true` 为已读，`false` 为未读，省略查询全部 |
| `sent_from` / `sent_until` | 发送时间范围，Unix 毫秒，包含边界 |

响应为 `{"items":[...],"next_cursor":"..."}`，到末页时 `next_cursor` 为 `null`。继续翻页时保留相同筛选条件，不要解析或修改游标；游标损坏或筛选条件改变返回 400。新到达且排在游标之前的短信，需要从第一页刷新获取。

`GET /api/sms/{device_id}/{message_id}` 读取详情，找不到返回 404。短信由两个 ID 共同定位；不同设备可以有相同的 `message_id`。

列表和详情返回入库时的各字段，另含 `device_id`、`received_at`（服务端归档时间）、`is_read` 和 `read_at`。所有时间均为 Unix 毫秒，`read_at` 未读时为 `null`。内部 `payload_hash` 不对外返回。读取列表或详情不会改变阅读状态。

## 标记已读／未读

向 `PATCH /api/sms/{device_id}/{message_id}/read` 发送：

```json
{"is_read": true}
```

响应为更新后的短信详情。所有客户端共享这份归档阅读状态，与手机收件箱无关；首次标记已读时设置 `read_at`，重复标记保留首次时间。改为 `false` 会恢复未读并清空时间；再次标记已读时记录新时间。重复上传同一短信不会重置已读状态。

0.8.1 首次启动会为旧表补上可空的 `read_at` 字段，已有短信视为未读，不修改正文、原始时间、ID 或去重哈希。升级需具备短信表的 ALTER 权限，且应事先备份数据库。

## 配置二维码 v1

面板在本地生成带白色静区的标准 QR Code，内容是 UTF-8 JSON：

```json
{
  "type": "gugugu.server",
  "version": 1,
  "name": "咕咕咕服务",
  "url": "https://sms.example.com",
  "api_token": "这里是面板生成的访问密钥"
}
```

二维码包含访问密钥，等同连接凭据，不要公开截图。重置密钥后旧二维码随之失效。`url` 为服务根地址，不要填写 `/api/sms`，不允许 URL 内嵌账号、查询参数或 fragment。Android 扫描器只解析已知版本的数据，不自动打开网址；显示名称与地址供用户确认，确认只填入表单，显式保存后生效。

公开 App 默认要求 HTTPS（本机测试地址除外），本地个人 HTTP 规则不会包含在公开 APK。其他客户端应同样保护传输中的短信和密钥。
