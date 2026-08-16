package com.jaycong.llm.webflux.lesson01;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * 第九节：背压。生产速度快于消费速度时，使用 onBackpressureDrop 丢弃积压数据。
 */
@Slf4j
public final class BackpressureDropLogDemo {

    private BackpressureDropLogDemo() {
    }

    public static void main(String[] args) {
        Scheduler producerScheduler = Schedulers.newSingle("fast-producer");
        Scheduler consumerScheduler = Schedulers.newSingle("slow-consumer");

        try {
            Flux.interval(Duration.ofMillis(50), producerScheduler)
                    .doOnNext(tokenId -> log.info("生产者生成 Token-{}", tokenId))
                    .onBackpressureDrop(tokenId -> log.warn("消费者太慢，丢弃 Token-{}", tokenId))
                    // prefetch=1：限制生产者最多向下游预取一个待消费元素，便于观察背压。
                    .publishOn(consumerScheduler, 1)
                    .doOnNext(BackpressureDropLogDemo::slowlyConsume)
                    .take(5)
                    .blockLast();
        } finally {
            producerScheduler.dispose();
            consumerScheduler.dispose();
        }
    }

    private static void slowlyConsume(long tokenId) {
        log.info("消费者开始处理 Token-{}", tokenId);
        try {
            Thread.sleep(180);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("消费者处理被中断", exception);
        }
        log.info("消费者处理完成 Token-{}", tokenId);
    }
}
