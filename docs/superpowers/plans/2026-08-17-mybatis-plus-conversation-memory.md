# MyBatis-Plus 会话记忆迁移实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 用 MyBatis-Plus 替换会话记忆中的全部 `JdbcTemplate` 操作，并完整映射 `ai_session` 表。

**架构：** `AiSessionEntity` 负责表字段映射，`AiSessionMapper` 提供 MyBatis-Plus 的 `BaseMapper` CRUD，`MySqlConversationMemory` 继续实现领域端口但通过 Mapper 完成存储。WebFlux 控制器的 `boundedElastic` 调度不变。

**技术栈：** Spring Boot 3.5、MyBatis-Plus 3.5.17、MySQL Connector/J、H2、JUnit 5。

---

## 文件结构

- 修改：`dodo-agent-learn/pom.xml`——移除直接 JDBC Starter，加入 MyBatis-Plus Boot 3 Starter。
- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/memory/AiSessionEntity.java`——完整表实体。
- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/memory/AiSessionMapper.java`——`BaseMapper` 接口。
- 修改：`dodo-agent-learn/src/main/java/com/jaycong/dodo/memory/MySqlConversationMemory.java`——改用 Mapper。
- 修改：`dodo-agent-learn/src/test/java/com/jaycong/dodo/memory/MySqlConversationMemoryTest.java`——通过 Mapper 驱动 H2 集成测试。
- 修改：`dodo-agent-learn/src/main/resources/application.yml`——保留用户确认的本机密码默认值。
- 创建：`dodo-agent-learn/tutorials/stages/05-mybatis-plus-conversation-memory.md`——阶段讲义。

### 任务 1：用失败测试定义 Mapper 适配器契约

**文件：**
- 修改：`dodo-agent-learn/src/test/java/com/jaycong/dodo/memory/MySqlConversationMemoryTest.java`

- [ ] **步骤 1：改写测试以注入尚不存在的 Mapper**

将 `@JdbcTest` 和 `JdbcTemplate` 替换为：

```java
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key")
class MySqlConversationMemoryTest {
    @Autowired
    private AiSessionMapper aiSessionMapper;
}
```

各测试使用 `aiSessionMapper.delete(null)` 清空数据，并用 `new AiSessionEntity()` 插入排序前置数据。保留五项原有端口断言，新增“实体完整映射”断言：写入 `tools`、`thinking`、`reference`、`fileId`、`recommend`、耗时和时间字段后，经 `selectById` 读回相同值。

- [ ] **步骤 2：运行目标测试验证失败**

运行：`mvn -s D:\\develop\\apache-maven-3.9.0\\conf\\settings-aliyun.xml -pl dodo-agent-learn -Dtest=MySqlConversationMemoryTest test`

预期：FAIL，编译错误指出 `AiSessionEntity` 与 `AiSessionMapper` 不存在。

### 任务 2：接入 MyBatis-Plus 实体和 Mapper

**文件：**
- 修改：`dodo-agent-learn/pom.xml`
- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/memory/AiSessionEntity.java`
- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/memory/AiSessionMapper.java`

- [ ] **步骤 1：替换依赖并声明完整实体**

