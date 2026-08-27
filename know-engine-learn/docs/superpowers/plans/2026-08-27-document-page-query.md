# 文档分页查询 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为文档列表提供可筛选的分页接口，并使页面按统一响应结构渲染文档数据。

**Architecture:** `DocumentController` 绑定并校验分页及筛选参数，委派 `DocumentService`。`DocumentServiceImpl` 使用 MyBatis-Plus `Page` 和 `LambdaQueryWrapper` 组合查询条件，将结果转换为既有 `PageResponse`；前端只在 `loadDocuments` 中解析 `ApiResponse.data`。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、JUnit 5、Mockito、原生 HTML/JavaScript。

---

### Task 1: 为文档分页服务建立失败测试

**Files:**
- Modify: `src/test/java/com/jaycong/know/engine/document/service/DocumentServiceTest.java`
- Modify: `src/main/java/com/jaycong/know/engine/document/service/DocumentService.java`
- Modify: `src/main/java/com/jaycong/know/engine/document/service/impl/DocumentServiceImpl.java`

- [ ] **Step 1: 写入分页查询的失败测试**

```java
@Test
void pageReturnsUndeletedDocumentsWithAllFilters() {
    Page<KnowledgeDocument> page = new Page<>(1, 10);
    page.setRecords(List.of(new KnowledgeDocument()));
    page.setTotal(1);
    when(documentMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);

    PageResponse<KnowledgeDocument> response = documentService.page(1, 10, "知识", "INIT", "DOCUMENT_SEARCH");

    assertEquals(1, response.getTotal());
    assertEquals(1, response.getRecords().size());
    ArgumentCaptor<LambdaQueryWrapper<KnowledgeDocument>> queryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(documentMapper).selectPage(any(Page.class), queryCaptor.capture());
    assertTrue(queryCaptor.getValue().getSqlSegment().contains("deleted"));
    assertTrue(queryCaptor.getValue().getSqlSegment().contains("doc_title"));
    assertTrue(queryCaptor.getValue().getSqlSegment().contains("status"));
    assertTrue(queryCaptor.getValue().getSqlSegment().contains("knowledge_base_type"));
}
```

- [ ] **Step 2: 运行测试，确认因 `page` 方法不存在而失败**

Run: `mvn -Dtest=DocumentServiceTest#pageReturnsUndeletedDocumentsWithAllFilters test`

Expected: 编译失败，指出 `DocumentService` 或 `DocumentServiceImpl` 中不存在 `page` 方法。

- [ ] **Step 3: 在 Service 接口与实现中添加最小分页实现**

```java
PageResponse<KnowledgeDocument> page(long current, long size, String docTitle, String status,
                                     String knowledgeBaseType);
```

```java
Page<KnowledgeDocument> page = new Page<>(current, size);
LambdaQueryWrapper<KnowledgeDocument> queryWrapper = new LambdaQueryWrapper<KnowledgeDocument>()
        .eq(KnowledgeDocument::getDeleted, 0)
        .like(!blank(docTitle), KnowledgeDocument::getDocTitle, docTitle)
        .eq(!blank(status), KnowledgeDocument::getStatus, status)
        .eq(!blank(knowledgeBaseType), KnowledgeDocument::getKnowledgeBaseType, knowledgeBaseType)
        .orderByDesc(KnowledgeDocument::getCreatedAt);
return PageResponse.from(documentMapper.selectPage(page, queryWrapper));
```

- [ ] **Step 4: 运行服务测试，确认通过**

Run: `mvn -Dtest=DocumentServiceTest#pageReturnsUndeletedDocumentsWithAllFilters test`

Expected: `BUILD SUCCESS`，该测试通过。

### Task 2: 为分页 Controller 建立失败测试并实现接口

**Files:**
- Create: `src/test/java/com/jaycong/know/engine/document/controller/DocumentControllerTest.java`
- Modify: `src/main/java/com/jaycong/know/engine/document/controller/DocumentController.java`

