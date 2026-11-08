-- tf-chat 全表中文注释
-- 只修改表与字段注释，不改变类型、默认值、索引；列定义逐字取自 MySQL 8.4 的 SHOW CREATE TABLE。
-- 列注释统一用 ALGORITHM=INSTANT，若定义与生产不一致会直接报错，避免静默改结构。

ALTER TABLE `chat_group` COMMENT = '聊天群组：名称、简介与列表顺序';

ALTER TABLE `chat_group`
    MODIFY COLUMN `id` varchar(32) NOT NULL COMMENT '群组标识，内置群在 V1 预置',
    MODIFY COLUMN `name` varchar(64) NOT NULL COMMENT '群名称',
    MODIFY COLUMN `description` varchar(255) NOT NULL COMMENT '群简介',
    MODIFY COLUMN `sort_order` int NOT NULL DEFAULT '0' COMMENT '列表排序值，越大越靠前',
    MODIFY COLUMN `created_at` datetime(3) NOT NULL COMMENT '创建时间',
    ALGORITHM=INSTANT;

ALTER TABLE `chat_message` COMMENT = '聊天消息：私聊与群聊共用，按会话与序号读取';

ALTER TABLE `chat_message`
    MODIFY COLUMN `seq` bigint NOT NULL AUTO_INCREMENT COMMENT '消息主键，全局自增序号',
    MODIFY COLUMN `message_id` varchar(36) NOT NULL COMMENT '消息业务唯一标识 UUID',
    MODIFY COLUMN `conversation_id` varchar(148) NOT NULL COMMENT '会话标识，私聊为 dm:小用户ID:大用户ID，群聊为 group:群ID',
    MODIFY COLUMN `scope` varchar(8) NOT NULL COMMENT '会话类型，DM 私聊 / GROUP 群聊',
    MODIFY COLUMN `group_id` varchar(32) DEFAULT NULL COMMENT '群组标识，私聊为空',
    MODIFY COLUMN `from_user_id` varchar(64) NOT NULL COMMENT '发送者用户标识',
    MODIFY COLUMN `from_nickname` varchar(32) NOT NULL COMMENT '发送者昵称快照',
    MODIFY COLUMN `from_node` varchar(24) NOT NULL COMMENT '发送者所在节点标识',
    MODIFY COLUMN `to_user_id` varchar(64) DEFAULT NULL COMMENT '私聊接收者用户标识，群聊为空',
    MODIFY COLUMN `content` text NOT NULL COMMENT '消息正文，图片消息存图片 ID',
    MODIFY COLUMN `kind` varchar(16) NOT NULL COMMENT '消息类型，text 文本 / ascii 字符画 / image 图片',
    MODIFY COLUMN `client_msg_id` varchar(80) DEFAULT NULL COMMENT '客户端消息 ID，用于重复发送去重',
    MODIFY COLUMN `created_at` datetime(3) NOT NULL COMMENT '消息发送时间',
    MODIFY COLUMN `recalled_at` datetime(3) DEFAULT NULL COMMENT '撤回时间，为空表示未撤回',
    MODIFY COLUMN `recalled_by` varchar(64) DEFAULT NULL COMMENT '撤回操作人用户标识，为空表示未撤回',
    ALGORITHM=INSTANT;

ALTER TABLE `chat_image` COMMENT = '私聊图片：上传后的图片元数据';

ALTER TABLE `chat_image`
    MODIFY COLUMN `id` varchar(64) NOT NULL COMMENT '图片标识，作为 image 消息内容发送',
    MODIFY COLUMN `owner_user_id` varchar(64) NOT NULL COMMENT '上传者用户标识',
    MODIFY COLUMN `content_type` varchar(32) NOT NULL COMMENT '图片 MIME 类型',
    MODIFY COLUMN `size` bigint NOT NULL COMMENT '图片字节数',
    MODIFY COLUMN `created_at` datetime(3) NOT NULL COMMENT '上传时间',
    ALGORITHM=INSTANT;

ALTER TABLE `chat_read_state` COMMENT = '会话已读位置：每个用户在每个会话读到的最新消息序号';

ALTER TABLE `chat_read_state`
    MODIFY COLUMN `user_id` varchar(64) NOT NULL COMMENT '用户标识',
    MODIFY COLUMN `conversation_id` varchar(148) NOT NULL COMMENT '会话标识，格式同 chat_message',
    MODIFY COLUMN `last_read_seq` bigint NOT NULL DEFAULT '0' COMMENT '已读到的最大消息序号',
    MODIFY COLUMN `updated_at` datetime(3) NOT NULL COMMENT '已读位置最后更新时间',
    ALGORITHM=INSTANT;
