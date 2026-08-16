package com.jaycong.llm.webflux.lesson01;

import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 第四节：Disposable 用于取消订阅。
 */
@Slf4j
public final class DisposableCancelLogDemo {

    private DisposableCancelLogDemo() {
    }

    public static void main(String[] args) throws InterruptedException {
        CountDownLatch terminated = new CountDownLatch(1);
        Scheduler tokenProducer = Schedulers.newSingle("token-producer");
        Scheduler clientController = Schedulers.newSingle("client-controller");

        try {
            Flux<String> tokenStream = Flux.interval(Duration.ofMillis(250), tokenProducer)
                    .take(10)
                    .map(index -> "Token-" + index)
                    .doOnSubscribe(ignored -> log.info("上游开始生成 Token"))
                    .doOnNext(token -> log.info("上游生成：{}", token))
                    .doOnCancel(() -> log.warn("上游收到取消信号，停止继续生成"))
                    .doFinally(signalType -> {
                        log.info("流结束原因：{}", signalType);
                        terminated.countDown();
                    });

            Disposable clientSubscription = tokenStream.subscribe(
                    token -> log.info("客户端消费：{}", token),
                    error -> log.error("客户端收到错误：{}", error.getMessage()),
                    () -> log.info("客户端收到正常完成信号")
            );

            clientController.schedule(() -> {
                log.warn("客户端主动断开：调用 Disposable.dispose()");
                clientSubscription.dispose();
            }, 850, TimeUnit.MILLISECONDS);

            if (!terminated.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("流没有在预期时间内结束");
            }

            log.info("订阅是否已取消：{}", clientSubscription.isDisposed());
        } finally {
            clientController.dispose();
            tokenProducer.dispose();
        }
    }
}
