package com.jaycong.llm.webflux.lesson01;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * 第五节：subscribeOn 与 publishOn 的线程切换。
 */
@Slf4j
public final class SchedulerLogDemo {

    private SchedulerLogDemo() {
    }

    public static void main(String[] args) {
        Scheduler sourceScheduler = Schedulers.newSingle("model-client");
        Scheduler consumerScheduler = Schedulers.newSingle("web-response");

        try {
            Flux.range(1, 3)
                    .doOnSubscribe(ignored -> log.info("上游被订阅"))
                    .map(index -> {
                        log.info("上游模拟生成 Token-{}", index);
                        return "Token-" + index;
                    })
                    .subscribeOn(sourceScheduler)
                    .publishOn(consumerScheduler)
                    .doOnNext(token -> log.info("切换后处理并准备写回响应：{}", token))
                    .doFinally(signalType -> log.info("流结束：{}", signalType))
                    .blockLast();
        } finally {
            sourceScheduler.dispose();
            consumerScheduler.dispose();
        }
    }
}
