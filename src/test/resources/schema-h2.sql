CREATE TABLE IF NOT EXISTS chat_group (
    id VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    description VARCHAR(255) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);

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
    created_at TIMESTAMP(3) NOT NULL,
    recalled_at TIMESTAMP(3) NULL,
    recalled_by VARCHAR(64) NULL,
    PRIMARY KEY (seq),
    UNIQUE (message_id),
    UNIQUE (from_user_id, client_msg_id)
);

CREATE INDEX IF NOT EXISTS idx_chat_message_conversation
    ON chat_message (conversation_id, seq);
CREATE INDEX IF NOT EXISTS idx_chat_message_created
    ON chat_message (created_at);

CREATE TABLE IF NOT EXISTS chat_image (
    id VARCHAR(64) NOT NULL,
    owner_user_id VARCHAR(64) NOT NULL,
    content_type VARCHAR(32) NOT NULL,
    size BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS chat_read_state (
    user_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(148) NOT NULL,
    last_read_seq BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (user_id, conversation_id)
);
