package com.jaycong.dodo.web;

import com.jaycong.dodo.agent.ManualReactAgent;
import com.jaycong.dodo.agent.ReactModelPort;
import com.jaycong.dodo.memory.InMemoryConversationMemory;
import com.jaycong.dodo.task.InMemoryTaskRegistry;
import com.jaycong.dodo.tool.AgentToolRegistry;
import com.jaycong.dodo.tool.WeatherTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@WebFluxTest(ChatController.class)
@Import({ManualReactAgent.class, InMemoryTaskRegistry.class, InMemoryConversationMemory.class, ChatControllerTest.FakeModelConfiguration.class})
class ChatControllerTest {

    @Autowired
    private WebTestClient client;

    @Test
    void streamsJsonEventsAsServerSentEvents() {
        client.get()
                .uri("/api/agent/chat/stream?conversationId=c-1&message=hello")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .value(body -> org.assertj.core.api.Assertions.assertThat(body)
                        .contains("\"type\":\"text\"")
                        .contains("\"content\":\"model answer\"")
                        .contains("\"type\":\"tool_start\"") // 断言 SSE 数据包含工具开始事件类型。
                        .contains("\"type\":\"tool_end\"") // 断言 SSE 数据包含工具结束事件类型。
                        .contains("\"toolName\":\"weather\"") // 断言工具生命周期事件携带工具名称。
                        .contains("event:tool_start") // 断言 SSE event 字段与 JSON type 保持一致。
                        .contains("event:tool_end") // 断言工具结束也使用独立 SSE event 字段。
                        .contains("\"type\":\"complete\""));
    }

    @Test
    void rejectsBlankMessagesBeforeCallingTheAgent() {
        client.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/agent/chat/stream")
                        .queryParam("conversationId", "c-1")
                        .queryParam("message", " ")
                        .build())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void reportsWhenThereIsNoTaskToStop() {
        client.post()
                .uri("/api/agent/tasks/no-task/stop")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .json("{\"stopped\":false}");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeModelConfiguration {

        @Bean
        ReactModelPort reactModelPort() { // 提供无需 API key 的脚本模型端口供 Web 切片测试使用。
            AtomicInteger calls = new AtomicInteger(); // 记录本次测试上下文中的模型决策次数。
            return (messages, toolsEnabled) -> { // 根据调用轮次依次返回工具 Action 和最终答案。
                if (calls.getAndIncrement() == 0) { // 第一轮模拟模型选择天气工具。
                    AssistantMessage.ToolCall call = new AssistantMessage.ToolCall( // 创建可被手写循环识别的工具调用。
                            "call-web-1", // 设置用于 SSE 关联的调用编号。
                            "function", // 使用 Spring AI 标准 function 工具类型。
                            "weather", // 选择测试注册表中的天气工具。
                            "{\"city\":\"北京\"}"); // 提供天气工具所需的 JSON 参数。
                    return AssistantMessage.builder().content("").toolCalls(List.of(call)).build(); // 返回只有 Action 的助手消息。
                } // 结束第一轮工具决策分支。
                return new AssistantMessage("model answer"); // 第二轮基于 Observation 返回最终文本。
            }; // 结束脚本模型端口定义。
        } // 结束测试模型 Bean 创建方法。

        @Bean
        AgentToolRegistry agentToolRegistry() { // 提供同时用于模型声明和真实执行的测试工具注册表。
            return new AgentToolRegistry(Arrays.asList(ToolCallbacks.from(new WeatherTool()))); // 注册确定性天气工具并返回目录。
        }
    }
}
