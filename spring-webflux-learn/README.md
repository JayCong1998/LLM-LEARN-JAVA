# Spring WebFlux / Reactor 学习项目

这是一个面向 Agent 开发场景的 Spring WebFlux 与 Reactor 学习项目。每个示例都位于 `src/main/java`，可直接运行，并使用 Lombok `@Slf4j` 输出关键日志。

当前内容先聚焦 Reactor：理解流的信号、冷热流、`Sinks`、取消订阅、线程调度、上下文与错误恢复；这些是后续接入 SSE、LLM Token 流、工具调用和多 Agent 编排的基础。

## 环境

- JDK 21
- Maven 3.9+

以下命令均在 `LLM-LEARN-JAVA` 目录执行。

```powershell
mvn -pl spring-webflux-learn package -DskipTests
```

启动 Spring Boot 应用：

```powershell
mvn -pl spring-webflux-learn spring-boot:run
```

## 运行单个 Demo

通用命令：

```powershell
mvn '-Dexec.mainClass=完整类名' -pl spring-webflux-learn compile exec:java
```

例如运行热流示例：

```powershell
mvn '-Dexec.mainClass=com.jaycong.llm.webflux.lesson01.HotSinkLogDemo' -pl spring-webflux-learn compile exec:java
```

## Demo 索引

| 节次 | 类 | 核心内容 | 建议观察的日志 |
| --- | --- | --- | --- |
| 1 | `SignalLogDemo` | `Mono` 的 `onSubscribe`、`onNext`、`onComplete` / `onError` | Mono 只会产生最多一个数据；取消 `observeFailedFlow()` 的注释可查看错误结束。 |
| 1（补充） | `FluxSignalLogDemo` | `Flux` 的多次 `onNext` 与终止信号 | `你`、`好`、`！` 依次发送，最后才 `onComplete`；也可取消错误场景的注释。 |
| 2 | `ColdFluxLogDemo` | 冷流 | `client-A` 与 `client-B` 分别触发一次数据源，订阅计数为 1、2。 |
| 3 | `HotSinkLogDemo` | 热流、`Sinks.Many`、`multicast` | 生产者每个 Token 只发送一次，两个客户端都收到。 |
| 4 | `DisposableCancelLogDemo` | `Disposable` 与取消订阅 | 客户端调用 `dispose()` 后，上游收到 `cancel` 并停止生成。 |
| 5 | `SchedulerLogDemo` | `subscribeOn`、`publishOn` | 上游运行在线程 `model-client-*`，切换后的下游运行在 `web-response-*`。 |
| 6 | `ReactorContextLogDemo` | Reactor `Context` | 每个 Agent 步骤读取同一个 `requestId` 和 `agentId`。 |
| 7 | `ErrorHandlingLogDemo` | 单个错误处理操作符 | 对比 `onErrorReturn`、`onErrorResume`、`onErrorMap`。 |
| 7（补充） | `CombinedErrorHandlingLogDemo` | 组合错误处理链 | 先异常转换，再尝试备用工具，最后返回固定兜底结果。 |
| 8 | `TimeoutRetryLogDemo` | `timeout` 与 `retryWhen` | 前两次模型调用超时，按指数退避重试，第三次成功。 |
| 9 | `BackpressureDropLogDemo` | 背压与 `onBackpressureDrop` | 生产速度快于消费速度时，观察被丢弃的 Token。 |
| 10 | `AsyncToolCallLogDemo` | `flatMap` 与 `concatMap` | 对比并发工具调用的完成顺序与串行调用的输入顺序。 |
| 11 | `ToolErrorIsolationLogDemo` | 并发工具的错误隔离 | 在每个 `flatMap` 子流中降级，避免一个工具失败取消其他工具。 |

所有 Demo 都在：

```text
src/main/java/com/jaycong/llm/webflux/lesson01
```

## 当前学习重点

### 信号与订阅

Reactor 流只有在 `subscribe()` 后才会执行。数据使用 `onNext` 发送，流最终只能以 `onComplete`、`onError` 或 `cancel` 之一结束。

### 冷流与热流

- 冷流：每个订阅者各自重新执行上游，例如每位用户各自发起一次 LLM 请求。
- 热流：多个当前订阅者共享同一上游，例如将一次 LLM Token 流广播给网页、日志与监控。
- `Sinks.Many`：把外部回调或事件主动推入 `Flux` 的入口。

`multicast()` 只向当前订阅者广播后续数据，不会给后来加入的订阅者补发历史 Token。

### 取消与线程

- `Disposable` 是订阅控制柄。调用 `dispose()` 会取消订阅，不会发送 `onComplete`。
- `subscribeOn` 决定上游从哪个线程启动。
- `publishOn` 从出现的位置开始切换下游线程。
- WebFlux 的 Netty 事件循环不应执行阻塞操作；调用阻塞 SDK 时通常使用 `Schedulers.boundedElastic()`。

### 上下文与错误恢复

- `Context` 随订阅传播，适合传递 `requestId`、用户 ID、Agent ID、Trace ID；它不是 `ThreadLocal`。
- `onErrorMap`：统一转换底层异常。
- `onErrorResume`：切换到备用流，例如主模型失败后调用备用模型。
- `onErrorReturn`：提供最终固定兜底值，通常放在错误处理链最后。

## 建议复习顺序

1. 先运行 `SignalLogDemo` 与 `FluxSignalLogDemo`，确认信号顺序。
2. 对比 `ColdFluxLogDemo` 和 `HotSinkLogDemo`，理解每个订阅是否会重新执行上游。
3. 运行 `DisposableCancelLogDemo` 与 `SchedulerLogDemo`，掌握取消和线程边界。
4. 运行 `ReactorContextLogDemo`，理解请求级元数据如何随流传播。
5. 学习两个错误处理 Demo，为模型与工具调用建立降级策略。
6. 运行 `TimeoutRetryLogDemo`，理解超时会取消本次调用，而重试会重新订阅上游。
7. 运行 `BackpressureDropLogDemo`，理解慢消费者下的积压控制策略。
8. 运行 `AsyncToolCallLogDemo`，选择 Agent 工具调用的并发或串行方式。
9. 运行 `ToolErrorIsolationLogDemo`，学习在并发工具调用中隔离单个失败。

后续课程会在这些 Reactor 基础上继续实现 WebFlux SSE、LLM Token 流、并发工具调用与 Agent 编排。
