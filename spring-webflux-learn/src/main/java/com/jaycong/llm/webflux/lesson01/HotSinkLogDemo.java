package com.jaycong.llm.webflux.lesson01;

import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 第三节：使用 Sinks 创建热流，让多个订阅者共享同一个 Token 生产者。
 */
@Slf4j
public final class HotSinkLogDemo {

    private HotSinkLogDemo() {
    }

    public static void main(String[] args) throws InterruptedException {
        Sinks.Many<String> tokenSink = Sinks.many().multicast().onBackpressureBuffer();
        Flux<String> sharedTokenStream = tokenSink.asFlux();
        CountDownLatch completionLatch = new CountDownLatch(2);
        Scheduler producerScheduler = Schedulers.newSingle("shared-token-producer");

        try {
            Disposable clientA = subscribeClient("client-A", sharedTokenStream, completionLatch);
            Disposable clientB = subscribeClient("client-B", sharedTokenStream, completionLatch);

            log.info("两个客户端已经订阅；现在启动唯一的 Token 生产者");
            emitTokens(tokenSink, producerScheduler);

            if (!completionLatch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("客户端未在预期时间内收到完成信号");
            }

            clientA.dispose();
            clientB.dispose();
            log.info("两个订阅句柄均已释放");
        } finally {
            producerScheduler.dispose();
        }
    }

    private static Disposable subscribeClient(String clientName, Flux<String> sharedTokenStream,
                                              CountDownLatch completionLatch) {
        return sharedTokenStream.subscribe(
                token -> log.info("{} 收到 Token：{}", clientName, token),
                error -> {
                    log.error("{} 收到错误：{}", clientName, error.getMessage());
                    completionLatch.countDown();
                },
                () -> {
                    log.info("{} 收到完成信号", clientName);
                    completionLatch.countDown();
                }
        );
    }

    private static void emitTokens(Sinks.Many<String> tokenSink, Scheduler producerScheduler) {
        Flux.just("你好", "，", "共享 Token 流")
                .delayElements(Duration.ofMillis(300), producerScheduler)
                .subscribe(
                        token -> {
                            Sinks.EmitResult result = tokenSink.tryEmitNext(token);
                            log.info("生产者发出 Token：{}，结果={}", token, result);
                        },
                        error -> tokenSink.tryEmitError(error),
                        () -> {
                            Sinks.EmitResult result = tokenSink.tryEmitComplete();
                            log.info("生产者发出完成信号，结果={}", result);
                        }
                );
    }
}
