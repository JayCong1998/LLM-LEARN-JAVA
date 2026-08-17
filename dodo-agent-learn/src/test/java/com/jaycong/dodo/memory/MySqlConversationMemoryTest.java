package com.jaycong.dodo.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 使用真实内存数据库验证 MySQL 适配器的 SQL 语义，而非只验证模拟对象的调用次数。
@JdbcTest
class MySqlConversationMemoryTest {

    // 注入测试切片自动配置的 JdbcTemplate，用它建立与生产表相同语义的最小表结构。
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 每个测试前删除旧表并重建，确保内存数据库中不会残留其他用例的会话记录。
    @BeforeEach
    void setUpSchema() {
        // 防御性删除上一个用例创建的表，使每个用例从空表开始。
        jdbcTemplate.execute("DROP TABLE IF EXISTS ai_session");
        // 创建适配器实际读写所需的列，并保留生产表相同的自增主键与创建时间语义。
        jdbcTemplate.execute("""
                CREATE TABLE ai_session (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    session_id VARCHAR(255) NOT NULL,
                    question CLOB,
                    answer CLOB,
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    // 明确新适配器应把同一会话的完整问答按写入时间正序读回。
    @Test
    void shouldAppendAndReadTurnsInChronologicalOrder() {
        // 构造尚未实现的目标适配器，以测试驱动其公开 API。
        ConversationMemory memory = new MySqlConversationMemory(jdbcTemplate);
        // 追加该会话的第一轮成功问答。
        memory.append("conversation", new ConversationTurn("问题一", "回答一"));
        // 追加该会话的第二轮成功问答。
        memory.append("conversation", new ConversationTurn("问题二", "回答二"));
        // 断言读取结果仍以旧到新的时间顺序供提示词回放。
        assertThat(memory.get("conversation")).containsExactly(
                new ConversationTurn("问题一", "回答一"),
                new ConversationTurn("问题二", "回答二"));
    }

    // 明确数据库虽然保存完整历史，但每次读取只能向模型暴露最近五轮。
    @Test
    void shouldKeepOnlyMostRecentFiveTurnsWhenReading() {
        // 构造尚未实现的目标适配器，以测试窗口裁剪的读取语义。
        ConversationMemory memory = new MySqlConversationMemory(jdbcTemplate);
        // 连续追加六轮，以制造一轮超出上下文窗口的历史。
        for (int index = 1; index <= 6; index++) {
            // 每轮使用可区分的内容，便于确认最旧记录被排除。
            memory.append("conversation", new ConversationTurn("问题" + index, "回答" + index));
        }
        // 断言数据库读取结果只保留第 2 至第 6 轮且顺序正确。
        assertThat(memory.get("conversation")).containsExactly(
                new ConversationTurn("问题2", "回答2"),
                new ConversationTurn("问题3", "回答3"),
                new ConversationTurn("问题4", "回答4"),
                new ConversationTurn("问题5", "回答5"),
                new ConversationTurn("问题6", "回答6"));
    }

    // 明确创建时间相同时使用 id 作为稳定次序，避免数据库返回不确定的提示词历史。
    @Test
    void shouldUseIdAsStableOrderWhenCreateTimesAreEqual() {
        // 构造尚未实现的目标适配器，以测试查询排序规则。
        ConversationMemory memory = new MySqlConversationMemory(jdbcTemplate);
        // 创建完全相同的时间戳，使排序只能依赖自增主键。
        Timestamp sameTime = Timestamp.valueOf(LocalDateTime.of(2026, 8, 17, 13, 30));
        // 直接插入第一行来精确控制生产表中的 create_time 值。
        jdbcTemplate.update("INSERT INTO ai_session (session_id, question, answer, create_time) VALUES (?, ?, ?, ?)", "conversation", "问题一", "回答一", sameTime);
        // 直接插入第二行，数据库会为它分配更大的 id。
        jdbcTemplate.update("INSERT INTO ai_session (session_id, question, answer, create_time) VALUES (?, ?, ?, ?)", "conversation", "问题二", "回答二", sameTime);
        // 断言倒序查询再反转后仍以较小 id 的旧记录开始。
        assertThat(memory.get("conversation")).containsExactly(
                new ConversationTurn("问题一", "回答一"),
                new ConversationTurn("问题二", "回答二"));
    }

    // 明确清空会话应删除该会话全部记录，并用返回值表达是否确实发生删除。
    @Test
    void shouldClearOnlyRequestedConversationAndReportWhetherHistoryExisted() {
        // 构造尚未实现的目标适配器，以测试删除边界。
        ConversationMemory memory = new MySqlConversationMemory(jdbcTemplate);
        // 为目标会话保存一轮历史。
        memory.append("conversation", new ConversationTurn("问题", "回答"));
        // 为另一个会话保存独立历史，证明删除条件必须带 session_id。
        memory.append("other-conversation", new ConversationTurn("其他问题", "其他回答"));
        // 断言首次清空确实删除了目标会话的记录。
        assertThat(memory.clear("conversation")).isTrue();
        // 断言目标会话读取不到已删除历史。
        assertThat(memory.get("conversation")).isEmpty();
        // 断言其他会话数据没有被误删。
        assertThat(memory.get("other-conversation")).containsExactly(new ConversationTurn("其他问题", "其他回答"));
        // 断言重复清空没有记录时返回 false。
        assertThat(memory.clear("conversation")).isFalse();
    }

    // 明确数据库边界与现有内存实现使用相同的会话编号输入约束。
    @Test
    void shouldRejectBlankConversationId() {
        // 构造尚未实现的目标适配器，以测试公共方法的统一校验。
        ConversationMemory memory = new MySqlConversationMemory(jdbcTemplate);
        // 断言追加操作拒绝纯空白会话编号。
        assertThatThrownBy(() -> memory.append(" ", new ConversationTurn("问题", "回答")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("会话编号不能为空");
        // 断言读取操作拒绝空引用会话编号。
        assertThatThrownBy(() -> memory.get(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("会话编号不能为空");
        // 断言清空操作拒绝纯空白会话编号。
        assertThatThrownBy(() -> memory.clear("\t\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("会话编号不能为空");
    }
}
