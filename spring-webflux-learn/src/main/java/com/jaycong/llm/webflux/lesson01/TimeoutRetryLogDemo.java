package com.jaycong.llm.webflux.lesson01;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 第八节：超时与指数退避重试。
 */
@Slf4j
public final class TimeoutRetryLogDemo {

    private TimeoutRetryLogDemo() {
    }

    public static void main(String[] args) {
        AtomicInteger attempts = new AtomicInteger();
        Scheduler timingScheduler = Schedulers.newSingle("model-timeout");

        try {
            String answer = callModel(attempts, timingScheduler)
                    .timeout(Duration.ofMillis(200), timingScheduler)
                    .retryWhen(Retry.backoff(2, Duration.ofMillis(100))
                            .scheduler(timingScheduler)
                            .filter(TimeoutException.class::isInstance)
                            .doBeforeRetry(signal -> log.warn(
                                    "第 {} 次请求超时，按 100ms 基础延迟进行指数退避重试",
                                    signal.totalRetries() + 1
                            )))
                    .block();

            log.info("最终模型回答：{}", answer);
        } finally {
            timingScheduler.dispose();
        }
    }

    private static Mono<String> callModel(AtomicInteger attempts, Scheduler timingScheduler) {
        return Mono.defer(() -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 3) {
                log.info("第 {} 次调用模型：模拟 400ms 慢响应", attempt);
                return Mono.delay(Duration.ofMillis(400), timingScheduler)
                        .thenReturn("这条响应会因超时被取消");
            }

            log.info("第 {} 次调用模型：模拟 50ms 正常响应", attempt);
            return Mono.delay(Duration.ofMillis(50), timingScheduler)
                    .thenReturn("模型已在重试后成功回答");
        });
    }
}
