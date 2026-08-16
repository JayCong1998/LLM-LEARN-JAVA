package com.jaycong.llm.webflux.lesson01;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 第六节：Reactor Context 为一次订阅传递请求级元数据。
 */
@Slf4j
public final class ReactorContextLogDemo {

    private static final String REQUEST_ID = "requestId";
    private static final String AGENT_ID = "agentId";

    private ReactorContextLogDemo() {
    }

    public static void main(String[] args) {
        Flux.deferContextual(context -> {
                    String requestId = context.get(REQUEST_ID);
                    String agentId = context.get(AGENT_ID);
                    log.info("上游读取 Context：requestId={}，agentId={}", requestId, agentId);

                    return Flux.just("规划任务", "调用搜索工具", "汇总答案");
                })
                .flatMap(step -> Mono.deferContextual(context -> {
                    log.info("执行步骤={}，关联 requestId={}", step, context.get(REQUEST_ID));
                    return Mono.just(step + " 完成");
                }))
                // Context 从这里向上游传播，因此通常放在链路末尾。
                .contextWrite(context -> context
                        .put(REQUEST_ID, "req-20260816-001")
                        .put(AGENT_ID, "travel-agent"))
                .subscribe(result -> log.info("订阅者收到：{}", result));
    }
}
