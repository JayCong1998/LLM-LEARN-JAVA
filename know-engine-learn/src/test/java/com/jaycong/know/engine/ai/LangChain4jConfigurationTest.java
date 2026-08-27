package com.jaycong.know.engine.ai;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LangChain4jConfigurationTest {

    @Test
    void usesTheVersionedOpenAiCompatibleEndpoint() throws IOException {
        try (var input = getClass().getResourceAsStream("/application.yml")) {
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(yaml.contains("/compatible-mode/v1"));
        }
    }
}
