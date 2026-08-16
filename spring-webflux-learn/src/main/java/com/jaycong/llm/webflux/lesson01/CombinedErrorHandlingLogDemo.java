package com.jaycong.llm.webflux.lesson01;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 第七节补充：在同一条流中组合 onErrorMap、onErrorResume 与 onErrorReturn。
 */
@Slf4j
public final class CombinedErrorHandlingLogDemo {

    private CombinedErrorHandlingLogDemo() {
    }

    public static void main(String[] args) {
        Mono.<String>error(new IllegalStateException("主搜索工具超时"))
                // 1. 统一将底层异常转换为 Agent 业务异常。
                .onErrorMap(error -> new AgentToolException("主工具失败", error))
                // 2. 针对业务异常，尝试调用备用工具。
                .onErrorResume(AgentToolException.class, error -> {
                    log.warn("{}；改用备用搜索工具", error.getMessage());
                    return Mono.error(new IllegalStateException("备用搜索工具也不可用"));
                })
                // 3. 备用工具也失败，返回最终固定兜底值。
                .onErrorReturn("暂时无法查询实时信息，请稍后重试。")
                .subscribe(result -> log.info("最终返回给用户：{}", result));
    }

    private static final class AgentToolException extends RuntimeException {

        private AgentToolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
