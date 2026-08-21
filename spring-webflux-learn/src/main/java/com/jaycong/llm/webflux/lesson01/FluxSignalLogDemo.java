package com.jaycong.llm.webflux.lesson01;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * 第一节补充：观察 Flux 的三类信号。
 * Flux 可以发送 0 到多个 onNext，最终只能以 onComplete 或 onError 二选一结束。
 */
@Slf4j
public final class FluxSignalLogDemo {

    private FluxSignalLogDemo() {
    }

    public static void main(String[] args) {
//        observeSuccessfulFlow();
        observeFailedFlow();
    }

    private static void observeSuccessfulFlow() {
        log.info("========== Flux 场景一：多个数据，正常完成 ==========");

        Flux<String> tokens = Flux.just("你", "好", "！")
                .doOnSubscribe(ignored -> log.info("信号：onSubscribe"))
                .doOnNext(token -> log.info("信号：onNext，数据={}", token))
                .doOnComplete(() -> log.info("信号：onComplete"))
                .doFinally(signalType -> log.info("清理回调：doFinally，原因={}", signalType));

        log.info("Flux 已组装；只有订阅后才开始发送多个 Token");

        tokens.subscribe(
                token -> log.info("订阅者处理 Token：{}", token),
                error -> log.error("订阅者处理错误", error),
                () -> log.info("订阅者收到完成信号")
        );
    }

    private static void observeFailedFlow() {
        log.info("========== Flux 场景二：发送数据后错误结束 ==========");

        Flux<String> failedToolStream = Flux.concat(
                        Flux.just("工具参数校验通过", "开始调用外部工具"),
                        Flux.error(new IllegalStateException("外部工具调用失败"))
                )
                .doOnSubscribe(ignored -> log.info("信号：onSubscribe"))
                .doOnNext(event -> log.info("信号：onNext，数据={}", event))
                .doOnError(error -> log.warn("信号：onError，原因={}", error.getMessage()))
                .doFinally(signalType -> log.info("清理回调：doFinally，原因={}", signalType));

        failedToolStream.subscribe(
                event -> log.info("订阅者处理事件：{}", event),
                error -> log.error("订阅者收到错误：{}", error.getMessage()),
                () -> log.info("这条日志不会出现：错误流不会发送 onComplete")
        );
    }
}
