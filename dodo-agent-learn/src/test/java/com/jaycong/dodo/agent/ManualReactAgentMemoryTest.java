// 将测试放在 Agent 核心包中，直接验证跨请求记忆与单次 ReAct 上下文的衔接。
package com.jaycong.dodo.agent;

import com.jaycong.dodo.memory.ConversationMemory;
import com.jaycong.dodo.memory.ConversationTurn;
import com.jaycong.dodo.task.InMemoryTaskRegistry;
import com.jaycong.dodo.tool.AgentToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// 验证 Agent 会在模型决策前加载历史快照，并将记忆边界异常转换成 SSE 终止协议。
class ManualReactAgentMemoryTest {

    @Test
    // 验证最终回答只有先成功写入记忆后才能作为 text 事件发送。
    void shouldAppendCompletedTurnBeforeEmittingFinalText() {
        // 创建可记录追加内容的记忆假实现。
        RecordingConversationMemory memory = new RecordingConversationMemory(List.of());
        // 创建直接返回最终答案的模型端口。
        CapturingModel model = new CapturingModel(new AssistantMessage("最终回答"));
        // 组装不含工具的 Agent，使测试聚焦正常终止顺序。
        ManualReactAgent agent = new ManualReactAgent(
                // 注入直接回答的模型。
                model,
                // 注入空工具注册表。
                new AgentToolRegistry(List.of()),
                // 注入独立任务注册表。
                new InMemoryTaskRegistry(),
                // 注入能够观察 append 调用的记忆端口。
                memory);

        // 订阅一次可以正常完成的 Agent 请求。
        StepVerifier.create(agent.stream("conversation-save", "当前问题"))
                // 在收到 text 的时刻检查记忆已经先完成提交。
                .assertNext(event -> {
                    // 断言第一个事件确实是最终文本。
                    assertThat(event).isEqualTo(AgentStreamEvent.text("最终回答"));
                    // 断言发送文本前已经保存当前问题和最终答案组成的完整轮次。
                    assertThat(memory.appendedTurns).containsExactly(new ConversationTurn("当前问题", "最终回答"));
                })
                // 断言保存和文本之后发送统一完成事件。
                .expectNext(AgentStreamEvent.complete())
                // 断言正常关闭事件流。
                .verifyComplete();
    }

    @Test
    // 验证模型失败或空白回答都不会污染跨请求记忆。
    void shouldNotAppendTurnWhenModelFailsOrReturnsBlankAnswer() {
        // 创建记录两个失败场景追加行为的共享记忆假实现。
        RecordingConversationMemory memory = new RecordingConversationMemory(List.of());
        // 创建会在决策边界抛出异常的模型端口。
        ReactModelPort failingModel = (messages, toolsEnabled) -> {
            // 模拟模型服务不可用，使运行进入普通错误终止路径。
            throw new IllegalStateException("model unavailable");
        };
        // 组装模型异常场景 Agent。
        ManualReactAgent failingAgent = new ManualReactAgent(
                // 注入失败模型端口。
                failingModel,
                // 注入空工具注册表。
                new AgentToolRegistry(List.of()),
                // 注入独立任务注册表。
                new InMemoryTaskRegistry(),
                // 注入记录型记忆端口。
                memory);
        // 组装返回空白答案的 Agent。
        ManualReactAgent blankAgent = new ManualReactAgent(
                // 注入返回无效空白答案的模型端口。
                new CapturingModel(new AssistantMessage("  ")),
                // 注入空工具注册表。
                new AgentToolRegistry(List.of()),
                // 注入另一独立任务注册表。
                new InMemoryTaskRegistry(),
                // 复用记录型记忆以统一检查没有任何提交。
                memory);

        // 执行模型异常场景并等待错误协议完整结束。
        StepVerifier.create(failingAgent.stream("conversation-model-error", "问题一"))
                // 断言模型异常被转换为错误事件。
                .expectNext(AgentStreamEvent.error("model unavailable"))
                // 断言错误后发送完成事件。
                .expectNext(AgentStreamEvent.complete())
                // 断言失败流正常关闭。
                .verifyComplete();
        // 执行空白最终回答场景并等待错误协议完整结束。
        StepVerifier.create(blankAgent.stream("conversation-blank", "问题二"))
                // 断言空白回答被拒绝为成功结果。
                .expectNext(AgentStreamEvent.error("模型未返回最终答案"))
                // 断言无效回答后发送完成事件。
                .expectNext(AgentStreamEvent.complete())
                // 断言失败流正常关闭。
                .verifyComplete();

        // 断言两种失败路径都没有保存不完整轮次。
        assertThat(memory.appendedTurns).isEmpty();
    }

