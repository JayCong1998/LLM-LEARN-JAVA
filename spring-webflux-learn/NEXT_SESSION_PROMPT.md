# 新会话续学提示词

在新电脑或新会话中，复制下面内容发送给 Codex：

```text
请继续带我学习 spring-webflux-learn。
先阅读 spring-webflux-learn/README.md，按其中 Demo 索引恢复上下文。

我已学到第 11 节：
1. Reactor 信号、Mono / Flux
2. 冷流、热流、Sinks.multicast
3. Disposable 取消订阅
4. subscribeOn / publishOn
5. Reactor Context
6. 错误恢复、timeout、retryWhen
7. 背压
8. flatMap / concatMap 并发工具调用
9. 并发工具的错误隔离

我要求：不要写 @Test demo；示例放 src/main/java，可直接运行，并使用 Lombok @Slf4j 打日志。
请从下一节继续。
```
