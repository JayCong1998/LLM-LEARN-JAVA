package com.jaycong.dodo.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 使用完整 Spring 上下文和真实 Mapper 验证 MyBatis-Plus 适配器，而不是验证模拟调用。
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class MySqlConversationMemoryTest {

    // 注入尚未实现的 Mapper，使测试从 MyBatis-Plus 的真实数据库边界定义预期 API。
    @Autowired
    private AiSessionMapper aiSessionMapper;

    // 每个测试前通过 Mapper 清空初始化脚本创建的表，确保用例之间不残留会话记录。
    @BeforeEach
    void clearTable() {
        // 删除所有测试记录，使每个用例从完全相同的空表状态开始。
        aiSessionMapper.delete(null);
    }

    // 明确适配器应通过 Mapper 写入并按时间正序读回同一会话的完整问答。
    @Test
    void shouldAppendAndReadTurnsInChronologicalOrder() {
        // 使用 Mapper 组装尚未实现的会话记忆适配器。
        ConversationMemory memory = new MySqlConversationMemory(aiSessionMapper);
        // 追加该会话的第一轮成功问答。
        memory.append("conversation", new ConversationTurn("问题一", "回答一"));
        // 追加该会话的第二轮成功问答。
        memory.append("conversation", new ConversationTurn("问题二", "回答二"));
        // 断言读取结果保持旧到新的顺序，供提示词自然回放。
        assertThat(memory.get("conversation")).containsExactly(
                new ConversationTurn("问题一", "回答一"),
                new ConversationTurn("问题二", "回答二"));
    }

    // 明确适配器只应把同一会话最近五轮带回模型上下文。
    @Test
    void shouldKeepOnlyMostRecentFiveTurnsWhenReading() {
        // 使用 Mapper 组装尚未实现的会话记忆适配器。
        ConversationMemory memory = new MySqlConversationMemory(aiSessionMapper);
        // 连续保存六轮历史以制造一轮超出窗口的数据。
        for (int index = 1; index <= 6; index++) {
            // 写入每轮可区分内容，便于确认最旧轮次被排除。
            memory.append("conversation", new ConversationTurn("问题" + index, "回答" + index));
        }
        // 断言只返回第 2 至第 6 轮，并且结果已经恢复时间正序。
        assertThat(memory.get("conversation")).containsExactly(
                new ConversationTurn("问题2", "回答2"),
                new ConversationTurn("问题3", "回答3"),
                new ConversationTurn("问题4", "回答4"),
                new ConversationTurn("问题5", "回答5"),
                new ConversationTurn("问题6", "回答6"));
    }

    // 明确创建时间相同的记录必须以自增主键稳定排序，避免数据库返回不确定结果。
    @Test
    void shouldUseIdAsStableOrderWhenCreateTimesAreEqual() {
        // 使用 Mapper 组装尚未实现的会话记忆适配器。
        ConversationMemory memory = new MySqlConversationMemory(aiSessionMapper);
        // 创建完全相同的时间值，使排序只能依赖数据库分配的 id。
        LocalDateTime sameTime = LocalDateTime.of(2026, 8, 17, 13, 30);
        // 通过 Mapper 插入第一条固定时间记录。
        aiSessionMapper.insert(session("conversation", "问题一", "回答一", sameTime));
        // 通过 Mapper 插入第二条固定时间记录，数据库会分配更大的 id。
        aiSessionMapper.insert(session("conversation", "问题二", "回答二", sameTime));
        // 断言适配器返回较小 id 对应的旧记录在前。
        assertThat(memory.get("conversation")).containsExactly(
                new ConversationTurn("问题一", "回答一"),
                new ConversationTurn("问题二", "回答二"));
    }

    // 明确清空操作只影响目标会话，并准确返回是否删除了历史。
    @Test
    void shouldClearOnlyRequestedConversationAndReportWhetherHistoryExisted() {
        // 使用 Mapper 组装尚未实现的会话记忆适配器。
        ConversationMemory memory = new MySqlConversationMemory(aiSessionMapper);
        // 为目标会话保存一轮历史。
        memory.append("conversation", new ConversationTurn("问题", "回答"));
        // 为其他会话保存独立历史，证明删除条件必须限定 sessionId。
        memory.append("other-conversation", new ConversationTurn("其他问题", "其他回答"));
        // 断言首次清空删除了目标会话的记录。
        assertThat(memory.clear("conversation")).isTrue();
        // 断言目标会话已没有历史。
        assertThat(memory.get("conversation")).isEmpty();
        // 断言其他会话记录保持不变。
        assertThat(memory.get("other-conversation")).containsExactly(new ConversationTurn("其他问题", "其他回答"));
        // 断言重复清空时不存在可删除记录。
        assertThat(memory.clear("conversation")).isFalse();
    }

    // 明确 Mapper 适配器保留内存实现已有的输入校验边界。
    @Test
    void shouldRejectInvalidConversationInput() {
        // 使用 Mapper 组装尚未实现的会话记忆适配器。
        ConversationMemory memory = new MySqlConversationMemory(aiSessionMapper);
        // 断言纯空白会话编号不能用于写入。
        assertThatThrownBy(() -> memory.append(" ", new ConversationTurn("问题", "回答")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("会话编号不能为空");
        // 断言空引用会话编号不能用于读取。
        assertThatThrownBy(() -> memory.get(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("会话编号不能为空");
        // 断言空轮次不能被保存。
        assertThatThrownBy(() -> memory.append("conversation", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("会话轮次不能为空");
    }

    // 明确完整实体映射可保留后续 Agent 运行轨迹阶段需要的所有表字段。
    @Test
    void shouldMapEveryAiSessionColumn() {
        // 创建包含全部表字段的实体，以验证属性名与列名映射。
        AiSessionEntity expected = session("full-column-session", "完整问题", "完整回答", LocalDateTime.of(2026, 8, 17, 14, 0));
        // 设置工具名称字段以验证普通 varchar 映射。
        expected.setTools("weather,calculator");
        // 设置首响应耗时字段以验证数值映射。
        expected.setFirstResponseTime(120L);
        // 设置总响应耗时字段以验证数值映射。
        expected.setTotalResponseTime(360L);
        // 设置更新时间字段以验证时间映射。
        expected.setUpdateTime(LocalDateTime.of(2026, 8, 17, 14, 1));
        // 设置 reference 列以验证显式字段注解。
        expected.setReference("https://example.com/reference");
        // 设置 Agent 类型字段以验证下划线列名映射。
        expected.setAgentType("manual-react");
        // 设置长文本思考过程字段以验证 longtext 映射。
        expected.setThinking("先分析，再回答。");
        // 设置 fileid 列以验证非标准下划线字段的显式注解。
        expected.setFileId("file-001");
        // 设置推荐内容字段以验证普通 varchar 映射。
        expected.setRecommend("推荐内容");
        // 使用 Mapper 写入完整实体，使数据库分配自增主键。
        aiSessionMapper.insert(expected);
        // 按主键读取刚刚插入的实体，验证数据库到 Java 的反向映射。
        AiSessionEntity actual = aiSessionMapper.selectById(expected.getId());
        // 断言主键已由数据库回填。
        assertThat(actual.getId()).isEqualTo(expected.getId());
        // 断言会话编号字段完整映射。
        assertThat(actual.getSessionId()).isEqualTo(expected.getSessionId());
        // 断言问题字段完整映射。
        assertThat(actual.getQuestion()).isEqualTo(expected.getQuestion());
        // 断言回答字段完整映射。
        assertThat(actual.getAnswer()).isEqualTo(expected.getAnswer());
        // 断言工具字段完整映射。
        assertThat(actual.getTools()).isEqualTo(expected.getTools());
        // 断言首响应耗时字段完整映射。
        assertThat(actual.getFirstResponseTime()).isEqualTo(expected.getFirstResponseTime());
        // 断言总响应耗时字段完整映射。
        assertThat(actual.getTotalResponseTime()).isEqualTo(expected.getTotalResponseTime());
        // 断言创建时间字段完整映射。
        assertThat(actual.getCreateTime()).isEqualTo(expected.getCreateTime());
        // 断言更新时间字段完整映射。
        assertThat(actual.getUpdateTime()).isEqualTo(expected.getUpdateTime());
        // 断言 reference 字段完整映射。
        assertThat(actual.getReference()).isEqualTo(expected.getReference());
        // 断言 Agent 类型字段完整映射。
        assertThat(actual.getAgentType()).isEqualTo(expected.getAgentType());
        // 断言思考过程字段完整映射。
        assertThat(actual.getThinking()).isEqualTo(expected.getThinking());
        // 断言文件编号字段完整映射。
        assertThat(actual.getFileId()).isEqualTo(expected.getFileId());
        // 断言推荐内容字段完整映射。
        assertThat(actual.getRecommend()).isEqualTo(expected.getRecommend());
    }

    // 集中创建适配排序测试和完整映射测试的会话实体。
    private AiSessionEntity session(String sessionId, String question, String answer, LocalDateTime createTime) {
        // 创建空实体，以逐项设置映射字段。
        AiSessionEntity entity = new AiSessionEntity();
        // 设置会话编号。
        entity.setSessionId(sessionId);
        // 设置用户问题。
        entity.setQuestion(question);
        // 设置助手回答。
        entity.setAnswer(answer);
        // 设置测试精确控制的创建时间。
        entity.setCreateTime(createTime);
        // 返回准备完成的实体。
        return entity;
    }
}
