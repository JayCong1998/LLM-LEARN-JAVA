package com.jaycong.know.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 知识引擎应用启动入口。
 */
@SpringBootApplication
public class KnowEngineLearnApplication {

    /**
     * 启动 Spring Boot 应用。
     *
     * @param args 应用启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(KnowEngineLearnApplication.class, args);
    }
}
