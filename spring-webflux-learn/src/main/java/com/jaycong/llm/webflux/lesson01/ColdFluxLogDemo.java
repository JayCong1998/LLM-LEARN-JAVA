package com.jaycong.llm.webflux.lesson01;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 第二节：观察 Flux 冷流——每一次订阅都会重新执行数据源。
 */
@Slf4j
public final class ColdFluxLogDemo {

    private static final AtomicInteger SUBSCRIPTION_COUNT = new AtomicInteger();

    private ColdFluxLogDemo() {
    }

    public static void main(String[] args) {
        Scheduler tokenScheduler = Schedulers.newSingle("token-emitter");

        try {
            Flux<String> tokenStream = createColdTokenStream(tokenScheduler);

            consume("client-A", tokenStream);
            consume("client-B", tokenStream);
        } finally {
            tokenScheduler.dispose();
        }
    }

    private static Flux<String> createColdTokenStream(Scheduler tokenScheduler) {
        return Flux.defer(() -> {
            int subscriptionId = SUBSCRIPTION_COUNT.incrementAndGet();
            log.info("数据源为第 {} 次订阅重新生成 Token 流", subscriptionId);

            return Flux.just("你好", "，", "Reactor")
                    .delayElements(Duration.ofMillis(300), tokenScheduler)
                    .doOnNext(token -> log.info("数据源 #{} 产生 Token：{}", subscriptionId, token));
        });
    }

    private static void consume(String clientName, Flux<String> tokenStream) {
        log.info("---------- {} 开始订阅 ----------", clientName);

        String lastToken = tokenStream
                .doOnSubscribe(ignored -> log.info("{} 收到 onSubscribe", clientName))
                .doOnNext(token -> log.info("{} 收到 onNext：{}", clientName, token))
                .doOnComplete(() -> log.info("{} 收到 onComplete", clientName))
                // 仅用于 CLI 教学：等待当前订阅结束。WebFlux Controller 中不要调用 blockLast()。
                .blockLast();

        log.info("{} 的最后一个 Token：{}", clientName, lastToken);
    }
}
