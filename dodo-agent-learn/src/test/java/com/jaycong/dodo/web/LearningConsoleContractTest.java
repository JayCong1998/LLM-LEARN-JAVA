package com.jaycong.dodo.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LearningConsoleContractTest {

    @Test
    void pageAndScriptExposeReactToolLifecycleWithoutInternalReasoning() throws IOException { // 验证阶段二控制台展示工具轨迹但不暴露内部推理。
        String html = resource("static/index.html");
        String javascript = resource("static/js/app.js");

        assertThat(html)
                .contains("id=\"conversation-id\"")
                .contains("id=\"message\"")
                .contains("id=\"send\"")
                .contains("id=\"stop\"")
                .contains("id=\"tool-trace\"") // 断言页面提供独立工具生命周期容器。
                .contains("id=\"memory-panel\"") // 断言页面提供与本轮工具轨迹分离的跨请求记忆面板。
                .contains("id=\"memory-turn-count\"") // 断言页面展示当前已经读取到的完整问答轮次数量。
                .contains("id=\"refresh-memory\"") // 断言页面提供只查询历史而不启动 Agent 的刷新按钮。
                .contains("id=\"clear-memory\"") // 断言页面提供显式清空当前会话历史的按钮。
                .contains("id=\"memory-turns\"") // 断言页面提供安全承载历史问答条目的容器。
                .contains("id=\"output\"");
        assertThat(javascript)
                .contains("/api/agent/chat/stream")
                .contains("/api/agent/tasks/")
                .contains("/api/agent/conversations/") // 断言脚本能够按当前 conversationId 组装记忆管理接口路径。
                .contains("/memory") // 断言脚本访问固定的会话记忆资源后缀。
                .contains("method: 'DELETE'") // 断言清空动作使用 DELETE 而不是错误地创建新的 Agent 请求。
                .contains("turn.userContent") // 断言脚本从后端记忆响应读取用户问题字段。
                .contains("turn.assistantContent") // 断言脚本从后端记忆响应读取助手回答字段。
                .contains("userContent.textContent = turn.userContent") // 断言历史用户内容通过文本节点写入，不能被解析为 HTML。
                .contains("assistantContent.textContent = turn.assistantContent") // 断言历史助手内容同样使用文本节点安全渲染。
                .contains("response.body.getReader()")
                .contains("event.type === 'tool_start'") // 断言脚本能处理工具开始事件。
                .contains("event.type === 'tool_end'") // 断言脚本能处理工具结束事件。
                .contains("event.type === 'tool_retry'") // 断言脚本能处理服务端发送的安全重试计划事件。
                .contains("handleToolRetry(event)") // 断言重试事件委托给独立处理函数以保持生命周期分支清晰。
                .contains("event.attempt") // 断言脚本读取即将开始的实际尝试次数。
                .contains("event.delayMillis") // 断言脚本读取本次尝试前的退避时长。
                .contains("event.toolCallId") // 断言脚本使用调用编号关联同一张工具卡片。
                .contains("new AbortController()");
        assertThat(html + javascript) // 合并用户可见页面和交互脚本检查禁用内容。
                .doesNotContain("Chain of Thought", "思维链"); // 断言控制台不展示模型内部推理过程。
    }

    private static String resource(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
