# Web 通用基础能力实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 为非流式 REST 接口建立统一响应、分页载体、错误码和全局异常处理。

**架构：** 在 `common` 包中集中定义传输模型和异常边界。Controller 使用 `ApiResponse<T>` 返回成功结果，Service 使用 `BusinessException` 表达可预期业务失败，由 `GlobalExceptionHandler` 将异常转换为 HTTP 状态与统一失败体。

**技术栈：** Java 21、Spring Boot Web、Jakarta Validation、MyBatis-Plus、JUnit 5、Mockito、MockMvc。

---

## 文件结构

- 创建：`src/main/java/com/jaycong/know/engine/common/api/ApiResponse.java`：统一响应和静态工厂。
- 创建：`src/main/java/com/jaycong/know/engine/common/api/PageResponse.java`：分页响应数据。
- 创建：`src/main/java/com/jaycong/know/engine/common/error/ErrorCode.java`：错误码与 HTTP 映射。
- 创建：`src/main/java/com/jaycong/know/engine/common/error/BusinessException.java`：业务异常。
- 创建：`src/main/java/com/jaycong/know/engine/common/error/GlobalExceptionHandler.java`：全局异常到 JSON 响应的转换。
- 修改：文档 DTO、服务和 Controller，以及 `ChatController`：应用校验、异常和响应包装。
- 创建：`src/test/java/com/jaycong/know/engine/common/api/ApiResponseTest.java`、`PageResponseTest.java`：通用模型测试。
- 创建：`src/test/java/com/jaycong/know/engine/common/error/GlobalExceptionHandlerTest.java`：异常映射测试。
- 修改：`src/test/java/com/jaycong/llm/chat/ChatControllerTest.java`：断言非流式聊天返回统一响应。

### 任务 1：统一响应模型与分页模型

**文件：**
- 创建：`src/test/java/com/jaycong/know/engine/common/api/ApiResponseTest.java`
- 创建：`src/test/java/com/jaycong/know/engine/common/api/PageResponseTest.java`
- 创建：`src/main/java/com/jaycong/know/engine/common/api/ApiResponse.java`
- 创建：`src/main/java/com/jaycong/know/engine/common/api/PageResponse.java`

- [ ] **步骤 1：编写失败的响应模型测试**

```java
assertEquals(0, ApiResponse.success("内容").getCode());
assertEquals("内容", ApiResponse.success("内容").getData());
assertEquals(40400, ApiResponse.fail(ErrorCode.RESOURCE_NOT_FOUND).getCode());
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -Dtest=ApiResponseTest test`

预期：FAIL，找不到 `ApiResponse`。

- [ ] **步骤 3：实现最少响应模型**

```java
public static <T> ApiResponse<T> success(T data) {
    return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
}
```

- [ ] **步骤 4：编写失败的分页模型测试并运行**

```java
PageResponse<String> pageResponse = PageResponse.of(List.of("a"), 2, 10, 11);
assertEquals(2, pageResponse.getTotalPages());
```

运行：`mvn -Dtest=PageResponseTest test`

预期：FAIL，找不到 `PageResponse`。

- [ ] **步骤 5：实现分页模型并验证通过**

```java
public static <T> PageResponse<T> of(List<T> records, long pageNumber, long pageSize, long total) {
    long totalPages = pageSize == 0 ? 0 : (total + pageSize - 1) / pageSize;
    return new PageResponse<>(records, pageNumber, pageSize, total, totalPages);
}
```

运行：`mvn -Dtest=ApiResponseTest,PageResponseTest test`

预期：PASS。

### 任务 2：错误码和全局异常处理

**文件：**
- 创建：`src/test/java/com/jaycong/know/engine/common/error/GlobalExceptionHandlerTest.java`
- 创建：`src/main/java/com/jaycong/know/engine/common/error/ErrorCode.java`
- 创建：`src/main/java/com/jaycong/know/engine/common/error/BusinessException.java`
- 创建：`src/main/java/com/jaycong/know/engine/common/error/GlobalExceptionHandler.java`

- [ ] **步骤 1：编写失败的异常映射测试**

```java
ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(
        new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
assertEquals(40400, response.getBody().getCode());
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -Dtest=GlobalExceptionHandlerTest test`

预期：FAIL，找不到异常类型。

- [ ] **步骤 3：实现错误枚举、业务异常与异常处理器**

```java
@ExceptionHandler(BusinessException.class)
public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
    ErrorCode errorCode = exception.getErrorCode();
    return ResponseEntity.status(errorCode.getHttpStatus())
            .body(ApiResponse.fail(errorCode, exception.getMessage()));
}
```

- [ ] **步骤 4：验证异常测试通过**

运行：`mvn -Dtest=GlobalExceptionHandlerTest test`

预期：PASS。

### 任务 3：迁移 API、校验和业务异常

**文件：**
- 修改：`src/main/java/com/jaycong/know/engine/document/dto/DocumentRequest.java`
- 修改：`src/main/java/com/jaycong/know/engine/document/dto/TextUploadRequest.java`
- 修改：`src/main/java/com/jaycong/know/engine/document/dto/SegmentRequest.java`
- 修改：`src/main/java/com/jaycong/know/engine/document/service/DocumentServiceImpl.java`
- 修改：`src/main/java/com/jaycong/know/engine/document/service/SegmentServiceImpl.java`
- 修改：`src/main/java/com/jaycong/know/engine/document/controller/DocumentController.java`
- 修改：`src/main/java/com/jaycong/know/engine/document/controller/SegmentController.java`
- 修改：`src/main/java/com/jaycong/know/engine/ai/controller/ChatController.java`
- 修改：`src/test/java/com/jaycong/llm/chat/ChatControllerTest.java`

- [ ] **步骤 1：将聊天测试改为期望统一响应并运行**

```java
ApiResponse<String> response = chatController.chat("你好");
assertEquals(0, response.getCode());
assertEquals("你好，我是测试助手。", response.getData());
```

运行：`mvn -Dtest=ChatControllerTest test`

预期：FAIL，聊天方法当前返回 `String`。

- [ ] **步骤 2：修改 Controller 和 DTO**

```java
public ApiResponse<String> chat(@RequestParam @NotBlank String message) {
    return ApiResponse.success(chatModel.chat(message));
}
```

请求 DTO 的必填字段使用 `@NotBlank`、`@NotNull`、`@Positive`，对应 Controller 请求体使用 `@Valid`。

- [ ] **步骤 3：将资源不存在迁移为业务异常**

```java
if (document == null || Integer.valueOf(1).equals(document.getDeleted())) {
    throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "知识文档不存在");
}
```

- [ ] **步骤 4：运行受影响测试验证通过**

运行：`mvn -Dtest=ChatControllerTest,ApiResponseTest,PageResponseTest,GlobalExceptionHandlerTest test`

预期：PASS。

### 任务 4：全量验证

**文件：** 无新增文件。

- [ ] **步骤 1：执行全量测试与编译**

运行：`mvn test`

预期：全部测试通过，进程退出码为 0。

- [ ] **步骤 2：检查变更范围**

运行：`git diff --check && git diff -- src/main/java src/test/java`

预期：无空白错误，变更仅涉及本计划列出的 Web 通用能力文件。
