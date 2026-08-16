package com.jaycong.dodo.agent; // 将模型决策端口放在 Agent 核心包中，保持依赖方向由适配器指向核心。

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 定义手写 ReAct 循环发起一次模型决策所需的最小能力。
 * 核心循环只依赖消息和工具开关，因此测试可以替换假实现，生产环境再接入 Spring AI ChatClient。
 */
@FunctionalInterface
public interface ReactModelPort { // 定义只有一个抽象方法的可替换模型边界。

    AssistantMessage decide( // 请求模型基于当前完整上下文生成下一步助手消息。
            List<Message> messages, // 传入按时间顺序积累的系统、用户、助手和工具响应消息。
            boolean toolsEnabled); // 指示本轮是否允许模型产生新的工具调用，并结束参数列表。
} // 结束 ReAct 模型决策端口定义。