    @Test
    // 验证记忆保存失败时不向客户端展示尚未持久化的最终答案。
    void shouldEmitErrorWithoutTextWhenMemoryAppendFails() {
        // 创建读取正常但追加时失败的记忆端口。
        ConversationMemory memory = new AppendFailingConversationMemory();
        // 创建可以正常生成答案的模型端口。
        CapturingModel model = new CapturingModel(new AssistantMessage("不能发送的回答"));
        // 创建独立任务注册表以验证保存异常后的释放行为。
        InMemoryTaskRegistry tasks = new InMemoryTaskRegistry();
        // 组装记忆保存失败场景 Agent。
        ManualReactAgent agent = new ManualReactAgent(
                // 注入正常回答模型。
                model,
                // 注入空工具注册表。
                new AgentToolRegistry(List.of()),
                // 注入任务注册表。
                tasks,
                // 注入追加失败的记忆端口。
                memory);

        // 订阅会在正常终止阶段遭遇保存失败的请求。
        StepVerifier.create(agent.stream("conversation-save-error", "当前问题"))
                // 断言第一条终止事件明确说明记忆保存失败。
                .expectNext(AgentStreamEvent.error("运行轨迹保存失败：memory write unavailable"))
                // 断言错误后发送统一完成事件，期间不得出现 text。
                .expectNext(AgentStreamEvent.complete())
                // 断言事件流正常关闭而不是抛出 Reactor 异常。
                .verifyComplete();

        // 断言保存失败后任务注册表仍然释放会话编号。
        assertThat(tasks.hasRunningTask("conversation-save-error")).isFalse();
    }

    @Test
    // 验证主动取消与同会话并发拒绝都不会被误保存为成功轮次。
    void shouldNotAppendTurnForCancellationOrConcurrentRejection() throws Exception {
        // 创建会阻塞在模型调用中的端口，使测试可以稳定制造运行中状态。
        BlockingModel model = new BlockingModel();
        // 创建记录追加行为的记忆端口。
        RecordingConversationMemory memory = new RecordingConversationMemory(List.of());
        // 创建任务注册表供测试触发与生产停止接口相同的取消动作。
        InMemoryTaskRegistry tasks = new InMemoryTaskRegistry();
        // 组装阻塞模型场景 Agent。
        ManualReactAgent agent = new ManualReactAgent(
                // 注入阻塞模型端口。
                model,
                // 注入空工具注册表。
                new AgentToolRegistry(List.of()),
                // 注入可直接取消的任务注册表。
                tasks,
                // 注入记录型记忆端口。
                memory);
        // 异步订阅首个请求，使其占用会话并阻塞在模型决策。
        CompletableFuture<List<AgentStreamEvent>> firstRun = agent.stream("conversation-running", "首个问题")
                // 收集取消后产生的有限事件序列。
                .collectList()
                // 转换成 Future，避免当前测试线程被阻塞模型占用。
                .toFuture();
        // 等待模型已经进入阻塞调用，确保并发请求发生在真实运行期间。
        assertThat(model.entered.await(2, TimeUnit.SECONDS)).isTrue();

        // 使用相同会话编号发起第二个请求。
        StepVerifier.create(agent.stream("conversation-running", "并发问题"))
                // 断言并发请求在调用模型前被拒绝。
                .expectNext(AgentStreamEvent.error("conversation is already running"))
                // 断言拒绝路径仍发送完成事件。
                .expectNext(AgentStreamEvent.complete())
                // 断言并发拒绝流立即关闭。
                .verifyComplete();
        // 通过任务注册表取消仍在运行的首个请求。
        assertThat(tasks.cancel("conversation-running")).isTrue();
        // 断言首个请求收到稳定的取消终止协议。
        assertThat(firstRun.get(2, TimeUnit.SECONDS)).containsExactly(
                // 取消原因作为错误事件发送。
                AgentStreamEvent.error("request cancelled"),
                // 取消之后发送统一完成事件。
                AgentStreamEvent.complete());
        // 断言并发拒绝和主动取消均未追加任何成功轮次。
        assertThat(memory.appendedTurns).isEmpty();
    }

