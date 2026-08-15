package com.jaycong.dodo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class DodoAgentLearnApplicationTest {

    @Test
    void contextLoads() {
    }
}
