CREATE TABLE IF NOT EXISTS chat_group (
    id VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    description VARCHAR(255) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS chat_message (
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

CREATE TABLE IF NOT EXISTS chat_read_state (
    user_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(148) NOT NULL,
    last_read_seq BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (user_id, conversation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
