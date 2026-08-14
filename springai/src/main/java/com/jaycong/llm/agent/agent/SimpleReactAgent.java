package com.jaycong.llm.agent.agent;


import com.alibaba.cloud.ai.dashscope.spec.DashScopeModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * @author pyc
 * @since 2026-08-14 15:36
 */
@Slf4j
public class SimpleReactAgent {

//     // react系统提示词
//    public static final String REACT_AGENT_SYSTEM_PROMPT = """
//            ## 角色
//            你是一个严格遵循 ReAct 模式的智能 AI 助手，会通过 Reasoning → Act(ToolCall) → Observation 的反复循环来逐步解决任务。
//
//            ## 工具调用规则（极其重要）
//            1. 如果需要调用工具：必须使用 OpenAI 官方 ToolCall 结构，并且 **只能通过工具调用字段输出**。
//            2. 工具调用时：**禁止在 content 中出现任何形式的工具调用文本**（包括 JSON、<tool_call>、函数名、参数、思考、推理或描述）。
//            3. 工具调用消息必须是一次性、原子性输出，不得混杂任何解释或内容。
//            4. 工具调用前后不得输出任何多余文字、标签、换行、推理轨迹或说明。
//            5. 调用工具时：
//               -工具参数必须是有效的JSON
//               -参数必须简洁，不超过500个字符
//               -切勿包含以前的工具结果、原始内容、HTML或长文本
//               -仅包括工具所需的最小控制参数
//
//            ## 工具执行结果
//            系统会自动将工具执行结果作为 ToolResponseMessage 注入上下文，你只需读取并决定下一步动作。
//
//            ## 最终答案规则
//            1. 如果上下文已经拥有了完成任务的全部信息，则不要再调用任何工具。
//            2. 在这种情况下，你必须输出最终自然语言答案，且 **禁止包含任何工具调用格式**。
//            3. 最终答案只允许是自然语言，不能包含 JSON、思考过程、reasoning、ToolCall 或伪代码。
//
//            ## 强制要求（必须遵守）
//            1. 工具调用消息必须只通过 ToolCall 字段输出，不允许在 content 字段体现工具调用迹象。
//            2. 如果本轮没有工具调用，则视为任务完成，你必须输出最终答案。
//            3. 不允许重复调用同一个工具（名称 + 参数完全一致），除非工具调用失败。
//            4. 禁止输出会干扰工具系统解析的任何结构（如 <reason>、<ToolCall>、函数 JSON、或模型内部思考）。
//            5. 如果上下文已经包含了完成任务的全部信息，则不要再调用任何工具。
//            """;
//    // 智能体名称
//    private final String name;
//    // 模型
//    private final DashScopeModel.ChatModel chatModel;
//    // 工具
//    private final List<ToolCallback> tools;
//    // 用户注入的系统提示词
//    private final String systemPrompt;
//    // 功能增强
//    private final List<Advisor> advisors;
//    // 最大迭代轮次
//    private int maxRounds;
//    // 记忆模块
//    private ChatMemory chatMemory;
//
//
//
//    private void initChatClient() {
//        try {
//            ToolCallingChatOptions toolOptions = ToolCallingChatOptions.builder()
//                    .toolCallbacks(tools)
//                    .internalToolExecutionEnabled(false)
//                    .build();
//
//            ChatClient.Builder builder = ChatClient.builder(chatModel);
//            if (!CollectionUtils.isEmpty(advisors)) {
//                builder.defaultAdvisors(advisors);
//            }
//            this.chatClient = builder.defaultOptions(toolOptions).defaultToolCallbacks(tools).build();
//        } catch (Exception e) {
//            throw new RuntimeException("ChatClient 初始化失败：" + e.getMessage(), e);
//        }
//    }
}
