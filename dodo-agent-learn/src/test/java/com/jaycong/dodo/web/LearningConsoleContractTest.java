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
                .contains("id=\"output\"");
        assertThat(javascript)
                .contains("/api/agent/chat/stream")
                .contains("/api/agent/tasks/")
                .contains("response.body.getReader()")
                .contains("event.type === 'tool_start'") // 断言脚本能处理工具开始事件。
                .contains("event.type === 'tool_end'") // 断言脚本能处理工具结束事件。
                .contains("event.toolCallId") // 断言脚本使用调用编号关联同一张工具卡片。
                .contains("new AbortController()");
        assertThat(html + javascript) // 合并用户可见页面和交互脚本检查禁用内容。
                .doesNotContain("Chain of Thought", "思维链"); // 断言控制台不展示模型内部推理过程。
    }

    private static String resource(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
