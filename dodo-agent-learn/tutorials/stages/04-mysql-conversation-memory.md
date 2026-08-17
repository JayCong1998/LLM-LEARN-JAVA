# 阶段 4：MySQL 持久化会话记忆

上一阶段的 `InMemoryConversationMemory` 能够保存最近五轮问答，但 JVM 重启后数据就消失了。本阶段保持 `ConversationMemory` 接口不变，新增 `MySqlConversationMemory`，让同一份 Agent 编排逻辑可以换用数据库实现。

## 复用已有 `ai_session` 表

本地 `dodo.ai_session` 已具备本阶段所需字段和索引：

| 表字段 | 领域含义 |
| --- | --- |
| `session_id` | 当前 HTTP 请求携带的 `conversationId` |
| `question` | `ConversationTurn.userContent` |
| `answer` | `ConversationTurn.assistantContent` |
| `create_time`、`id` | 对话轮次的稳定排序键 |

工具名称、思考过程、引用资料等其他列暂时不写入；它们会在后续“运行轨迹持久化”阶段拥有各自明确的写入时机。

## 为什么接口不需要改变

`ManualReactAgent` 仍然只知道三个动作：`get`、`append`、`clear`。它并不知道数据存放在内存还是 MySQL，因此：

- 单元测试可以继续注入 `InMemoryConversationMemory`，启动快且没有数据库依赖；
- 正常应用上下文会选中标记为 `@Primary` 的 `MySqlConversationMemory`；
- 将来替换为 Redis 或向量数据库时，也只需再实现同一个端口。

## 查询最近五轮的顺序

SQL 使用 `ORDER BY create_time DESC, id DESC LIMIT 5`。倒序能让数据库先拿到最新五条，避免把整个会话历史读进应用；随后 Java 反转列表，最终提示词仍按“旧问题、旧回答、……、当前问题”的自然时间顺序构造。

`id` 是 `create_time` 相同情况下的第二排序键。它避免并发或低精度时间戳导致数据库返回不稳定顺序。

## JDBC 与 WebFlux 线程

`JdbcTemplate` 是同步阻塞 API，不能在 Netty 事件循环线程直接执行：一次慢查询会拖住同一线程上的其他连接。

Agent 的运行循环已经运行在 `boundedElastic`；记忆管理的 GET/DELETE 接口现在也用 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` 包裹同步存储调用。HTTP 仍是反应式的，但真正等待数据库的工作交给专用工作线程池。

## 本机启动

数据库地址和用户名可通过 `DODO_DB_URL`、`DODO_DB_USERNAME` 覆盖；密码只读取 `DODO_DB_PASSWORD`，不写入仓库。

```powershell
$env:DODO_DB_PASSWORD = '<你的本机密码>'
mvn -s D:\develop\apache-maven-3.9.0\conf\settings-aliyun.xml -pl dodo-agent-learn spring-boot:run
```

启动后，同一 `conversationId` 的成功问答会插入 `dodo.ai_session`；重启应用后再次访问该会话，最近五轮仍会被加载进模型上下文。

## 本阶段的边界

数据库持久化只保存正常完成的最终问答。模型异常、用户取消、回答为空、会话被并发拒绝以及写库失败时，沿用上一阶段 Agent 的失败语义，不把不完整轮次写入历史。