    @Test
    // 验证跨请求历史按角色和时间顺序插入系统消息与当前问题之间。
    void shouldLoadConversationTurnsBeforeCurrentUserMessage() {
        // 创建两轮已经在先前 HTTP 请求中成功完成的历史。
        List<ConversationTurn> history = List.of(
                // 保存第一轮用户身份信息及助手确认。
                new ConversationTurn("我叫小明", "你好，小明。"),
                // 保存第二轮偏好信息及助手确认。
                new ConversationTurn("我喜欢 Java", "我记住了你喜欢 Java。"));
        // 创建只返回指定历史快照的记忆假实现。
        ConversationMemory memory = new FixedConversationMemory(history);
        // 创建捕获模型输入的端口，便于检查消息角色和顺序。
        CapturingModel model = new CapturingModel(new AssistantMessage("你叫小明，并且喜欢 Java。"));
        // 创建独立任务注册表以验证运行结束后的资源释放。
        InMemoryTaskRegistry tasks = new InMemoryTaskRegistry();
        // 组装带跨请求记忆依赖且不包含工具的 Agent。
        ManualReactAgent agent = new ManualReactAgent(
                // 注入记录消息快照的模型端口。
                model,
                // 注入空工具注册表，使本测试只关注历史加载。
                new AgentToolRegistry(List.of()),
                // 注入任务注册表以维持同会话互斥语义。
                tasks,
                // 注入返回两轮历史的记忆端口。
                memory);

        // 订阅第三次跨请求对话，并验证最终协议仍保持不变。
        StepVerifier.create(agent.stream("conversation-memory", "请总结你记住的信息"))
                // 断言模型基于历史生成的最终回答被输出。
                .expectNext(AgentStreamEvent.text("你叫小明，并且喜欢 Java。"))
                // 断言最终文本后仍有显式完成事件。
                .expectNext(AgentStreamEvent.complete())
                // 断言事件流正常关闭。
                .verifyComplete();

        // 取得模型第一次决策收到的完整消息快照。
        List<Message> messages = model.messageSnapshots.getFirst();
        // 断言消息总数等于系统消息、四条历史消息和当前用户消息。
        assertThat(messages).hasSize(6);
        // 断言系统规则始终位于整个提示词首位。
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        // 断言第一轮历史用户问题紧随系统消息。
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class).extracting(Message::getText).isEqualTo("我叫小明");
        // 断言第一轮历史助手回答使用 AssistantMessage 角色回放。
        assertThat(messages.get(2)).isInstanceOf(AssistantMessage.class).extracting(Message::getText).isEqualTo("你好，小明。");
        // 断言第二轮历史用户问题保持时间顺序。
        assertThat(messages.get(3)).isInstanceOf(UserMessage.class).extracting(Message::getText).isEqualTo("我喜欢 Java");
        // 断言第二轮历史助手回答紧随对应问题。
        assertThat(messages.get(4)).isInstanceOf(AssistantMessage.class).extracting(Message::getText).isEqualTo("我记住了你喜欢 Java。");
        // 断言本次 HTTP 请求的当前问题最后加入模型上下文。
        assertThat(messages.get(5)).isInstanceOf(UserMessage.class).extracting(Message::getText).isEqualTo("请总结你记住的信息");
        // 断言正常完成后任务注册表已经释放该会话。
        assertThat(tasks.hasRunningTask("conversation-memory")).isFalse();
    }

    @Test
    // 验证历史存储读取失败时不会携带不完整上下文调用模型。
    void shouldEmitTerminalErrorWithoutCallingModelWhenMemoryReadFails() {
        // 记录模型端口是否被错误调用。
        AtomicInteger modelCalls = new AtomicInteger();
        // 创建任何调用都会增加计数的模型端口。
        ReactModelPort model = (messages, toolsEnabled) -> {
            // 增加调用次数，使测试能够识别错误越过记忆边界。
            modelCalls.incrementAndGet();
            // 返回理论上不应产生的回答以满足端口契约。
            return new AssistantMessage("不应调用模型");
        };
        // 创建读取时模拟底层存储故障的记忆端口。
        ConversationMemory memory = new FailingConversationMemory();
        // 创建独立任务注册表以检查失败路径的资源清理。
        InMemoryTaskRegistry tasks = new InMemoryTaskRegistry();
        // 组装带失败记忆端口的 Agent。
        ManualReactAgent agent = new ManualReactAgent(
                // 注入带计数器的模型端口。
                model,
                // 注入空工具注册表。
                new AgentToolRegistry(List.of()),
                // 注入任务生命周期注册表。
                tasks,
                // 注入会在读取时抛出异常的记忆端口。
                memory);

        // 订阅会触发历史读取失败的 Agent 请求。
        StepVerifier.create(agent.stream("conversation-failure", "继续聊天"))
                // 断言存储异常被转换为稳定且可理解的错误事件。
                .expectNext(AgentStreamEvent.error("memory unavailable"))
                // 断言失败路径仍发送统一完成事件。
                .expectNext(AgentStreamEvent.complete())
                // 断言 Reactor 流正常关闭而不是泄漏 onError。
                .verifyComplete();

        // 断言历史加载失败后模型一次也没有被调用。
        assertThat(modelCalls).hasValue(0);
        // 断言失败终止后会话任务已经从注册表释放。
        assertThat(tasks.hasRunningTask("conversation-failure")).isFalse();
    }

    // 提供固定历史快照，隔离测试与具体内存适配器实现。
    private static final class FixedConversationMemory implements ConversationMemory {
        // 保存测试期望 Agent 加载的不可变历史。
        private final List<ConversationTurn> history;

        // 接收并复制测试配置的历史，避免测试数据在运行中变化。
        private FixedConversationMemory(List<ConversationTurn> history) {
            // 保存不可变副本以模拟真实记忆端口的快照语义。
            this.history = List.copyOf(history);
        }

        @Override
        // 为任意测试会话返回预先配置的历史快照。
        public List<ConversationTurn> get(String conversationId) {
            // 返回固定历史供 Agent 构造初始消息列表。
            return history;
        }

        @Override
        // Task 3 尚不验证成功提交，因此提供无副作用的端口实现。
        public void append(String conversationId, ConversationTurn turn) {
            // 本测试只关注读取历史，故无需保存新轮次。
        }

        @Override
        // 本测试不使用清空能力，因此返回没有历史被删除。
        public boolean clear(String conversationId) {
            // 返回 false 表示测试假实现未执行删除。
            return false;
        }
    }

    // 模拟记忆读取边界故障，验证 Agent 不会继续调用模型。
    private static final class FailingConversationMemory implements ConversationMemory {
        @Override
        // 在读取历史时抛出稳定异常，模拟外部存储不可用。
        public List<ConversationTurn> get(String conversationId) {
            // 抛出带可读消息的异常供 SSE 错误协议转换。
            throw new IllegalStateException("memory unavailable");
        }

        @Override
        // 失败场景不应进入追加路径。
        public void append(String conversationId, ConversationTurn turn) {
            // 若流程正确，本空实现不会被调用。
        }

        @Override
        // 失败场景不使用清空能力。
        public boolean clear(String conversationId) {
            // 返回 false 以满足端口契约。
            return false;
        }
    }

    // 记录 Agent 提交的轮次，验证成功与失败终止路径的数据边界。
    private static final class RecordingConversationMemory implements ConversationMemory {
        // 保存每次读取时需要返回的初始历史快照。
        private final List<ConversationTurn> history;
        // 保存 Agent 实际追加的全部轮次，供终止顺序测试检查。
        private final List<ConversationTurn> appendedTurns = new ArrayList<>();

        // 接收并复制预设历史快照。
        private RecordingConversationMemory(List<ConversationTurn> history) {
            // 使用不可变副本模拟生产记忆读取的快照语义。
            this.history = List.copyOf(history);
        }

        @Override
        // 返回测试预设的历史轮次。
        public List<ConversationTurn> get(String conversationId) {
            // 返回不可变历史供 Agent 初始化消息。
            return history;
        }

        @Override
        // 记录一次成功终止尝试提交的完整问答。
        public void append(String conversationId, ConversationTurn turn) {
            // 将轮次加入观察列表，使测试能验证内容和调用时机。
            appendedTurns.add(turn);
        }

        @Override
        // 当前测试不需要清空记录型记忆。
        public boolean clear(String conversationId) {
            // 返回 false 表示没有执行清空。
            return false;
        }
    }

    // 模拟读取可用但持久化失败的记忆边界。
    private static final class AppendFailingConversationMemory implements ConversationMemory {
        @Override
        // 返回空历史，让 Agent 可以正常进入模型决策。
        public List<ConversationTurn> get(String conversationId) {
            // 返回不可变空快照。
            return List.of();
        }

        @Override
        // 在正常终止保存轮次时模拟存储故障。
        public void append(String conversationId, ConversationTurn turn) {
            // 抛出带稳定原因的异常供保存失败协议转换。
            throw new IllegalStateException("memory write unavailable");
        }

        @Override
        // 本场景不使用清空能力。
        public boolean clear(String conversationId) {
            // 返回 false 以满足端口契约。
            return false;
        }
    }

    // 捕获模型每次收到的不可变消息快照并返回预设最终回答。
    private static final class CapturingModel implements ReactModelPort {
        // 保存每次模型决策的完整输入，供测试检查角色与时序。
        private final List<List<Message>> messageSnapshots = new ArrayList<>();
        // 保存该测试模型应返回的最终助手消息。
        private final AssistantMessage answer;

        // 接收模型调用时需要返回的稳定回答。
        private CapturingModel(AssistantMessage answer) {
            // 保存预设回答供 decide 方法使用。
            this.answer = answer;
        }

        @Override
        // 捕获一次模型输入并直接返回不含工具调用的最终答案。
        public AssistantMessage decide(List<Message> messages, boolean toolsEnabled) {
            // 复制输入列表，防止后续上下文追加影响本次断言。
            messageSnapshots.add(List.copyOf(messages));
            // 返回预设最终答案，使 Agent 正常结束。
            return answer;
        }
    }

    // 提供可中断的阻塞模型，用于稳定制造取消与并发请求竞争。
    private static final class BlockingModel implements ReactModelPort {
        // 在模型决策开始时通知测试线程任务已经进入运行状态。
        private final CountDownLatch entered = new CountDownLatch(1);

        @Override
        // 阻塞当前 boundedElastic 工作者，直到取消动作将线程中断。
        public AssistantMessage decide(List<Message> messages, boolean toolsEnabled) {
            // 标记模型调用已经开始。
            entered.countDown();
            try {
                // 使用不会自行释放的闩锁保持模型调用阻塞。
                new CountDownLatch(1).await();
                // 理论上不可到达，仅用于满足方法返回类型。
                return new AssistantMessage("不应返回");
            } catch (InterruptedException error) {
                // 恢复中断标记，遵守 Java 线程中断约定。
                Thread.currentThread().interrupt();
                // 将取消引发的中断转换成运行时异常，由已取得终止权的取消路径抑制。
                throw new IllegalStateException("blocking model interrupted", error);
            }
        }
    }
}
