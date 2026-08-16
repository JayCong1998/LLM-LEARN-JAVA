package com.jaycong.llm.webflux.lesson01;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 第七节：Reactor 的错误处理与降级。
 */
@Slf4j
public final class ErrorHandlingLogDemo {

    private ErrorHandlingLogDemo() {
    }

    public static void main(String[] args) {
        staticFallback();
        dynamicFallback();
        translateException();
    }

    private static void staticFallback() {
        log.info("========== 场景一：onErrorReturn 固定降级 ==========");

        Mono.<String>error(new IllegalStateException("模型服务不可用"))
                .doOnError(error -> log.warn("原始错误：{}", error.getMessage()))
                .onErrorReturn("当前无法生成回答，请稍后重试。")
                .subscribe(result -> log.info("返回用户：{}", result));
    }

    private static void dynamicFallback() {
        log.info("========== 场景二：onErrorResume 切换备用流 ==========");

        Mono.<String>error(new IllegalStateException("搜索工具超时"))
                .onErrorResume(IllegalStateException.class, error -> {
                    log.warn("工具失败：{}；切换到本地知识库", error.getMessage());
                    return Mono.just("来自本地知识库的备用答案");
                })
                .subscribe(result -> log.info("Agent 最终结果：{}", result));
    }

    private static void translateException() {
        log.info("========== 场景三：onErrorMap 转换业务异常 ==========");

        Mono.<String>error(new IllegalArgumentException("无效的工具参数"))
                .onErrorMap(error -> new AgentExecutionException("Agent 工具执行失败", error))
                .subscribe(
                        result -> log.info("这条日志不会出现：{}", result),
                        error -> log.warn("订阅者收到 {}：{}", error.getClass().getSimpleName(), error.getMessage())
                );
    }

    private static final class AgentExecutionException extends RuntimeException {

        private AgentExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
