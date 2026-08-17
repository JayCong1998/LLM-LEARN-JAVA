// 将 Agent 成功轨迹行为测试放在 Agent 核心包中。
package com.jaycong.dodo.agent;

import com.jaycong.dodo.memory.InMemoryConversationMemory;
import com.jaycong.dodo.task.InMemoryTaskRegistry;
import com.jaycong.dodo.tool.AgentToolRegistry;
import com.jaycong.dodo.trace.SuccessfulAgentRun;
import com.jaycong.dodo.trace.SuccessfulAgentRunPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 验证新生产构造路径仅在成功后提交完整运行快照。
class ManualReactAgentRunTraceTest {

    @Test
    // 验证直接回答先持久化完整记录，再向 SSE 下游发布最终文本。
    void shouldPersistDirectAnswerBeforeEmittingText() {
        // 创建记录完整运行快照的内存端口替身。
        RecordingPersistence persistence = new RecordingPersistence();
        // 创建直接返回最终答案的确定性模型。
        ReactModelPort model = (messages, toolsEnabled) -> new AssistantMessage("最终回答");
        // 使用新的五参数生产路径组装 Agent。
        ManualReactAgent agent = new ManualReactAgent(
                // 注入直接回答模型。
                model,
                // 注入空工具目录。
                new AgentToolRegistry(List.of()),
                // 注入独立任务注册表。
                new InMemoryTaskRegistry(),
                // 注入只读取历史的内存记忆。
                new InMemoryConversationMemory(),
                // 注入记录型完整运行持久化端口。
                persistence);

        // 订阅成功请求并在 text 到达时检查已经完成持久化。
        StepVerifier.create(agent.stream("trace-conversation", "当前问题"))
                // text 前断言完整成功运行已经被一次性提交。
                .assertNext(event -> {
                    // 断言对外最终事件仍保持既有协议。
                    assertThat(event).isEqualTo(AgentStreamEvent.text("最终回答"));
                    // 断言只持久化一次成功运行。
                    assertThat(persistence.runs).singleElement().satisfies(run -> {
                        // 断言问答被完整提交。
                        assertThat(run.question()).isEqualTo("当前问题");
                        // 断言直接回答没有真实工具调用。
                        assertThat(run.executedToolNames()).isEmpty();
                        // 断言记录当前手写 ReAct 类型。
                        assertThat(run.agentType()).isEqualTo("manual-react");
                        // 断言首响应耗时不会为负。
                        assertThat(run.firstResponseTimeMillis()).isGreaterThanOrEqualTo(0L);
                        // 断言总耗时不会早于首响应。
                        assertThat(run.totalResponseTimeMillis()).isGreaterThanOrEqualTo(run.firstResponseTimeMillis());
                    });
                })
                // 断言最终文本后保留完成事件。
                .expectNext(AgentStreamEvent.complete())
                // 断言有限 SSE 流正常关闭。
                .verifyComplete();
    }

    // 使用内存列表观察生产 Agent 传入端口的完整成功运行。
    private static final class RecordingPersistence implements SuccessfulAgentRunPersistence {
        // 保存按实际调用顺序收到的完整运行。
        private final List<SuccessfulAgentRun> runs = new ArrayList<>();

        @Override
        // 记录一次成功持久化请求，不访问数据库以保持单元测试确定性。
        public void persist(SuccessfulAgentRun run) {
            // 保存领域快照供 SSE 事件到达时断言。
            runs.add(run);
        }
    }
}
