# MyBatis-Plus 会话记忆迁移设计

## 目标

将 `dodo-agent-learn` 当前 `MySqlConversationMemory` 中的全部 `JdbcTemplate` 操作迁移到 MyBatis-Plus，同时完整映射本机 `dodo.ai_session` 的全部字段。对外 `ConversationMemory` 契约、历史窗口大小、排序和 WebFlux 线程边界均保持不变。

## 范围

- 引入 Spring Boot 3 对应的 `mybatis-plus-spring-boot3-starter` 3.5.17。
- 删除模块对 `spring-boot-starter-jdbc` 的直接依赖和生产代码中的 `JdbcTemplate` 引用。
- 保留 MySQL Connector/J、H2 测试驱动和已有数据源配置。
- 新增完整表实体与 `BaseMapper` Mapper。
- 改写 MySQL 记忆适配器和它的真实 H2 集成测试。
- 不增加 Service 层、XML Mapper、分页插件、字段自动填充或新的 HTTP 接口。

## 架构

```text
ManualReactAgent / ConversationMemoryController
                    │ ConversationMemory
                    ▼
          MySqlConversationMemory
                    │ BaseMapper<AiSessionEntity>
                    ▼
             AiSessionMapper
                    │ MyBatis-Plus
                    ▼
              dodo.ai_session
```

`ManualReactAgent` 和 `ConversationMemoryController` 仍依赖 `ConversationMemory`，因此无需知道持久化技术变化。控制器已经用 `boundedElastic` 隔离同步存储调用；MyBatis-Plus 同样基于阻塞 JDBC，故该线程边界继续有效。

## 组件

### AiSessionEntity

文件：`dodo-agent-learn/src/main/java/com/jaycong/dodo/memory/AiSessionEntity.java`

使用 `@TableName("ai_session")` 映射表，并映射全部字段：

| Java 属性 | 数据库字段 | Java 类型 |
| --- | --- | --- |
| `id` | `id` | `Long` |
| `sessionId` | `session_id` | `String` |
| `question` | `question` | `String` |
| `answer` | `answer` | `String` |
| `tools` | `tools` | `String` |
| `firstResponseTime` | `first_response_time` | `Long` |
| `totalResponseTime` | `total_response_time` | `Long` |
| `createTime` | `create_time` | `LocalDateTime` |
| `updateTime` | `update_time` | `LocalDateTime` |
| `reference` | `reference` | `String` |
| `agentType` | `agent_type` | `String` |
| `thinking` | `thinking` | `String` |
| `fileId` | `fileid` | `String` |
| `recommend` | `recommend` | `String` |

`id` 使用 `@TableId(type = IdType.AUTO)`，让数据库的自增主键规则保持权威。`reference` 和 `fileId` 使用显式 `@TableField`；其余下划线字段由 MyBatis-Plus 的驼峰映射处理。

### AiSessionMapper

文件：`dodo-agent-learn/src/main/java/com/jaycong/dodo/memory/AiSessionMapper.java`

接口使用 `@Mapper` 和 `BaseMapper<AiSessionEntity>`。当前不定义自定义 SQL：`selectList`、`insert`、`delete` 足以表达会话记忆的三项操作。

### MySqlConversationMemory

构造器从注入 `JdbcTemplate` 改为注入 `AiSessionMapper`，继续带 `@Component` 和 `@Primary`。

- `get(conversationId)`：以 Lambda 条件构造器匹配 `sessionId`，按 `createTime`、`id` 降序排序，静态追加 `LIMIT 5`，将实体转换为 `ConversationTurn` 后反转并返回不可变快照。
- `append(conversationId, turn)`：创建只设置 `sessionId`、`question`、`answer` 的实体，调用 `mapper.insert`。其他字段让数据库默认值或后续业务阶段决定。
- `clear(conversationId)`：以 Lambda 条件构造器匹配 `sessionId`，根据 `mapper.delete` 返回的影响行数产生布尔值。
- 会话编号与空轮次的校验、异常消息保持当前行为。

## 数据流和排序

数据库先按 `create_time DESC, id DESC` 返回最新五条，以避免完整会话历史进入 JVM；适配器再反转列表，使模型消息按旧到新的顺序回放。`id` 是相同创建时间下的稳定次序。

## 测试与验证

`MySqlConversationMemoryTest` 改为完整 Spring 上下文的 H2 集成测试，测试类使用测试 API Key 避免模型自动配置失败。测试通过 Mapper 在每个用例前后建立和清理最小 `ai_session` 表，覆盖：

- 写入并以正序读取；
- 六轮历史只返回最近五轮；
- 相同 `create_time` 时以 `id` 稳定排序；
- 清空只影响指定会话并准确返回是否删除；
- 空白会话编号与空轮次拒绝。

现有 `ConversationMemoryControllerTest` 保留，以验证 MyBatis-Plus 同样在 `boundedElastic` 工作线程运行。

## 风险与约束

- `last("LIMIT 5")` 只拼接仓库内固定常量，不接受外部输入，因此没有 SQL 注入入口。
- H2 的测试表只使用适配器涉及的最小列；实体可以映射生产表的完整字段而无需每个字段都在本阶段读写。
- 本阶段不添加 MyBatis-Plus 分页插件，因为固定窗口只需静态 `LIMIT 5`，避免为单个查询引入额外配置。
