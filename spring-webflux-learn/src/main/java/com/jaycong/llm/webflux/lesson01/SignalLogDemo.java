package com.jaycong.llm.webflux.lesson01;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 第一节：观察 Reactor 的三类信号与订阅触发时机。
 */
@Slf4j
public final class SignalLogDemo {

    private SignalLogDemo() {
    }

    public static void main(String[] args) {
        observeSuccessfulFlow();
        observeFailedFlow();
    }

    private static void observeSuccessfulFlow() {
        log.info("========== 场景一：正常完成 ==========");

        Mono<String> token = Mono.just("Hello")
                .doOnSubscribe(ignored -> log.info("信号：onSubscribe"))
                .doOnNext(value -> log.info("信号：onNext，数据={}", value))
                .doOnSuccess(value -> log.info("观察回调：doOnSuccess，结果={}", value))
                .doFinally(signalType -> log.info("清理回调：doFinally，原因={}", signalType));

        log.info("Mono 已组装；没有订阅时，不会发出任何信号");

        token.subscribe(
                value -> log.info("订阅者处理数据：{}", value),
                error -> log.error("订阅者处理错误", error),
                () -> log.info("订阅者收到完成信号")
        );
    }

    private static void observeFailedFlow() {
        log.info("========== 场景二：错误结束 ==========");

        Mono<String> failedToolCall = Mono.defer(() -> {
                    log.info("开始执行模拟工具调用");
                    return Mono.<String>error(new IllegalStateException("工具调用失败"));
                })
                .doOnSubscribe(ignored -> log.info("信号：onSubscribe"))
                .doOnError(error -> log.warn("信号：onError，原因={}", error.getMessage()))
                .doFinally(signalType -> log.info("清理回调：doFinally，原因={}", signalType));

        log.info("错误流已组装；仍然要等订阅后才会执行工具调用");

        failedToolCall.subscribe(
                value -> log.info("订阅者处理数据：{}", value),
                error -> log.error("订阅者收到错误：{}", error.getMessage()),
                () -> log.info("这条日志不会出现：错误流不会发送 onComplete")
        );
    }
}
