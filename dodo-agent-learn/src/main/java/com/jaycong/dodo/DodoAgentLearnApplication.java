package com.jaycong.dodo; // 声明启动类所在的根包，Spring Boot 默认会从这个包向下扫描组件。

import org.springframework.boot.SpringApplication; // 引入 Spring Boot 的应用启动器，用于创建并运行应用上下文。
import org.springframework.boot.autoconfigure.SpringBootApplication; // 引入组合注解，以启用配置、自动配置和组件扫描。

/**
 * 学习项目的 Spring Boot 启动入口。
 * SpringApplication.run 会创建应用上下文、执行自动配置，并启动内嵌的 WebFlux 服务。
 */
@SpringBootApplication // 标记这是 Spring Boot 主配置类，并以当前包作为默认组件扫描起点。
public class DodoAgentLearnApplication { // 定义应用入口类型，供 JVM 和 Spring Boot 定位启动配置。

    public static void main(String[] args) { // JVM 从这个静态 main 方法进入应用，并把命令行参数传入 Spring Boot。
        SpringApplication.run(DodoAgentLearnApplication.class, args); // 创建 Spring 容器、装配 Bean，并启动 HTTP 服务。
    } // 结束应用启动方法。
} // 结束 Spring Boot 启动类。
