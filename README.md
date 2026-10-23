# tf-chat

FishMatrix（摸鱼矩阵）聊天消息服务：登录态私聊持久化与群聊临时会话、图片发送、游标分页、搜索、撤回与未读同步 —— Spring Boot 3 + JDK 17。

- 端口：`8102`
- 健康检查：`GET /actuator/health`、`GET /api/v1/chat/health`（免鉴权）
- 实时通道：`WS /ws/chat?ticket=<一次性票据>`
- 身份来源：`tf-common` 统一校验网关签名身份头（或 Bearer 回退），聊天侧映射为 `u<accountId>`
- 数据中心：MySQL 存私聊消息/群组/已读位点/图片元数据；图片文件存 `CHAT_IMAGE_DIR`；Redis 存在线状态、离线私聊、限流、WS 票据与 Pub/Sub
- 群聊是临时会话：消息与图片不落库、不落盘，只实时广播

> 玩法、命令与常见问题：[docs/聊天玩法.md](docs/聊天玩法.md)

## 鉴权模型

REST 与 WebSocket 都不接受客户端自报身份，昵称与角色始终来自用户服务。

```text
浏览器 ── Authorization: Bearer <token> ──> tf-gateway
   │                                            └─ 校验 token，注入 HMAC 签名身份头
   │                                               └─> tf-chat（tf-common AuthInterceptor）
   └─ POST /api/v1/chat/ws-ticket ──> {ticket, expiresIn}
        └─ WS /ws/chat?ticket=<ticket>（GETDEL 一次性消费，60s 过期）
```

- 身份校验、签名头验证、Bearer 回退与用户快照缓存全部由 `tf-common` 提供，聊天模块不再单独调用用户中台。
- REST 缺少或无效身份：`401 {"error":"UNAUTHORIZED"}`；用户中台不可用时 `tf-common` 返回 503，不做匿名降级。
- WebSocket 握手无 ticket 或 ticket 已消费：拒绝连接。
- 长期 token 不进入 URL 与访问日志；重连时前端重新申请 ticket。

## API 一览

除健康检查外均需 `Authorization: Bearer <token>`。

| 方法/路径 | 说明 |
|---|---|
| `GET /api/v1/chat/groups` | 群组列表 + 在线人数（群未读恒为 0） |
| `GET /api/v1/chat/groups/{id}/history?limit=&beforeSeq=&afterSeq=` | 群聊临时会话，固定返回空页 |
| `GET /api/v1/chat/groups/{id}/search?q=&limit=` | 群聊不持久化，固定返回空列表 |
| `GET /api/v1/chat/groups/{id}/members` | 群内在线成员 |
| `GET /api/v1/chat/online` | 全局在线成员 |
| `GET /api/v1/chat/dm/{peerUserId}/history?limit=&beforeSeq=&afterSeq=` | 私聊历史（viewer 来自 token） |
| `GET /api/v1/chat/dm/{peerUserId}/search?q=&limit=` | 私聊搜索 |
| `GET /api/v1/chat/unread` | `{groups:{gid:0}, dms:{peerId:count}, total}`，total 仅含私聊 |
| `POST /api/v1/chat/read` | `{groupId?, peerUserId?, seq?}` 标记私聊已读；群聊为 no-op，`204` |
| `POST /api/v1/chat/messages/{messageId}/recall` | 撤回自己窗口内的私聊消息；群聊不支持 |
| `POST /api/v1/chat/ws-ticket` | 申请一次性 WebSocket ticket |
| `POST /api/v1/chat/images` | multipart 字段 `file`，上传私聊图片，返回 `{image}`，`201` |
| `GET /api/v1/chat/images/{imageId}` | 带鉴权读取私聊图片二进制 |

分页约定：

- `limit` 默认私聊 50，服务端上限 200（搜索上限 100）。
- `beforeSeq`：向上翻页，返回该游标之前最近的一页。
- `afterSeq`：断线补消息，返回该游标之后的消息。
- 两者不能同时使用；响应 `{messages, hasMore, nextBeforeSeq}`，`messages` 按 `seq` 升序。

`ChatMessage` 字段：`id`（UUID）、`seq`（全局递增游标；群聊临时消息为 0）、`groupId`、`fromUserId`、`fromNickname`、`fromNode`、`toUserId`、`content`、`kind`（`text` / `ascii` / `image`）、`ts`、`clientMsgId`、`recalled`。

图片消息约定：

- 私聊：`content` 为图片 ID（先 `POST /api/v1/chat/images` 上传），消息落库；
- 群聊：`content` 为 `data:image/...;base64,...`，服务端校验类型与大小后只广播；
- 前端终端只显示 `[图片] #短ID`，实际图片来自缩略图条 / 鉴权下载。

