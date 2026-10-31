package com.example.codexdemo;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/** 在本地 Docker MySQL 上验证真实迁移与约束；每个测试的数据自动回滚。 */
@SpringBootTest
@Transactional
class DatabaseIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    private String conversationId;

    @BeforeEach
    void createConversation() {
        conversationId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO conversation (id, model_id) VALUES (?, ?)",
                conversationId, "test-model");
    }

    @Test
    void flywayCreatesExpectedColumnsAndTracksMigration() {
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1", Integer.class));
        assertEquals(10, columnCount("conversation"));
        assertEquals(12, columnCount("message"));
    }

    @Test
    void storesChineseEmojiAndPairedMessagesWithSameRequestId() {
        insertMessage("user", 1, "send-001", "雨夜 🌧️\n 保留空格");
        insertMessage("assistant", 2, "send-001", "暖色城市 🌆");
        assertEquals("雨夜 🌧️\n 保留空格", jdbc.queryForObject(
                "SELECT content FROM message WHERE conversation_id = ? AND role = 'user'",
                String.class, conversationId));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM message WHERE conversation_id = ? AND client_request_id = ?",
                Integer.class, conversationId, "send-001"));
    }

    @Test
    void rejectsDuplicateRequestRoleButKeepsRequestIdsCaseSensitive() {
        insertMessage("user", 1, "send-A", "first");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertMessage("user", 2, "send-A", "duplicate"));
        insertMessage("user", 2, "send-a", "different request");
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM message WHERE conversation_id = ?", Integer.class, conversationId));
    }

    @Test
    void rejectsOrphanMessageAndInvalidAssistantState() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO message (id, conversation_id, role, sequence, content, client_request_id) "
                        + "VALUES (?, ?, 'user', 1, '', ?)",
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString()));
        // MySQL CHECK 违反返回 HY000/3819，Spring 不将它归类为完整性异常。
        var exception = assertThrows(UncategorizedSQLException.class, () -> jdbc.update(
                "INSERT INTO message (id, conversation_id, role, sequence, content, client_request_id) "
                        + "VALUES (?, ?, 'assistant', 1, '', ?)",
                UUID.randomUUID().toString(), conversationId, UUID.randomUUID().toString()));
        assertEquals(3819, exception.getSQLException().getErrorCode());
    }

    @Test
    void rejectsDuplicateSequenceAndThreadBinding() {
        insertMessage("user", 1, "request-1", "first");
        assertThrows(DataIntegrityViolationException.class,
                () -> insertMessage("user", 1, "request-2", "same sequence"));
        String threadId = UUID.randomUUID().toString();
        jdbc.update("UPDATE conversation SET thread_id = ?, thread_state = 'ready' WHERE id = ?",
                threadId, conversationId);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO conversation (id, model_id, thread_id) VALUES (?, ?, ?)",
                UUID.randomUUID().toString(), "test-model", threadId));
    }

    @Test
    void softDeletePreservesMessagesAndCannotCascadePhysicalDeletion() {
        insertMessage("user", 1, "send-001", "保留历史");
        jdbc.update("UPDATE conversation SET deleted_at = UTC_TIMESTAMP(6), "
                        + "updated_at = UTC_TIMESTAMP(6), snapshot_version = snapshot_version + 1 WHERE id = ?",
                conversationId);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation WHERE id = ? AND deleted_at IS NULL",
                Integer.class, conversationId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM message WHERE conversation_id = ?", Integer.class, conversationId));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM conversation WHERE id = ?", conversationId));
    }

    private int columnCount(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = ?", Integer.class, table);
    }

    private void insertMessage(String role, long sequence, String requestId, String content) {
        jdbc.update("INSERT INTO message (id, conversation_id, role, sequence, content, client_request_id, state) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), conversationId, role, sequence, content, requestId,
                "assistant".equals(role) ? "accepted" : null);
    }
}
