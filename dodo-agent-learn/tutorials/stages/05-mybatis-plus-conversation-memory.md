# 阶段 5：用 MyBatis-Plus 持久化会话记忆

上一阶段已经把会话记忆保存到 MySQL，但 `MySqlConversationMemory` 自己维护 SQL 文本并调用 `JdbcTemplate`。本阶段改用 MyBatis-Plus：适配器仍然实现同一个 `ConversationMemory` 端口，数据访问细节则交给实体和 Mapper。

## 三个角色

```text
ConversationMemory → MySqlConversationMemory → AiSessionMapper → ai_session
                         领域适配器              数据访问接口       数据表
```

- `AiSessionEntity`：完整映射 `ai_session` 的 14 个字段。完整映射代表后续功能可以直接使用这些字段，不代表本阶段每次插入都会填满它们。
- `AiSessionMapper`：继承 `BaseMapper<AiSessionEntity>`，MyBatis-Plus 自动提供 `selectList`、`insert`、`delete` 等通用操作。
- `MySqlConversationMemory`：继续负责领域规则，例如校验会话编号、只取五轮、把数据恢复为时间正序，以及把删除行数转换为布尔结果。

## Lambda 条件构造器

读取历史时，适配器通过 `Wrappers.lambdaQuery(AiSessionEntity.class)` 构造条件：

```java
Wrappers.lambdaQuery(AiSessionEntity.class)
        .eq(AiSessionEntity::getSessionId, conversationId)
        .orderByDesc(AiSessionEntity::getCreateTime)
        .orderByDesc(AiSessionEntity::getId)
        .last("LIMIT 5")
```

方法引用避免了手写 `session_id`、`create_time` 等列名；字段重命名时，编译器可以帮助发现大部分问题。`LIMIT 5` 是仓库内的固定常量，不拼接外部输入。

数据库先倒序取得最新五条，适配器再将列表反转。这样既让数据库高效裁剪窗口，又让模型看到“旧问题、旧回答、……、当前问题”的自然对话顺序。

## 完整字段映射

当前记忆写入只设置：

- `sessionId`
- `question`
- `answer`

工具名称、首/总耗时、引用、Agent 类型、思考过程、文件编号和推荐内容均映射在实体中，但应在未来拥有明确业务含义和生命周期后再写入。不要因为实体有字段就用空字符串填充它们。

`reference` 和 `fileid` 使用 `@TableField` 显式绑定；其余下划线列依赖 MyBatis-Plus 的驼峰映射。主键使用 `IdType.AUTO`，由数据库负责生成并回填 `id`。

## MyBatis-Plus 仍是阻塞 I/O

MyBatis-Plus 底层仍使用 JDBC，因此它不是响应式数据库驱动。`ManualReactAgent` 的运行循环和记忆 GET/DELETE 控制器已经将同步存储操作调度到 `boundedElastic`，这层边界在切换 Mapper 后仍然必须保留。

## 本机配置

当前学习环境默认连接本机 `dodo` 数据库，密码默认值已配置为 `root`：

```yaml
spring:
  datasource:
    password: ${DODO_DB_PASSWORD:root}
```

生产环境应通过 `DODO_DB_PASSWORD` 覆盖默认值，不要把真实生产密码写入仓库。

## 本阶段验证

测试在 H2 内存数据库中执行真实 MyBatis-Plus Mapper 操作，覆盖完整字段映射、写入、读取、最近五轮、稳定排序、清空和参数校验。现有 WebFlux 控制器测试继续验证同步数据库访问运行在 `boundedElastic` 线程，而非 Netty 事件循环。