## WebSocket 协议

连接：`GET /ws/chat?ticket=<ticket>`（`ticket` 由 REST 申请）。

客户端帧：

```json
{"type":"ping","ts":1700000000000}
{"type":"join","groupId":"dev-group"}
{"type":"message","content":"hello","kind":"text","clientMsgId":"c-1"}
{"type":"message","content":"data:image/png;base64,<...>","kind":"image","clientMsgId":"c-2"}
{"type":"dm","toUserId":"u9","content":"hi","kind":"text","clientMsgId":"c-3"}
{"type":"dm","toUserId":"u9","content":"<imageId>","kind":"image","clientMsgId":"c-4"}
{"type":"read","groupId":"dev-group","seq":42}
{"type":"read","peerUserId":"u9","seq":17}
{"type":"recall","messageId":"<uuid>"}
{"type":"leave"}
```

服务端帧：

| type | 说明 |
|---|---|
| `ready` | 连接就绪，`{user:{id,nickname,node}, serverTime}`，身份来自 ticket |
| `presence` | 群内在线成员变化（加入群或广播） |
| `message` | 群消息 |
| `dm` | 私聊（接收方经 Redis relay，发送方由服务端回显） |
| `dm_offline` | 连接建立时补发的离线私聊，`{messages:[...]}` |
| `recalled` | 撤回通知，`{message, byUserId}` |
| `pong` | 心跳回包 |
| `error` | `{code, message}`，见下表 |
| `left` | 离开当前群组 |

错误码：`BAD_REQUEST`、`UNAUTHORIZED`、`UNKNOWN_TYPE`、`NOT_FOUND`、`RATE_LIMITED`、`CHAT_UNAVAILABLE`、`INTERNAL_ERROR`。处理出错时发送 `error` 帧，不主动断开连接（建连阶段失败除外）。

## 消息语义

- **群聊临时会话**：`message` 帧不写 MySQL、不落盘，`seq=0`，只经 Redis Pub/Sub 广播；不提供历史、搜索、未读、撤回。
- **私聊持久化**：`dm` 帧写 `chat_message`，按 `(from_user_id, client_msg_id)` 幂等；二次连接可通过历史接口回看。
- **限流**：Redis `tf:chat:rate:<userId>:<10s窗口>`，默认 20 条/10 秒；超频发送时 WS 返回 `RATE_LIMITED` 错误帧（当前仅 WS 提供发送入口）。
- **内容清理**：去除 C0/C1 控制字符与 bidi 控制字符（保留 `\n`、`\t`），客户端写终端前再次清理，避免 ANSI 注入；图片 data URL / 图片 ID 不做文本清理。
- **图片**：私聊文件 5MB、群聊 data URL 解码后 1MB；校验 PNG/JPEG/GIF/WebP 魔数。私聊图片仅上传者与该私聊参与者可读取。
- **撤回**：仅私聊、仅发送者本人、默认 120 秒窗口内可撤回；记录保留，内容清空并标记 `recalled=true`，历史返回撤回占位。
- **未读**：`chat_read_state` 按 `(user_id, conversation_id)` 保存 `last_read_seq`；仅统计私聊，群聊未读恒为 0。
- **在线状态**：Redis ZSET score 为过期时间，心跳续期；断线不直接删除全局在线键，靠 60 秒 TTL 自然过期。
- **离线私聊**：接收者不在线时写入 `tf:chat:offline:<userId>`，重连后 `RENAME` 到临时键再读取补发，避免丢消息。

## 数据库

默认库 `fm_chat`（测试 `fm_test_chat`）。表结构由 Flyway 管理，业务容器不建表：
`src/main/resources/db/migration/V1__baseline.sql` 由独立任务 `db-migrate-chat`
使用迁移账号执行。生产/测试业务容器固定 `SPRING_FLYWAY_ENABLED=false`、`SQL_INIT_MODE=never`。

| 表 | 用途 |
|---|---|
| `chat_group` | 群组目录，由 `V1__baseline.sql` 写入 `dev-group`（研发协作）/ `ops-oncall`（运维值班）/ `tech-share`（技术分享） |
| `chat_message` | 私聊消息主表，`seq` 自增游标；唯一键 `message_id`、`(from_user_id, client_msg_id)`；群聊不写入 |
| `chat_read_state` | 每个用户在每个私聊会话的已读位点 |
| `chat_image` | 私聊图片元数据：`id`、`owner_user_id`、`content_type`、`size`、`created_at`；文件存 `CHAT_IMAGE_DIR` |

会话 ID：群聊 `group:<gid>`，私聊 `dm:<较小userId>:<较大userId>`。

