# Elasticsearch 与 MinIO 基础接入实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为知识引擎提供 DashScope embedding、Elasticsearch 向量存储和 MinIO 客户端的基础 Spring Bean 配置。

**架构：** 在 `storage` 包中将 Elasticsearch 和 MinIO 参数映射为类型安全的 `@ConfigurationProperties`，由单个配置类创建 `EmbeddingModel`、`EmbeddingStore<TextSegment>` 和 `MinioClient`。本次不把它们接入既有文档上传流程，避免改变当前 CRUD 行为。

**技术栈：** Java 21、Spring Boot 3、LangChain4j 1.11、Elasticsearch、MinIO Java SDK、JUnit 5。

---

## 文件结构

- `pom.xml`：声明 LangChain4j Elasticsearch 与 MinIO SDK 依赖。
- `src/main/resources/application.yml`：提供可由环境变量覆盖的 Elasticsearch、DashScope embedding、MinIO 默认配置。
- `src/main/java/com/jaycong/know/engine/storage/config/ElasticsearchProperties.java`：映射向量存储与 embedding 参数。
- `src/main/java/com/jaycong/know/engine/storage/config/MinioProperties.java`：映射 MinIO 连接参数。
- `src/main/java/com/jaycong/know/engine/storage/config/StorageConfiguration.java`：声明三个可注入 Bean。
- `src/test/java/com/jaycong/know/engine/storage/config/StorageConfigurationTest.java`：验证配置属性能创建预期 Bean，且不访问外部服务。

### 任务 1：定义失败的配置上下文测试

**文件：**
- 创建：`src/test/java/com/jaycong/know/engine/storage/config/StorageConfigurationTest.java`

- [ ] **步骤 1：编写失败的测试**

```java
@Test
void shouldCreateEmbeddingAndObjectStorageBeans() {
    AnnotationConfigApplicationContext applicationContext = new AnnotationConfigApplicationContext();
    TestPropertySourceUtils.addInlinedPropertiesToEnvironment(applicationContext,
        "elasticsearch.host=http://127.0.0.1:9200",
        "elasticsearch.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1",
        "elasticsearch.model-name=text-embedding-v4",
        "elasticsearch.api-key=test-key",
        "elasticsearch.dimensions=1536",
        "minio.endpoint=http://localhost:9000",
        "minio.access-key=minioadmin",
        "minio.secret-key=minioadmin");
    applicationContext.register(StorageConfiguration.class);
    applicationContext.refresh();

    assertThat(applicationContext.getBean(EmbeddingModel.class)).isNotNull();
    assertThat(applicationContext.getBean(EmbeddingStore.class)).isNotNull();
    assertThat(applicationContext.getBean(MinioClient.class)).isNotNull();
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn test -Dtest=StorageConfigurationTest`

预期：FAIL，原因是 `StorageConfiguration` 和相关 Bean 尚不存在。

### 任务 2：添加客户端依赖与配置模型

**文件：**
- 修改：`pom.xml`
- 创建：`src/main/java/com/jaycong/know/engine/storage/config/ElasticsearchProperties.java`
- 创建：`src/main/java/com/jaycong/know/engine/storage/config/MinioProperties.java`

- [ ] **步骤 1：添加最小依赖**

```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-elasticsearch</artifactId>
    <version>1.11.0</version>
</dependency>
<dependency>
    <groupId>io.minio</groupId>
    <artifactId>minio</artifactId>
    <version>8.5.17</version>
</dependency>
```

- [ ] **步骤 2：创建属性模型**

```java
@ConfigurationProperties(prefix = "elasticsearch")
public class ElasticsearchProperties {
    private String host;
    private String baseUrl;
    private String modelName;
    private String apiKey;
    private Integer dimensions;
}
```

```java
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {
    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucketName;
}
```

- [ ] **步骤 3：运行测试验证仍失败**

运行：`mvn test -Dtest=StorageConfigurationTest`

预期：FAIL，原因是 `StorageConfiguration` 仍不存在。

### 任务 3：创建 Storage Bean 配置

**文件：**
- 创建：`src/main/java/com/jaycong/know/engine/storage/config/StorageConfiguration.java`

- [ ] **步骤 1：创建最小 Bean 配置**

```java
@Configuration
@EnableConfigurationProperties({ElasticsearchProperties.class, MinioProperties.class})
public class StorageConfiguration {

    @Bean
    public EmbeddingModel embeddingModel(ElasticsearchProperties elasticsearchProperties) {
        return OpenAiEmbeddingModel.builder()
            .baseUrl(elasticsearchProperties.getBaseUrl())
            .apiKey(elasticsearchProperties.getApiKey())
            .modelName(elasticsearchProperties.getModelName())
            .dimensions(elasticsearchProperties.getDimensions())
            .build();
    }
}
```

在同一配置类补充 `EmbeddingStore<TextSegment>`，使用 `host` 与 `dimensions` 创建 `ElasticsearchEmbeddingStore`；再使用 `endpoint`、`accessKey`、`secretKey` 创建 `MinioClient`。为类、公开方法与字段添加中文多行 Javadoc，符合 `AGENTS.md`。

- [ ] **步骤 2：运行测试验证通过**

运行：`mvn test -Dtest=StorageConfigurationTest`

预期：PASS，测试执行期间不发生 HTTP 连接。

### 任务 4：提供默认运行配置并回归验证

**文件：**
- 修改：`src/main/resources/application.yml`

- [ ] **步骤 1：补充外部化配置**

```yaml
elasticsearch:
  host: ${ELASTICSEARCH_HOST:http://127.0.0.1:9200}
  base-url: ${DASHSCOPE_EMBEDDING_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
  model-name: ${DASHSCOPE_EMBEDDING_MODEL_NAME:text-embedding-v4}
  api-key: ${DASHSCOPE_API_KEY:}
  dimensions: ${ELASTICSEARCH_DIMENSIONS:1536}

minio:
  endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
  access-key: ${MINIO_ACCESS_KEY:minioadmin}
  secret-key: ${MINIO_SECRET_KEY:minioadmin}
  bucket-name: ${MINIO_BUCKET_NAME:know-engine}
```

- [ ] **步骤 2：执行回归验证**

运行：`mvn test`

预期：PASS，既有测试与新增配置测试均通过。

- [ ] **步骤 3：检查格式化与变更范围**

运行：`git diff --check` 和 `git diff -- pom.xml src/main/resources/application.yml src/main/java/com/jaycong/know/engine/storage src/test/java/com/jaycong/know/engine/storage`

预期：无空白错误；仅包含依赖、配置、存储配置类和相应测试的改动。
