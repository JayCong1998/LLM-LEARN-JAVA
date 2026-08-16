package com.jaycong.llm.webflux.lesson01;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 运行本类，观察 Mono 从订阅到结束的完整信号流。
 */
@Slf4j
public final class MonoLogDemo {

    private MonoLogDemo() {
    }

    public static void main(String[] args) {
        Mono<String> greeting = Mono.just("Jay")
                .doOnSubscribe(subscription -> log.info("已订阅 Mono"))
                .map(name -> {
                    log.info("map 收到数据：{}", name);
                    return "Hello, " + name + "!";
                })
                .doOnNext(message -> log.info("Mono 发出数据：{}", message))
                .doOnSuccess(ignored -> log.info("Mono 正常结束"));

        log.info("Mono 已创建；此时尚未执行 map，因为还没有订阅者");

        greeting.subscribe(message -> log.info("订阅者收到数据：{}", message));

        log.info("主程序结束");
    }
}
