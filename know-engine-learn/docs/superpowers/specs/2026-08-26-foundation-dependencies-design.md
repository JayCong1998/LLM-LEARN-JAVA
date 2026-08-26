# 学习版基础依赖设计

## 目标

为 `know-engine-learn` 集成 LLM 与数据库的基础依赖，但不创建任何数据表、实体、Mapper、配置类或 HTTP 接口。

## 范围

- 使用 Spring Boot 作为应用框架。
- 使用 LangChain4j 的 OpenAI 集成，后续通过 `OPENAI_API_KEY` 对接 OpenAI API。
- 使用 MyBatis-Plus 和 MySQL Connector/J，为后续本地 MySQL 数据持久化准备运行时依赖。
- 保留现有项目包结构和入口类。

## 不在本次范围内

- 不创建数据库、数据表或初始化 SQL。
- 不新增 `application.yml`、密钥或数据库连接配置。
- 不实现聊天接口、会话、消息持久化、流式输出或 RAG。
- 不引入 Elasticsearch、Redis、MinIO 或其他中间件。

## 依赖选择

| 能力 | 依赖 | 用途 |
| --- | --- | --- |
| Web 应用 | `spring-boot-starter-web` | 后续提供 REST API 的基础运行环境。 |
| LLM | `langchain4j-open-ai-spring-boot-starter` | 后续创建并注入 OpenAI Chat Model。 |
| ORM | `mybatis-plus-spring-boot3-starter` | 后续实现 MyBatis-Plus 数据访问层。 |
| 数据库驱动 | `mysql-connector-j` | 连接本地 MySQL 8。 |
| 测试 | `spring-boot-starter-test` | 后续运行 Spring Boot 测试。 |

## 验收标准

- Maven 能解析所有新增依赖。
- 项目可以执行 `mvn test`，且不需要 OpenAI 密钥或本地 MySQL 实例。
- `pom.xml` 不包含真实 API Key、数据库密码或连接地址。
