package com.jaycong.llm.webflux.lesson01;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * 第十节：使用 flatMap 并发调用工具，使用 concatMap 串行且保持输入顺序。
 */
@Slf4j
public final class AsyncToolCallLogDemo {

    private AsyncToolCallLogDemo() {
    }

    public static void main(String[] args) {
        Scheduler toolScheduler = Schedulers.newParallel("tool-worker", 3);

        try {
            callToolsConcurrently(toolScheduler);
            callToolsSequentially(toolScheduler);
        } finally {
            toolScheduler.dispose();
        }
    }

    private static void callToolsConcurrently(Scheduler toolScheduler) {
        log.info("========== flatMap：并发工具调用，结果按完成顺序返回 ==========");

        Flux.just("search", "weather", "calendar")
                .flatMap(tool -> callTool(tool, toolScheduler))
                .doOnNext(result -> log.info("Agent 收到结果：{}", result))
                .blockLast();
    }

    private static void callToolsSequentially(Scheduler toolScheduler) {
        log.info("========== concatMap：串行工具调用，结果保持输入顺序 ==========");

        Flux.just("search", "weather", "calendar")
                .concatMap(tool -> callTool(tool, toolScheduler))
                .doOnNext(result -> log.info("Agent 收到结果：{}", result))
                .blockLast();
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
            return Mono.delay(delay, toolScheduler)
                    .thenReturn(tool + " 结果");
        });
    }
}
