package com.jaycong.llm.webflux.lesson01;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * 第十一节：在 flatMap 的子流中恢复错误，避免一个工具失败取消全部并发调用。
 */
@Slf4j
public final class ToolErrorIsolationLogDemo {

    private ToolErrorIsolationLogDemo() {
    }

    public static void main(String[] args) {
        Scheduler toolScheduler = Schedulers.newParallel("isolated-tool", 3);

        try {
            Flux.just("search", "weather", "calendar")
                    .flatMap(tool -> callTool(tool, toolScheduler)
                            // 错误处理位于每个子流内部，因此只恢复失败的那个工具。
                            .onErrorResume(error -> {
                                log.warn("工具 {} 失败：{}；返回该工具的降级结果", tool, error.getMessage());
                                return Mono.just(tool + " 暂不可用");
                            }))
                    .doOnNext(result -> log.info("Agent 收到结果：{}", result))
                    .blockLast();
        } finally {
            toolScheduler.dispose();
        }
    }

    private static Mono<String> callTool(String tool, Scheduler toolScheduler) {
        Duration delay = switch (tool) {
            case "search" -> Duration.ofMillis(300);
            case "weather" -> Duration.ofMillis(100);
            case "calendar" -> Duration.ofMillis(200);
            default -> throw new IllegalArgumentException("未知工具：" + tool);
        };

        return Mono.defer(() -> {
            log.info("开始调用工具={}，预计耗时={}ms", tool, delay.toMillis());
            if ("calendar".equals(tool)) {
                return Mono.delay(delay, toolScheduler)
                        .then(Mono.<String>error(new IllegalStateException("日历服务不可用")));
            }
            return Mono.delay(delay, toolScheduler)
                    .thenReturn(tool + " 结果");
        });
    }
}
