package com.jaycong.dodo.agent; // 将单次运行上下文测试放在 Agent 核心包中。

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReactRunContextTest { // 定义消息、轮次、去重与终止状态的行为测试。

    @Test
    void maintainsOrderedMessagesAndDecisionRoundLimit() { // 验证上下文按顺序积累消息并严格限制工具决策轮次。
        Message first = new UserMessage("第一个问题"); // 创建初始用户消息。
        Message second = new UserMessage("补充观察"); // 创建稍后追加的消息。
        ReactRunContext context = new ReactRunContext(List.of(first), 4); // 创建最多允许四个工具决策轮次的上下文。

        context.addMessage(second); // 模拟 ReAct 循环追加最新消息。

        assertThat(context.messages()).containsExactly(first, second); // 断言消息保持严格时间顺序。
        assertThatThrownBy(() -> context.messages().add(first)) // 尝试从快照外部修改内部消息。
                .isInstanceOf(UnsupportedOperationException.class); // 断言快照不可变，状态只能经过上下文方法修改。
        assertThat(context.tryStartDecisionRound()).isTrue(); // 断言第一轮允许开始。
        assertThat(context.tryStartDecisionRound()).isTrue(); // 断言第二轮允许开始。
        assertThat(context.tryStartDecisionRound()).isTrue(); // 断言第三轮允许开始。
        assertThat(context.tryStartDecisionRound()).isTrue(); // 断言第四轮允许开始。
        assertThat(context.tryStartDecisionRound()).isFalse(); // 断言第五轮被最大轮次规则拒绝。
        assertThat(context.round()).isEqualTo(4); // 断言失败尝试不会继续增加已执行轮次。
    } // 结束消息与轮次测试。

    @Test
    void detectsRepeatedToolSignaturesUsingTrimmedRawArguments() { // 验证工具去重只清理参数首尾空格而不改变原始 JSON 语义。
        ReactRunContext context = new ReactRunContext(List.of(), 4); // 创建没有初始消息的独立上下文。

        assertThat(context.markToolExecution("weather", "  {\"city\":\"北京\"}  ")).isTrue(); // 首次签名应登记为真实执行。
        assertThat(context.markToolExecution("weather", "{\"city\":\"北京\"}")).isFalse(); // 去掉首尾空格后的相同调用应判定重复。
        assertThat(context.markToolExecution("calculator", "{\"city\":\"北京\"}")).isTrue(); // 不同工具名即使参数相同仍是新调用。
    } // 结束工具签名去重测试。

    @Test
    void allowsCancellationAndFinishTransitionsOnlyOnce() { // 验证竞争终止路径只能各自完成一次原子状态转换。
        ReactRunContext context = new ReactRunContext(List.of(), 4); // 创建用于状态转换验证的新上下文。

        assertThat(context.markCancelled()).isTrue(); // 第一次取消成功把运行状态改为已取消。
        assertThat(context.markCancelled()).isFalse(); // 重复取消不应再次触发下游通知。
        assertThat(context.isCancelled()).isTrue(); // 断言取消状态可被阻塞循环轮询读取。
        assertThat(context.tryFinish()).isTrue(); // 第一个终止路径成功取得完成闸门。
        assertThat(context.tryFinish()).isFalse(); // 后续终止路径不能重复发送 complete。
        assertThat(context.isFinished()).isTrue(); // 断言完成状态对所有线程可见。
    } // 结束取消与完成原子状态测试。

    @Test
    void exportsOnlyBudgetedSnapshotWhileKeepingFullRunHistory() { // 验证模型快照可裁剪，但运行上下文仍完整保存可观察消息历史。
        SystemMessage system = new SystemMessage("系统提示"); // 创建本轮不可裁剪的系统规则。
        UserMessage oldQuestion = new UserMessage("旧问题旧问题旧问题旧问题"); // 创建可被裁剪的历史问题。
        AssistantMessage oldAnswer = new AssistantMessage("旧回答旧回答旧回答旧回答"); // 创建应与历史问题整体裁剪的历史回答。
        UserMessage currentQuestion = new UserMessage("当前问题"); // 创建本轮必须保留的显式问题。
        ReactRunContext context = new ReactRunContext(List.<Message>of(system, oldQuestion, oldAnswer), 4, new CharacterTokenBudget(15)); // 创建携带小预算器的独立运行上下文。

        context.setCurrentUserMessage(currentQuestion); // 标记当前问题，避免与相同文本的历史消息混淆。
        context.addMessage(currentQuestion); // 按真实运行顺序把当前问题追加到完整历史。

        assertThat(context.messagesWithinBudget()).containsExactly(system, currentQuestion); // 断言模型仅收到满足预算的必需消息快照。
        assertThat(context.messages()).containsExactly(system, oldQuestion, oldAnswer, currentQuestion); // 断言完整运行历史没有被裁剪或改写。
    } // 结束模型快照与完整历史隔离测试。
} // 结束 ReAct 运行上下文测试类。
