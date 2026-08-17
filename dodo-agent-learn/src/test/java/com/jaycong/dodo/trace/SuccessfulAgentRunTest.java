// 将完整成功运行的领域规则测试放在 trace 包中。
package com.jaycong.dodo.trace;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 验证持久化边界只会收到结构完整且不可变的成功运行快照。
class SuccessfulAgentRunTest {

    @Test
    // 验证运行记录保留字段值并冻结调用方提供的工具列表。
    void shouldKeepValidatedImmutableRunData() {
        // 创建可在构造后被外部修改的工具列表以验证防御性复制。
        List<String> toolNames = new ArrayList<>(List.of("weather"));
        // 创建包含完整成功运行数据的领域记录。
        SuccessfulAgentRun run = new SuccessfulAgentRun(
                // 设置稳定会话编号。
                "conversation-1",
                // 设置本次用户问题。
                "北京天气怎么样？",
                // 设置模型最终回答。
                "北京：晴，25℃。",
                // 传入可变工具列表。
                toolNames,
                // 设置首次可观察事件耗时。
                12L,
                // 设置最终答案就绪耗时。
                34L,
                // 设置当前 Agent 类型。
                "manual-react");
        // 在构造完成后修改原始列表，验证记录不会保留其引用。
        toolNames.add("calculator");

        // 断言记录保留原始会话编号。
        assertThat(run.conversationId()).isEqualTo("conversation-1");
        // 断言记录保留完整用户问题。
        assertThat(run.question()).isEqualTo("北京天气怎么样？");
        // 断言记录保留完整最终回答。
        assertThat(run.answer()).isEqualTo("北京：晴，25℃。");
        // 断言工具列表只保留构造时的首次快照。
        assertThat(run.executedToolNames()).containsExactly("weather");
        // 断言暴露出的工具列表不能被调用方修改。
        assertThatThrownBy(() -> run.executedToolNames().add("calculator"))
                // 断言不可变集合拒绝修改请求。
                .isInstanceOf(UnsupportedOperationException.class);
        // 断言首响应耗时被原样保留。
        assertThat(run.firstResponseTimeMillis()).isEqualTo(12L);
        // 断言总耗时被原样保留。
        assertThat(run.totalResponseTimeMillis()).isEqualTo(34L);
        // 断言 Agent 类型被原样保留。
        assertThat(run.agentType()).isEqualTo("manual-react");
    }

    @Test
    // 验证无法形成完整成功运行的输入在领域边界被明确拒绝。
    void shouldRejectBlankRequiredFieldsAndNegativeDurations() {
        // 断言空白会话编号不能成为持久化索引。
        assertThatThrownBy(() -> validRun(" ", "问题", "回答", List.of(), 0L, 0L, "manual-react"))
                // 断言拒绝类型稳定为参数异常。
                .isInstanceOf(IllegalArgumentException.class)
                // 断言失败原因准确指出会话编号。
                .hasMessage("会话编号不能为空");
        // 断言空白问题不能写入完整问答记录。
        assertThatThrownBy(() -> validRun("conversation", " ", "回答", List.of(), 0L, 0L, "manual-react"))
                // 断言失败原因准确指出用户问题。
                .hasMessage("用户问题不能为空");
        // 断言空白回答不能被误认为成功运行。
        assertThatThrownBy(() -> validRun("conversation", "问题", " ", List.of(), 0L, 0L, "manual-react"))
                // 断言失败原因准确指出最终回答。
                .hasMessage("最终回答不能为空");
        // 断言负首响应耗时不具有有效性能语义。
        assertThatThrownBy(() -> validRun("conversation", "问题", "回答", List.of(), -1L, 0L, "manual-react"))
                // 断言失败原因准确指出首响应耗时。
                .hasMessage("首响应耗时不能为负数");
        // 断言负总耗时不具有有效性能语义。
        assertThatThrownBy(() -> validRun("conversation", "问题", "回答", List.of(), 0L, -1L, "manual-react"))
                // 断言失败原因准确指出总耗时。
                .hasMessage("总响应耗时不能为负数");
        // 断言空白 Agent 类型不能用于后续过滤和展示。
        assertThatThrownBy(() -> validRun("conversation", "问题", "回答", List.of(), 0L, 0L, " "))
                // 断言失败原因准确指出 Agent 类型。
                .hasMessage("Agent 类型不能为空");
    }

    // 集中创建测试所需的记录，避免重复掩盖各断言的关注字段。
    private SuccessfulAgentRun validRun(
            // 接收需要验证的会话编号。
            String conversationId,
            // 接收需要验证的用户问题。
            String question,
            // 接收需要验证的最终回答。
            String answer,
            // 接收需要验证的工具名快照。
            List<String> toolNames,
            // 接收需要验证的首响应耗时。
            long firstResponseTimeMillis,
            // 接收需要验证的总耗时。
            long totalResponseTimeMillis,
            // 接收需要验证的 Agent 类型。
            String agentType) {
        // 创建领域记录，使每个测试分支都经过相同构造边界。
        return new SuccessfulAgentRun(
                // 传递会话编号。
                conversationId,
                // 传递用户问题。
                question,
                // 传递最终回答。
                answer,
                // 传递工具名快照。
                toolNames,
                // 传递首响应耗时。
                firstResponseTimeMillis,
                // 传递总耗时。
                totalResponseTimeMillis,
                // 传递 Agent 类型。
                agentType);
    }
}