- [ ] **Step 1: 写入 Controller 统一响应失败测试**

```java
@Test
void pageDelegatesFiltersAndWrapsPageResponse() {
    PageResponse<KnowledgeDocument> pageResponse = PageResponse.of(List.of(new KnowledgeDocument()), 1, 10, 1);
    when(documentService.page(1, 10, "知识", "INIT", "DOCUMENT_SEARCH")).thenReturn(pageResponse);

    ApiResponse<PageResponse<KnowledgeDocument>> response = controller.page(
            1, 10, "知识", "INIT", "DOCUMENT_SEARCH");

    assertEquals(0, response.getCode());
    assertSame(pageResponse, response.getData());
}
```

- [ ] **Step 2: 运行测试，确认因 Controller 方法不存在而失败**

Run: `mvn -Dtest=DocumentControllerTest#pageDelegatesFiltersAndWrapsPageResponse test`

Expected: 编译失败，指出 `DocumentController` 中不存在 `page` 方法。

- [ ] **Step 3: 添加带参数校验的 `GET /api/document/page` 方法**

```java
@GetMapping("/page")
public ApiResponse<PageResponse<KnowledgeDocument>> page(
        @RequestParam @Positive(message = "页码必须为正数") long current,
        @RequestParam @Positive(message = "每页数量必须为正数") long size,
        @RequestParam(required = false) String docTitle,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String knowledgeBaseType) {
    return ApiResponse.success(documentService.page(current, size, docTitle, status, knowledgeBaseType));
}
```

为该公开方法添加符合项目约束的多行 Javadoc，并导入 `PageResponse`。

- [ ] **Step 4: 运行 Controller 测试，确认通过**

Run: `mvn -Dtest=DocumentControllerTest#pageDelegatesFiltersAndWrapsPageResponse test`

Expected: `BUILD SUCCESS`，该测试通过。

### Task 3: 适配文档列表页面的统一响应

**Files:**
- Modify: `src/main/resources/static/document.html:628-654`

- [ ] **Step 1: 调整 `loadDocuments`，先验证业务状态，再读取 `data`**

```javascript
const result = await res.json();
if (result.code !== 0) throw new Error(result.message || '查询文档列表失败');
const page = result.data;
totalPages = page.totalPages || 1;
totalRecords = page.total || 0;
renderTable(page.records || []);
```

保留已有 `current`、`size`、`docTitle`、`status` 和 `knowledgeBaseType` 参数构造逻辑。

- [ ] **Step 2: 进行静态回归检查**

Run: `rg -n "result\.data|page\.totalPages|/api/document/page" src/main/resources/static/document.html`

Expected: `loadDocuments` 包含业务状态校验，且只从 `result.data` 读取 `records`、`total` 与 `totalPages`。

### Task 4: 执行完整验证并格式化

**Files:**
- Modify: `src/main/java/com/jaycong/know/engine/document/controller/DocumentController.java`
- Modify: `src/main/java/com/jaycong/know/engine/document/service/DocumentService.java`
- Modify: `src/main/java/com/jaycong/know/engine/document/service/impl/DocumentServiceImpl.java`
- Modify: `src/test/java/com/jaycong/know/engine/document/service/DocumentServiceTest.java`
- Create: `src/test/java/com/jaycong/know/engine/document/controller/DocumentControllerTest.java`
- Modify: `src/main/resources/static/document.html`

- [ ] **Step 1: 运行受影响测试与完整 Maven 测试**

Run: `mvn test`

Expected: `BUILD SUCCESS`，无测试失败。

- [ ] **Step 2: 检查格式与改动范围**

Run: `git diff --check && git diff -- src/main/java/com/jaycong/know/engine/document src/test/java/com/jaycong/know/engine/document src/main/resources/static/document.html`

Expected: 无空白错误；仅包含分页查询接口、测试和统一响应前端适配。
