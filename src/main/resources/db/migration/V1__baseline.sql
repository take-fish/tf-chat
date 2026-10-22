CREATE TABLE chat_group (
    id VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    description VARCHAR(255) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE chat_message (
    seq BIGINT NOT NULL AUTO_INCREMENT,
    message_id VARCHAR(36) NOT NULL,
    conversation_id VARCHAR(148) NOT NULL,
    scope VARCHAR(8) NOT NULL,
    group_id VARCHAR(32) NULL,
    from_user_id VARCHAR(64) NOT NULL,
    from_nickname VARCHAR(32) NOT NULL,
    from_node VARCHAR(24) NOT NULL,
    to_user_id VARCHAR(64) NULL,
    content TEXT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    client_msg_id VARCHAR(80) NULL,
    created_at DATETIME(3) NOT NULL,
    recalled_at DATETIME(3) NULL,
    recalled_by VARCHAR(64) NULL,
    PRIMARY KEY (seq),
    UNIQUE KEY uk_chat_message_message_id (message_id),
    UNIQUE KEY uk_chat_message_client (from_user_id, client_msg_id),
    KEY idx_chat_message_conversation (conversation_id, seq),
    KEY idx_chat_message_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE chat_image (
    id VARCHAR(64) NOT NULL,
    owner_user_id VARCHAR(64) NOT NULL,
    content_type VARCHAR(32) NOT NULL,
    size BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_chat_image_owner (owner_user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE chat_read_state (
    user_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(148) NOT NULL,
    last_read_seq BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (user_id, conversation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

INSERT INTO chat_group (id, name, description, sort_order, created_at) VALUES
    ('dev-group', '研发协作', '日常研发、需求联调与代码评审', 0, CURRENT_TIMESTAMP(3)),
    ('ops-oncall', '运维值班', '告警、发布与线上故障协同', 1, CURRENT_TIMESTAMP(3)),
    ('tech-share', '技术分享', '架构方案与工具链交流', 2, CURRENT_TIMESTAMP(3));
