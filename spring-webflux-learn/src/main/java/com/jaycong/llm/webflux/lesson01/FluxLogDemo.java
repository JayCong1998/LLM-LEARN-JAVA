package com.jaycong.llm.webflux.lesson01;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Locale;

/**
 * 运行本类，观察 Flux 对多个数据依次执行 map、filter 和订阅。
 */
@Slf4j
public final class FluxLogDemo {

    private FluxLogDemo() {
    }

    public static void main(String[] args) {
        long startedAt = System.nanoTime();
        Scheduler delayScheduler = Schedulers.newSingle("lesson01-delay");

        try {
            Flux<String> names = Flux.just("alice", "bob", "carol")
                    .doOnSubscribe(subscription -> log.info("已订阅 Flux"))
                    // 每个元素延迟 500ms；后续信号由专用调度线程处理。
                    .delayElements(Duration.ofMillis(500), delayScheduler)
                    .map(name -> {
                        log.info("[{} ms] map 收到数据：{}", elapsedMillis(startedAt), name);
                        return name.toUpperCase(Locale.ROOT);
                    })
                    .filter(name -> {
                        boolean keep = !"BOB".equals(name);
                        log.info("[{} ms] filter 判断 {}：{}", elapsedMillis(startedAt), name, keep ? "保留" : "过滤");
                        return keep;
                    })
                    .doOnNext(name -> log.info("[{} ms] Flux 发出数据：{}", elapsedMillis(startedAt), name))
                    .doOnComplete(() -> log.info("[{} ms] Flux 正常结束", elapsedMillis(startedAt)));

            log.info("Flux 已创建；此时尚未执行 map 或 filter，因为还没有订阅者");

            // 仅用于命令行演示：等待异步流结束。WebFlux 的 Controller 中绝不能调用 block() / blockLast()。
            String lastName = names.blockLast();
            log.info("[{} ms] blockLast 收到最后一个元素：{}", elapsedMillis(startedAt), lastName);
        } finally {
            delayScheduler.dispose();
        }

        log.info("主程序结束");
        log.info("主程序结束");
    }

    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
