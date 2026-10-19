# tf-chat

FishMatrix（摸鱼矩阵）聊天消息服务：群聊 / 私聊与消息同步 —— Spring Boot 3 + JDK 17。

- 端口：`8102`
- 健康检查：`GET /actuator/health`、`GET /api/v1/chat/health`
- 实时通道：`WS /ws/chat?userId=<id>&nickname=<name>`
- 数据中心：Redis（群组、历史 Stream、在线状态、私聊离线、Pub/Sub 广播）
- 当前实现：3 个默认群组、群聊实时广播、私聊服务端定向中转、离线消息补发

## API 一览

| 方法/路径 | 说明 |
|---|---|
| `GET /api/v1/chat/groups` | 群组列表 + 在线人数 |
| `GET /api/v1/chat/groups/{id}/history?limit=20` | 群组最近消息 |
| `GET /api/v1/chat/groups/{id}/members` | 群组在线成员 |
| `GET /api/v1/chat/dm/{peerUserId}/history?userId=&limit=50` | 私聊历史 |
| `WS /ws/chat` | 实时消息，协议见 `api/openapi.yaml` |

## 终端命令映射（前端 TerminalChat）

`ls /opt/chat/group`、`cd <group>`、`echo "<msg>" >> chat.log`、`tail -n 20 chat.log`、
`tail -f chat.log`、`ps aux | grep dev`、`ssh <nickname>[@node-xx]`、
`cat ascii/funny.cat`、`pwd`、`clear`、`help`、`exit`。

## Redis 键

| 键 | 用途 |
|---|---|
| `tf:chat:groups` | 群组 Hash |
| `tf:chat:history:<groupId>` | 群聊历史 Stream（MAXLEN ≈ 1000） |
| `tf:chat:dm:<a>:<b>` | 私聊历史 Stream |
| `tf:chat:offline:<userId>` | 离线私聊 Stream，连接后 drain |
| `tf:chat:online` + `tf:chat:online:meta` | 全局在线状态（ZSET + Hash） |
| `tf:chat:presence:<groupId>` + `tf:chat:presence:meta:<groupId>` | 群组在线成员 |
| `tf:chat:events` | Pub/Sub 广播频道 |

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `REDIS_HOST` / `REDIS_PORT` | `10.28.25.234` / `6379` | Redis |
| `CHAT_RELAY_ENABLED` | `true` | Redis Pub/Sub relay；测试关闭 |
| `CHAT_PRESENCE_TTL_MS` | `60000` | 在线状态过期时间（心跳 25s） |
| `NACOS_ADDR` / `NACOS_USERNAME` / `NACOS_PASSWORD` | — | 服务注册 |

## 本地开发

```bash
mvn spring-boot:run    # 启动开发服务
mvn -B test            # 运行测试（不依赖 MySQL/Redis/Nacos）
```

> Redis 不可用时服务仍可启动，REST 返回 503、WebSocket 拒绝连接并在前端重连；
> relay 每 15 秒自动重试，Redis 恢复后无需重启服务。

## 构建

```bash
mvn -B -DskipTests package   # 产物 target/tf-chat.jar
java -jar target/tf-chat.jar
```

## 分支模型

- `master`：保护分支，只接受 PR（CI 必须通过，squash 合并），合并后自动发版
- `test`：联调分支，开发分支直接合入（不走 PR），可直接推送
- 开发流：`feature/*` 开发 → 直接合入 `test` 联调 → 验证 OK 后从开发分支开 PR 合 `master` → 自动发版
