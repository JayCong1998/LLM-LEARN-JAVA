// 将 MySQL 持久化适配器放在记忆包中，使 Agent 继续只依赖 ConversationMemory 抽象。
package com.jaycong.dodo.memory;

import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// 将此实现注册为 Spring Bean，并在同时存在内存实现时优先用于真实应用运行。
@Component
@Primary
// 使用已有 ai_session 表持久化成功问答，使会话记忆在应用重启后仍可恢复。
public class MySqlConversationMemory implements ConversationMemory {

    // 查询指定会话最新五条记录；倒序读取可让数据库利用会话和时间索引快速截取窗口。
    private static final String SELECT_RECENT_TURNS = """
            SELECT question, answer
            FROM ai_session
            WHERE session_id = ?
            ORDER BY create_time DESC, id DESC
            LIMIT 5
            """;
    // 仅写入当前阶段需要的会话编号、用户问题和最终回答，其他已有列留给后续阶段扩展。
    private static final String INSERT_TURN = """
            INSERT INTO ai_session (session_id, question, answer)
            VALUES (?, ?, ?)
            """;
    // 按会话编号删除全部历史，使清空语义与内存窗口实现一致。
    private static final String DELETE_TURNS = "DELETE FROM ai_session WHERE session_id = ?";
    // 保存 Spring JDBC 模板，以复用连接池、参数绑定和异常转换能力。
    private final JdbcTemplate jdbcTemplate;

    // 通过构造器注入 JDBC 模板，使适配器可在隔离 H2 数据库中进行真实 SQL 测试。
    public MySqlConversationMemory(JdbcTemplate jdbcTemplate) {
        // 拒绝缺少数据库执行器的错误装配，避免运行时空指针隐藏配置问题。
        if (jdbcTemplate == null) {
            // 使用参数异常明确指出持久化适配器缺少必要依赖。
            throw new IllegalArgumentException("JdbcTemplate 不能为空");
        }
        // 保存已校验的 JDBC 模板供三个端口方法执行 SQL。
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    // 查询时返回与内存实现相同的不可变时间正序快照，供 Agent 按轮次回放提示词。
    public List<ConversationTurn> get(String conversationId) {
        // 在访问数据库前统一拒绝无法作为稳定键的会话编号。
        validateConversationId(conversationId);
        // 数据库按最新到最旧返回最多五条，避免把完整历史全部加载到 JVM。
        List<ConversationTurn> newestFirstTurns = jdbcTemplate.query(
                // 使用参数化 SQL 防止会话编号被拼接进查询文本。
                SELECT_RECENT_TURNS,
                // 将每行的用户问题和助手回答还原为领域中的完整对话轮次。
                (resultSet, rowNumber) -> new ConversationTurn(resultSet.getString("question"), resultSet.getString("answer")),
                // 绑定当前会话编号作为唯一查询条件。
                conversationId);
        // 复制可变查询结果，防止反转操作修改 JDBC 框架返回的列表实现。
        List<ConversationTurn> chronologicalTurns = new ArrayList<>(newestFirstTurns);
        // 将最新优先恢复为最旧优先，保持提示词中的对话时间线自然连续。
        Collections.reverse(chronologicalTurns);
        // 返回不可变快照，避免调用方意外修改本次读取的历史内容。
        return List.copyOf(chronologicalTurns);
    }

    @Override
    // 只在 Agent 已成功产出最终回答后由调用方追加一整轮问答。
    public void append(String conversationId, ConversationTurn turn) {
        // 在写入数据库前统一拒绝无效会话编号。
        validateConversationId(conversationId);
        // 空轮次没有完整的问答对，不能形成可回放的持久化记录。
        if (turn == null) {
            // 以参数异常向调用方说明缺少必须保存的会话轮次。
            throw new IllegalArgumentException("会话轮次不能为空");
        }
        // 使用预编译参数写入完整问答，避免字符串拼接和 SQL 注入风险。
        jdbcTemplate.update(INSERT_TURN, conversationId, turn.userContent(), turn.assistantContent());
    }

    @Override
    // 删除指定会话的所有历史，并将受影响行数转换为端口约定的布尔结果。
    public boolean clear(String conversationId) {
        // 在执行删除前统一拒绝无效会话编号。
        validateConversationId(conversationId);
        // 非零受影响行数表示调用时确实存在并清除了至少一轮历史。
        return jdbcTemplate.update(DELETE_TURNS, conversationId) > 0;
    }

    // 集中维护三个端口方法共享的会话编号约束，保持与内存实现完全一致。
    private void validateConversationId(String conversationId) {
        // 空引用、空字符串和纯空白字符串都不能作为数据库会话键。
        if (conversationId == null || conversationId.isBlank()) {
            // 阻止无效键进入数据库查询或写入边界。
            throw new IllegalArgumentException("会话编号不能为空");
        }
    }
}