## Redis 键

| 键 | 用途 |
|---|---|
| `tf:chat:ws-ticket:<ticket>` | 一次性 WS 握手票据，TTL 60s，GETDEL |
| `tf:chat:rate:<userId>:<窗口>` | 发送限流计数 |
| `tf:chat:offline:<userId>` | 离线私聊 Stream；重连后改名到临时键读取补发 |
| `tf:chat:online` + `tf:chat:online:meta` | 全局在线（ZSET + Hash） |
| `tf:chat:presence:<groupId>` + `tf:chat:presence:meta:<groupId>` | 群内在线成员 |
| `tf:chat:events` | Pub/Sub 实时广播频道 |

> 旧版 Redis Stream 历史（`tf:chat:history:*`、`tf:chat:dm:*`）不再写入，历史以 MySQL 为准。

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_DB` | `10.28.13.214` / `3306` / `fm_chat` | MySQL |
| `MYSQL_APP_USER` / `MYSQL_APP_PASSWORD` | `fm_chat_app` / — | 运行时账号（仅 DML，密码由容器环境注入） |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_DATABASE` | `10.28.13.214` / `6379` / `0` | Redis |
| `SQL_INIT_MODE` | `never` | 业务容器不执行 SQL 初始化 |
| `SPRING_FLYWAY_ENABLED` | `false` | 业务容器不执行迁移，由 `db-migrate-chat` 负责 |
| `TF_USER_URL` | `http://127.0.0.1:8101` | 用户中台地址（Bearer 回退 introspection） |
| `FM_IDENTITY_SECRET` | 空 | 网关身份签名密钥，须与网关一致 |
| `CHAT_WS_TICKET_TTL_SECONDS` | `60` | WS 票据有效期 |
| `CHAT_RECALL_WINDOW_SECONDS` | `120` | 撤回时间窗口 |
| `CHAT_RATE_LIMIT_PER_10S` | `20` | 每 10 秒发送上限 |
| `CHAT_IMAGE_DIR` | `./data/chat-images` | 私聊图片存储目录（容器内 `/app/data/chat-images`，compose 已挂卷） |
| `CHAT_MAX_IMAGE_BYTES` | `5242880` | 私聊图片上传上限（5MB） |
| `CHAT_MAX_GROUP_IMAGE_BYTES` | `1048576` | 群聊图片解码后上限（1MB） |
| `CHAT_ALLOWED_ORIGINS` | `*` | WS 允许来源（逗号分隔） |
| `CHAT_RELAY_ENABLED` | `true` | Redis Pub/Sub relay；单实例测试可关 |
| `CHAT_PRESENCE_TTL_MS` | `60000` | 在线状态过期时间（心跳 25s） |
| `NACOS_ADDR` / `NACOS_NAMESPACE` / `NACOS_USERNAME` / `NACOS_PASSWORD` | — | 服务注册与配置 |

## 本地开发

```bash
mvn spring-boot:run    # 启动开发服务（需本机 MySQL/Redis/用户服务，或改用测试配置）
mvn -B test            # 单元/切片测试，H2 + Mock，不依赖 MySQL/Redis/Nacos
```

- 测试使用 H2 MySQL 模式与 `schema-h2.sql`，`src/test/resources/application.yml` 关闭 Nacos 与 relay。
- Redis 不可用时服务仍可启动：REST 返回 503，WebSocket 拒绝连接并由前端重连；relay 每 15 秒自动重试，Redis 恢复后无需重启。
- MySQL 不可用时 `/api/v1/chat/**` 返回 503 `CHAT_UNAVAILABLE`。

## 构建

```bash
mvn -B -DskipTests package   # 产物 target/tf-chat.jar
java -jar target/tf-chat.jar
```

## 终端命令映射（前端）

进入群组或私聊后直接输入内容并回车即发送，当前会话的新消息自动实时输出。
连接操作：`cd <group>` 切换群组、`ssh <nickname>[@node-xx]` 发起私聊、`exit` 退出私聊。
辅助命令：`ls`、`tail -n 50 chat.log`、`ps aux | grep dev`、`who`、`grep <keyword>`、
`recall #<短ID>`、`cat ascii/funny.cat`、`pwd`、`clear`、`help`。
旧写法 `echo "<msg>" >> chat.log` 仍兼容。

## 分支模型

- `master`：保护分支，只接受 PR（CI 必须通过，squash 合并），合并后自动发版
- `test`：联调分支，开发分支直接合入（不走 PR），可直接推送
- 开发流：`feature/*` 开发 → 直接合入 `test` 联调 → 验证 OK 后从开发分支开 PR 合 `master` → 自动发版
