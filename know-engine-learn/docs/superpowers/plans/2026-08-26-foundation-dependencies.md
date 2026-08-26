# 基础依赖集成实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为学习版模块解析 LangChain4j OpenAI、MyBatis-Plus 和本地 MySQL 所需的 Maven 依赖。

**架构：** 仅修改模块 `pom.xml`，由 Spring Boot 3.5.6 父工程管理 Spring Framework、测试框架和 MySQL 驱动版本。LangChain4j 与 MyBatis-Plus 在子模块属性中显式锁定版本；不创建运行配置或业务代码。

**技术栈：** Java 21、Maven、Spring Boot 3.5.6、LangChain4j 1.19.0-beta29、MyBatis-Plus 3.5.17、MySQL Connector/J。

---

## 文件结构

- 修改：`LLM-LEARN-JAVA/know-engine-learn/pom.xml` — 声明学习模块的基础运行和测试依赖及版本属性。
- 验证：Maven 本地依赖树与测试生命周期；不新增 Java 测试，因为本次没有可测试的业务行为。

### 任务 1：声明基础依赖

**文件：**

- 修改：`LLM-LEARN-JAVA/know-engine-learn/pom.xml`
- 测试：`LLM-LEARN-JAVA/know-engine-learn/pom.xml` 的 Maven 依赖解析结果。

- [ ] **步骤 1：确认新增依赖在当前模块中尚不存在**

运行：

```powershell
mvn -pl know-engine-learn dependency:tree '-Dincludes=dev.langchain4j:langchain4j-open-ai-spring-boot-starter,com.baomidou:mybatis-plus-spring-boot3-starter,com.mysql:mysql-connector-j'
```

预期：命令成功，但输出中不包含以上 3 个 artifactId。

- [ ] **步骤 2：在 `pom.xml` 的 `<properties>` 中锁定第三方版本**

```xml
<langchain4j.version>1.19.0-beta29</langchain4j.version>
<mybatis-plus.version>3.5.17</mybatis-plus.version>
```

- [ ] **步骤 3：在 `pom.xml` 中添加运行和测试依赖**

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-open-ai-spring-boot-starter</artifactId>
        <version>${langchain4j.version}</version>
    </dependency>
    <dependency>
        <groupId>com.baomidou</groupId>
        <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
        <version>${mybatis-plus.version}</version>
    </dependency>
    <dependency>
        <groupId>com.mysql</groupId>
        <artifactId>mysql-connector-j</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

- [ ] **步骤 4：验证依赖已解析**

运行：

```powershell
mvn -pl know-engine-learn dependency:tree '-Dincludes=dev.langchain4j:langchain4j-open-ai-spring-boot-starter,com.baomidou:mybatis-plus-spring-boot3-starter,com.mysql:mysql-connector-j'
```

预期：输出分别包含 `langchain4j-open-ai-spring-boot-starter:1.19.0-beta29`、`mybatis-plus-spring-boot3-starter:3.5.17` 与 `mysql-connector-j`。

- [ ] **步骤 5：运行模块测试生命周期**

运行：

```powershell
mvn -pl know-engine-learn test
```

预期：`BUILD SUCCESS`；不需要配置 `OPENAI_API_KEY` 或启动本地 MySQL。

- [ ] **步骤 6：提交依赖变更**

```powershell
git add LLM-LEARN-JAVA/know-engine-learn/pom.xml
git commit -m "build: add llm and persistence dependencies"
```