移除 `spring-boot-starter-jdbc`，加入：

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    <version>3.5.17</version>
</dependency>
```

创建带 `@TableName("ai_session")` 的 `AiSessionEntity`，使用 `@TableId(value = "id", type = IdType.AUTO)` 映射主键，显式注解 `reference` 与 `fileid`，并声明设计规格列出的全部十四个属性。创建带 `@Mapper` 的 `AiSessionMapper extends BaseMapper<AiSessionEntity>`。每行有效 Java 代码添加具体中文注释。

- [ ] **步骤 2：运行目标测试验证仍失败**

运行：`mvn -s D:\\develop\\apache-maven-3.9.0\\conf\\settings-aliyun.xml -pl dodo-agent-learn -Dtest=MySqlConversationMemoryTest test`

预期：FAIL，失败原因是旧 `MySqlConversationMemory(JdbcTemplate)` 构造器与测试的新 Mapper 注入不匹配。

### 任务 3：将领域适配器改为 Mapper 操作

**文件：**
- 修改：`dodo-agent-learn/src/main/java/com/jaycong/dodo/memory/MySqlConversationMemory.java`

- [ ] **步骤 1：实现最少 MyBatis-Plus 适配器**

构造器改为接收 `AiSessionMapper`。`get` 使用：

```java
List<AiSessionEntity> newestFirst = aiSessionMapper.selectList(
        Wrappers.lambdaQuery(AiSessionEntity.class)
                .eq(AiSessionEntity::getSessionId, conversationId)
                .orderByDesc(AiSessionEntity::getCreateTime)
                .orderByDesc(AiSessionEntity::getId)
                .last("LIMIT 5"));
```

随后将每个实体的 `question`/`answer` 转为 `ConversationTurn`、反转并冻结。`append` 创建实体并设置 `sessionId`、`question`、`answer` 后调用 `insert`；`clear` 以 Lambda 条件调用 `delete` 并判断受影响行数。保留参数校验消息。

- [ ] **步骤 2：运行目标测试验证通过**

运行：`mvn -s D:\\develop\\apache-maven-3.9.0\\conf\\settings-aliyun.xml -pl dodo-agent-learn -Dtest=MySqlConversationMemoryTest test`

预期：PASS，真实 H2/MyBatis-Plus 验证完整字段映射和原有会话端口语义。

### 任务 4：验证运行时边界、文档和提交

**文件：**
- 修改：`dodo-agent-learn/src/main/resources/application.yml`
- 创建：`dodo-agent-learn/tutorials/stages/05-mybatis-plus-conversation-memory.md`

- [ ] **步骤 1：记录 MyBatis-Plus 学习要点**

文档说明实体、Mapper、`BaseMapper`、Lambda 条件构造器、`@Primary` 与 `boundedElastic` 的职责边界；说明完整字段映射不等于当前阶段全部写入；展示 `DODO_DB_PASSWORD` 默认值已是 `root`。

- [ ] **步骤 2：运行完整模块测试**

运行：`mvn -s D:\\develop\\apache-maven-3.9.0\\conf\\settings-aliyun.xml -pl dodo-agent-learn clean test -q`

预期：PASS，原有 Agent、控制器和全部新的 Mapper 集成测试均通过。

- [ ] **步骤 3：验证本机 MySQL 启动**

运行：`$env:dashcode_key = 'test-key'; mvn -s D:\\develop\\apache-maven-3.9.0\\conf\\settings-aliyun.xml -pl dodo-agent-learn spring-boot:run`。

预期：应用监听 8080；随后请求 `GET /api/agent/conversations/mybatis-startup-check/memory` 返回 200，最后停止验证进程；不发送模型请求且不写入对话数据。

- [ ] **步骤 4：提交阶段变更**

运行 `git status --short`、`git diff --check`，仅暂存计划列出的文件和用户修改的 `application.yml`，然后执行：

```powershell
git -C D:\develop\CodeProject\LLM\LLM-LEARN-JAVA commit -m "feat: migrate conversation memory to mybatis plus"
```

预期：全量测试证据存在，提交范围不含无关修改。

## 自检

- 规格覆盖度：任务 1 覆盖测试 API 与完整字段；任务 2 覆盖 Starter、实体和 Mapper；任务 3 覆盖所有 JDBC 替换；任务 4 覆盖配置、运行时与提交。
- 占位符扫描：每项实现提供精确文件、API、命令和预期结果，没有未定义步骤。
- 类型一致性：所有任务统一使用 `AiSessionEntity`、`AiSessionMapper`、`MySqlConversationMemory` 和 `ConversationMemory`。
