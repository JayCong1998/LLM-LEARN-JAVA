package com.jaycong.dodo.agent; // 将上下文预算器测试放在 Agent 核心包中以验证包内配置构造器。

import org.junit.jupiter.api.Test; // 引入 JUnit 测试注解。
import org.springframework.ai.chat.messages.AssistantMessage; // 引入包含工具调用的助手消息类型。
import org.springframework.ai.chat.messages.Message; // 引入统一消息接口以构造消息序列。
import org.springframework.ai.chat.messages.SystemMessage; // 引入必须保留的系统提示消息类型。
import org.springframework.ai.chat.messages.ToolResponseMessage; // 引入工具 Observation 消息类型。
import org.springframework.ai.chat.messages.UserMessage; // 引入用户消息类型。

import java.util.List; // 引入不可变消息列表工厂。

import static org.assertj.core.api.Assertions.assertThat; // 引入 AssertJ 普通断言。
import static org.assertj.core.api.Assertions.assertThatThrownBy; // 引入 AssertJ 异常断言。

class CharacterTokenBudgetTest { // 定义固定 Token 预算下的上下文裁剪行为测试。

    @Test
    void estimatesTextByCeilingOfFourCharactersPlusMessageProtocolCost() { // 验证中文字符近似估算采用四字符向上取整并叠加消息协议成本。
        CharacterTokenBudget budget = new CharacterTokenBudget(20); // 创建用于精确断言估算值的小预算器。

        long estimatedTokens = budget.estimate(new UserMessage("一二三四五")); // 估算五个字符的用户消息。

        assertThat(estimatedTokens).isEqualTo(6L); // 断言两枚文本 Token 加四枚固定协议 Token。
    } // 结束基础字符估算测试。

    @Test
    void keepsSystemCurrentQuestionAndNewestCompleteHistoryGroupWithinBudget() { // 验证预算不足时丢弃最旧完整历史轮次而保留最近轮次。
        CharacterTokenBudget budget = new CharacterTokenBudget(31); // 创建只够容纳系统、当前问题和最近一轮历史的小预算器。
        SystemMessage system = new SystemMessage("系统提示"); // 创建必须保留的系统消息。
        UserMessage oldQuestion = new UserMessage("旧问题旧问题旧问题旧问题"); // 创建应被整体丢弃的最旧用户问题。
        AssistantMessage oldAnswer = new AssistantMessage("旧回答旧回答旧回答旧回答"); // 创建应随旧问题一起丢弃的最旧回答。
        UserMessage recentQuestion = new UserMessage("近问题"); // 创建应被保留的最近历史问题。
        AssistantMessage recentAnswer = new AssistantMessage("近回答"); // 创建应随最近问题一起保留的最近回答。
        UserMessage currentQuestion = new UserMessage("当前问题"); // 创建本次运行必须保留的显式当前问题。
        List<Message> messages = List.of(system, oldQuestion, oldAnswer, recentQuestion, recentAnswer, currentQuestion); // 按真实对话顺序组织待裁剪消息。

        List<Message> snapshot = budget.messagesWithinBudget(messages, currentQuestion); // 构造满足预算的模型调用快照。

        assertThat(snapshot).containsExactly(system, recentQuestion, recentAnswer, currentQuestion); // 断言只删除最旧完整轮次且保持原始时序。
    } // 结束历史轮次裁剪测试。

    @Test
    void keepsToolCallAndObservationAsOneAtomicGroup() { // 验证工具调用与其 Observation 不会被裁剪成无法理解的半组上下文。
        CharacterTokenBudget budget = new CharacterTokenBudget(31); // 创建只能容纳系统、当前问题与完整工具组的预算器。
        SystemMessage system = new SystemMessage("系统"); // 创建必须保留的系统提示。
        UserMessage currentQuestion = new UserMessage("当前问题"); // 创建必须保留的显式当前问题。
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("call-1", "function", "weather", "{\"city\":\"北京\"}"); // 创建模型声明的工具调用。
        AssistantMessage action = AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build(); // 创建承载工具调用的助手 Action 消息。
        ToolResponseMessage observation = ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "weather", "晴朗"))).build(); // 创建与调用编号关联的工具 Observation。
        AssistantMessage oldAnswer = new AssistantMessage("很早以前的回答很早以前的回答"); // 创建应优先被丢弃的旧普通助手消息。
        List<Message> messages = List.of(system, currentQuestion, oldAnswer, action, observation); // 组织当前问题后的普通消息与工具消息。

        List<Message> snapshot = budget.messagesWithinBudget(messages, currentQuestion); // 构造预算内模型消息快照。

        assertThat(snapshot).containsExactly(system, currentQuestion, action, observation); // 断言工具 Action 与 Observation 被一起保留，旧消息被丢弃。
    } // 结束工具原子分组测试。

    @Test
    void rejectsRunWhenMandatorySystemAndCurrentQuestionExceedBudget() { // 验证不可裁剪的系统提示与当前问题超预算时明确失败。
        CharacterTokenBudget budget = new CharacterTokenBudget(10); // 创建小于必需消息总量的预算器。
        SystemMessage system = new SystemMessage("系统提示系统提示系统提示"); // 创建超出预算的系统提示。
        UserMessage currentQuestion = new UserMessage("当前问题当前问题"); // 创建必须保留的当前问题。

        assertThatThrownBy(() -> budget.messagesWithinBudget(List.of(system, currentQuestion), currentQuestion)) // 尝试构造不能容纳必需消息的快照。
                .isInstanceOf(CharacterTokenBudget.ContextBudgetExceededException.class) // 断言抛出专用预算异常而不是静默截断。
                .hasMessage("上下文预算不足：系统提示和当前问题已超过 10 Token"); // 断言错误信息给出稳定且可展示的预算原因。
    } // 结束必需消息超预算测试。
} // 结束 CharacterTokenBudget 测试类。
