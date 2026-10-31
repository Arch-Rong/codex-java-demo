-- ID 使用大小写敏感且不忽略尾部空格的比较规则，避免请求编号被错误合并。
CREATE TABLE conversation (
    id VARCHAR(64) NOT NULL COMMENT '业务对话 ID',
    title VARCHAR(200) NOT NULL DEFAULT '未命名对话',
    thread_id VARCHAR(255) NULL COMMENT 'Codex thread ID，绑定前为空',
    thread_state VARCHAR(20) NOT NULL DEFAULT 'pending',
    model_id VARCHAR(255) NOT NULL,
    mode VARCHAR(40) NOT NULL DEFAULT 'creative_description',
    snapshot_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_conversation_thread (thread_id),
    KEY idx_conversation_history (deleted_at, updated_at, id),
    CONSTRAINT ck_conversation_thread_state CHECK (
        thread_state IN ('pending', 'starting', 'ready', 'failed', 'unknown')),
    CONSTRAINT ck_conversation_ready CHECK (thread_state <> 'ready' OR thread_id IS NOT NULL),
    CONSTRAINT ck_conversation_mode CHECK (
        mode IN ('visual_inspiration', 'creative_description', 'video_idea')),
    CONSTRAINT ck_conversation_version CHECK (snapshot_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='对话及唯一 thread 绑定';

CREATE TABLE message (
    id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL,
    sequence BIGINT NOT NULL COMMENT '对话内稳定顺序',
    content TEXT NOT NULL,
    client_request_id VARCHAR(64) NOT NULL COMMENT '问答共享的发送编号',
    turn_id VARCHAR(255) NULL,
    state VARCHAR(20) NULL,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_sequence (conversation_id, sequence),
    UNIQUE KEY uk_message_request_role (conversation_id, client_request_id, role),
    UNIQUE KEY uk_message_turn (conversation_id, turn_id),
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversation (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_message_sequence CHECK (sequence > 0),
    CONSTRAINT ck_message_role CHECK (role IN ('user', 'assistant')),
    CONSTRAINT ck_message_execution CHECK (
        (role = 'user' AND state IS NULL AND turn_id IS NULL
            AND error_code IS NULL AND error_message IS NULL)
        OR (role = 'assistant' AND state IS NOT NULL AND state IN
            ('accepted', 'starting', 'running', 'stopping', 'completed', 'failed', 'interrupted', 'unknown')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='用户消息与 AI 回答执行状态';
