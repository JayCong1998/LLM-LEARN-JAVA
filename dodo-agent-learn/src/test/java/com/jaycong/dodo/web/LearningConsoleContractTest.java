package com.jaycong.dodo.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LearningConsoleContractTest {

    @Test
    void pageAndScriptExposeTheFirstStageControlsAndEndpoints() throws IOException {
        String html = resource("static/index.html");
        String javascript = resource("static/js/app.js");

        assertThat(html)
                .contains("id=\"conversation-id\"")
                .contains("id=\"message\"")
                .contains("id=\"send\"")
                .contains("id=\"stop\"")
                .contains("id=\"output\"");
        assertThat(javascript)
                .contains("/api/agent/chat/stream")
                .contains("/api/agent/tasks/")
                .contains("response.body.getReader()")
                .contains("new AbortController()");
    }

    private static String resource(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
