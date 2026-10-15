# tf-chat

FishMatrix（摸鱼矩阵）聊天消息服务：群聊 / 私聊与消息同步 —— Spring Boot 3 + JDK 17。

- 端口：`8102`
- 健康检查：`GET /actuator/health`、`GET /api/v1/chat/health`

## 本地开发

```bash
mvn spring-boot:run    # 启动开发服务
mvn -B test            # 运行测试
```

## 构建

```bash
mvn -B -DskipTests package   # 产物 target/tf-chat.jar
java -jar target/tf-chat.jar
```

## 分支模型

- `master`：保护分支，只接受 PR（CI 必须通过），合并后自动发版
- `test`：联调分支，可直接推送
- 开发流：`feature/*` → PR 到 `test` → 联调 → PR 到 `master` → 自动发版
