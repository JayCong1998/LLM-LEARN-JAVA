// 将真实 MyBatis-Plus 运行轨迹适配器测试放在 trace 包中。
package com.jaycong.dodo.trace;

import com.jaycong.dodo.memory.AiSessionEntity;
import com.jaycong.dodo.memory.AiSessionMapper;
import com.jaycong.dodo.memory.ConversationTurn;
import com.jaycong.dodo.memory.MySqlConversationMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 使用完整 Spring 上下文和 H2 表验证 Mapper 真实写入，不使用模拟数据访问层。
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class MyBatisSuccessfulAgentRunPersistenceTest {

    @Autowired
    // 注入真实 Mapper 以读取持久化适配器写入的表记录。
    private AiSessionMapper aiSessionMapper;

    @Autowired
    // 注入尚未实现的完整成功运行写入端口。
    private SuccessfulAgentRunPersistence persistence;

    @BeforeEach
    // 清空共享 H2 表，保证每个测试只观察本用例写入的一条记录。
    void clearTable() {
        // 删除所有 ai_session 行，防止完整上下文测试之间遗留数据。
        aiSessionMapper.delete(null);
    }

    @Test
    // 验证一次成功运行会在同一行完整写入问答、工具、耗时和 Agent 类型。
    void shouldPersistCompleteRunWithoutInternalThinkingFields() {
        // 创建带两个真实工具名称的完整成功运行。
        SuccessfulAgentRun run = new SuccessfulAgentRun(
                // 设置本次运行会话编号。
                "trace-session",
                // 设置本次用户问题。
                "先查询北京天气，再计算二加三",
                // 设置本次最终回答。
                "北京晴，计算结果为五。",
                // 设置按真实执行顺序去重后的工具名。
                List.of("weather", "calculator"),
                // 设置首个可观察事件耗时。
                18L,
                // 设置最终答案就绪耗时。
                52L,
                // 设置当前手写 ReAct 类型。
                "manual-react");

        // 调用持久化端口写入完整运行。
        persistence.persist(run);

        // 读取刚刚写入的唯一表记录。
        AiSessionEntity row = aiSessionMapper.selectList(null).getFirst();
        // 断言会话编号与运行快照一致。
        assertThat(row.getSessionId()).isEqualTo("trace-session");
        // 断言用户问题与运行快照一致。
        assertThat(row.getQuestion()).isEqualTo("先查询北京天气，再计算二加三");
        // 断言最终回答与运行快照一致。
        assertThat(row.getAnswer()).isEqualTo("北京晴，计算结果为五。");
        // 断言工具名称保存为稳定 JSON 数组而非逗号分隔文本。
        assertThat(row.getTools()).isEqualTo("[\"weather\",\"calculator\"]");
        // 断言首响应耗时保存为毫秒数。
        assertThat(row.getFirstResponseTime()).isEqualTo(18L);
        // 断言总响应耗时保存为毫秒数。
        assertThat(row.getTotalResponseTime()).isEqualTo(52L);
        // 断言 Agent 类型正确写入。
        assertThat(row.getAgentType()).isEqualTo("manual-react");
        // 断言本阶段不写入隐藏推理字段。
        assertThat(row.getThinking()).isNull();
        // 断言本阶段不写入未来引用字段。
        assertThat(row.getReference()).isNull();
        // 断言本阶段不写入未来文件字段。
        assertThat(row.getFileId()).isNull();
        // 断言本阶段不写入未来推荐字段。
        assertThat(row.getRecommend()).isNull();
        // 使用既有记忆适配器读取同一行，验证模型上下文只得到问答。
        assertThat(new MySqlConversationMemory(aiSessionMapper).get("trace-session"))
                // 断言轨迹字段没有混入 ConversationTurn。
                .containsExactly(new ConversationTurn("先查询北京天气，再计算二加三", "北京晴，计算结果为五。"));
    }

    @Test
    // 验证模型直接回答时以空数组而不是 null 表示没有实际工具调用。
    void shouldPersistEmptyToolsAsJsonArray() {
        // 创建不包含工具调用的完整成功运行。
        SuccessfulAgentRun run = new SuccessfulAgentRun(
                // 设置直接回答会话编号。
                "direct-session",
                // 设置直接回答问题。
                "什么是 Agent？",
                // 设置直接回答内容。
                "Agent 是能够感知、决策并行动的系统。",
                // 用空列表表达没有实际工具执行。
                List.of(),
                // 设置首个 text 前的耗时。
                7L,
                // 设置最终回答就绪耗时。
                7L,
                // 设置当前 Agent 类型。
                "manual-react");

        // 写入直接回答运行。
        persistence.persist(run);

        // 读取唯一记录并断言空轨迹采用稳定 JSON 表示。
        assertThat(aiSessionMapper.selectList(null).getFirst().getTools()).isEqualTo("[]");
    }
}
