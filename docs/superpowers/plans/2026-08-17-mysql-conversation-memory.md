# MySQL 持久化会话记忆实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `superpowers:executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 复用 `dodo.ai_session`，让 Agent 的最近五轮会话记忆跨应用重启保存到 MySQL。

**架构：** 保留 `ConversationMemory` 端口和内存实现，新增基于 `JdbcTemplate` 的 MySQL 适配器并作为运行时首选 Bean。适配器只读写 `session_id`、`question`、`answer`；查询倒序取得五条后在 Java 恢复为正序，保证提示词按时间回放。

**技术栈：** Spring Boot 3.5、Spring JDBC、MySQL Connector/J、H2、JUnit 5。

---

## 文件结构

- 修改：`dodo-agent-learn/pom.xml`——加入 JDBC、MySQL 驱动和 H2 测试依赖。
- 修改：`dodo-agent-learn/src/main/resources/application.yml`——用环境变量声明数据源。
- 修改：`dodo-agent-learn/src/main/java/com/jaycong/dodo/web/ConversationMemoryController.java`——将阻塞记忆 I/O 切换到 `boundedElastic`。
- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/memory/MySqlConversationMemory.java`——MySQL 适配器。
- 创建：`dodo-agent-learn/src/test/java/com/jaycong/dodo/memory/MySqlConversationMemoryTest.java`——H2 真实 SQL 测试。
- 修改：`dodo-agent-learn/src/test/java/com/jaycong/dodo/web/ConversationMemoryControllerTest.java`——验证记忆 HTTP 端点不占用事件循环。
- 创建：`dodo-agent-learn/src/test/resources/application.properties`——隔离的 H2 数据源。
- 创建：`dodo-agent-learn/tutorials/stages/04-mysql-conversation-memory.md`——阶段讲义。

### 任务 1：准备 JDBC 运行环境

**文件：**
- 修改：`dodo-agent-learn/pom.xml`
- 修改：`dodo-agent-learn/src/main/resources/application.yml`
- 创建：`dodo-agent-learn/src/test/resources/application.properties`

- [ ] **步骤 1：加入 JDBC 和驱动依赖**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **步骤 2：配置不含密码的运行时数据源与隔离测试数据源**

在 `application.yml` 的 `spring` 下加入：

```yaml
datasource:
  url: ${DODO_DB_URL:jdbc:mysql://127.0.0.1:3306/dodo?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=GMT%2B8}
  username: ${DODO_DB_USERNAME:root}
  password: ${DODO_DB_PASSWORD}
  driver-class-name: com.mysql.cj.jdbc.Driver
```

创建测试配置：

```properties
spring.datasource.url=jdbc:h2:mem:dodo-agent-test;MODE=MySQL;DB_CLOSE_DELAY=-1
spring.datasource.username=sa
spring.datasource.password=
spring.datasource.driver-class-name=org.h2.Driver
```

- [ ] **步骤 3：验证依赖解析和现有测试**

运行：`mvn -s D:\develop\apache-maven-3.9.0\conf\settings-aliyun.xml -pl dodo-agent-learn test -q`

预期：依赖下载成功，原有测试通过。

### 任务 2：以失败测试定义 MySQL 记忆语义

**文件：**
- 创建：`dodo-agent-learn/src/test/java/com/jaycong/dodo/memory/MySqlConversationMemoryTest.java`

- [ ] **步骤 1：编写真实 JDBC 的失败测试**

测试用 `JdbcTemplate` 建立与现有 `ai_session` 对应的最小列集，构造尚不存在的 `MySqlConversationMemory`，并断言：

```java
assertThat(memory.get("conversation")).containsExactly(
        new ConversationTurn("问题一", "回答一"),
        new ConversationTurn("问题二", "回答二"));
assertThat(memory.get("conversation")).hasSize(5);
assertThat(memory.clear("conversation")).isTrue();
assertThat(memory.get("conversation")).isEmpty();
assertThatThrownBy(() -> memory.append(" ", new ConversationTurn("问题", "回答")))
        .isInstanceOf(IllegalArgumentException.class);
```

“最新五轮”测试插入六轮后断言第 2 至第 6 轮；“读取顺序”测试以相同 `create_time` 插入记录，断言自增 `id` 保证稳定顺序。

- [ ] **步骤 2：运行目标测试验证失败**

运行：`mvn -s D:\develop\apache-maven-3.9.0\conf\settings-aliyun.xml -pl dodo-agent-learn -Dtest=MySqlConversationMemoryTest test`

预期：FAIL，原因是 `MySqlConversationMemory` 尚未定义。

### 任务 3：隔离 WebFlux 中的阻塞记忆 I/O

**文件：**
- 修改：`dodo-agent-learn/src/test/java/com/jaycong/dodo/web/ConversationMemoryControllerTest.java`
- 修改：`dodo-agent-learn/src/main/java/com/jaycong/dodo/web/ConversationMemoryController.java`

- [ ] **步骤 1：编写失败的线程边界测试**

让测试记忆记录 `get` 和 `clear` 的线程名；通过 `WebTestClient` 调用两个端点后断言记录的线程名包含 `boundedElastic`。当前同步控制器会在 WebFlux 请求线程直接调用存储，因此该测试应失败。

- [ ] **步骤 2：运行控制器测试验证失败**

运行：`mvn -s D:\\develop\\apache-maven-3.9.0\\conf\\settings-aliyun.xml -pl dodo-agent-learn -Dtest=ConversationMemoryControllerTest test`

预期：FAIL，线程名不包含 `boundedElastic`。

- [ ] **步骤 3：将控制器操作转为反应式边界**

将 GET 和 DELETE 返回类型改为 `Mono`，使用 `Mono.fromCallable` 包装同步 JDBC 调用、使用 `subscribeOn(Schedulers.boundedElastic())` 隔离阻塞 I/O，并使用 `onErrorMap` 将存储异常转换为既有 HTTP 500。

- [ ] **步骤 4：运行控制器测试验证通过**

运行：`mvn -s D:\\develop\\apache-maven-3.9.0\\conf\\settings-aliyun.xml -pl dodo-agent-learn -Dtest=ConversationMemoryControllerTest test`

预期：PASS，原有 JSON 契约和错误响应不变，存储访问在线程池执行。

### 任务 4：实现 MySQL 适配器并验证绿灯

**文件：**
- 创建：`dodo-agent-learn/src/main/java/com/jaycong/dodo/memory/MySqlConversationMemory.java`
- 修改：`dodo-agent-learn/src/test/java/com/jaycong/dodo/memory/MySqlConversationMemoryTest.java`

- [ ] **步骤 1：编写最少 JDBC 实现**

构造器注入 `JdbcTemplate`，并以 `@Component`、`@Primary` 使应用默认注入持久化实现。核心 SQL：

```java
private static final String SELECT_RECENT_TURNS = """
        SELECT question, answer FROM ai_session
        WHERE session_id = ?
        ORDER BY create_time DESC, id DESC LIMIT 5
        """;
private static final String INSERT_TURN = """
        INSERT INTO ai_session (session_id, question, answer)
        VALUES (?, ?, ?)
        """;
private static final String DELETE_TURNS = "DELETE FROM ai_session WHERE session_id = ?";
```

`get` 反转倒序记录并返回 `List.copyOf`；`append` 插入一轮成功问答；`clear` 根据影响行数返回布尔值；三个方法复用非空白会话编号校验。每一行有效 Java 代码写具体中文注释。

- [ ] **步骤 2：运行目标测试验证通过**

运行：`mvn -s D:\develop\apache-maven-3.9.0\conf\settings-aliyun.xml -pl dodo-agent-learn -Dtest=MySqlConversationMemoryTest test`

预期：PASS，验证读、写、最近五轮、稳定排序、清空和参数校验。

- [ ] **步骤 3：运行全量模块测试**

运行：`mvn -s D:\develop\apache-maven-3.9.0\conf\settings-aliyun.xml -pl dodo-agent-learn clean test -q`

预期：PASS；切片测试继续显式使用内存实现，完整应用测试使用隔离 H2 数据源。

### 任务 5：记录阶段知识并提交

**文件：**
- 创建：`dodo-agent-learn/tutorials/stages/04-mysql-conversation-memory.md`

- [ ] **步骤 1：编写阶段文档**

说明端口不变的价值、`@Primary` 的选择规则、JDBC 阻塞调用为何必须沿用 Agent 的 `boundedElastic` 边界、三列映射、倒序查询后正序回放，并给出不含密码的启动方式：

```powershell
$env:DODO_DB_PASSWORD = '<你的本机密码>'
mvn -s D:\develop\apache-maven-3.9.0\conf\settings-aliyun.xml -pl dodo-agent-learn spring-boot:run
```

- [ ] **步骤 2：提交已验证变更**

运行：`git -C D:\develop\CodeProject\LLM\LLM-LEARN-JAVA status --short`，只暂存计划列出的阶段文件，然后执行：

```powershell
git -C D:\develop\CodeProject\LLM\LLM-LEARN-JAVA commit -m "feat: persist conversation memory in mysql"
```

预期：提交前全量测试为 PASS，提交范围不含无关文件。

## 自检

- 规格覆盖度：任务 1 覆盖连接与依赖；任务 2、3 覆盖 SQL 读写语义；任务 4 覆盖学习说明和提交。
- 占位符扫描：每项代码工作均给出文件、SQL、断言和命令，没有待定实现。
- 类型一致性：统一使用 `ConversationMemory`、`ConversationTurn`、`MySqlConversationMemory`、`JdbcTemplate` 与 `ai_session` 三列映射。
