# 基础 RAG 文档 CRUD 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 提供纯文本上传切片，以及知识文档和片段的 MyBatis-Plus CRUD HTTP 接口。

**架构：** 文档模块使用 Controller、Service、Mapper、Entity 与 DTO 分层。上传服务在一个事务中保存文档、按字符上限切片并批量保存片段。查询和删除均使用 `deleted` 软删除标记。

**技术栈：** Java 21、Spring Boot 3、MyBatis-Plus 3.5.7、JUnit 5、Mockito。

---

## 文件结构

- `document/entity/*`：两个数据表的 MyBatis-Plus 映射。
- `document/mapper/*`：`BaseMapper` 接口。
- `document/dto/*`：上传、创建、更新与响应模型。
- `document/service/*`：事务、切片和 CRUD 编排。
- `document/controller/*`：REST 路由。
- `src/test/.../document/*`：服务和控制器行为测试。

### 任务 1：定义失败测试与数据模型

- [ ] 为上传文本被固定大小切片、空文本被拒绝、软删除不可读取编写 JUnit 测试。
- [ ] 运行 `mvn -pl know-engine-learn test -Dtest=DocumentServiceTest`，确认因模块类尚不存在而失败。
- [ ] 创建实体、DTO、Mapper 与服务接口，使测试可编译。
- [ ] 再次运行相同测试，确认仍因未实现行为失败。

### 任务 2：实现文档与片段服务

- [ ] 实现 `DocumentService.upload`：保存 `UPLOADED` 文档、按 `chunkSize` 连续切片、批量保存 `STORED` 片段、更新文档为 `CHUNKED`。
- [ ] 实现文档和片段的创建、读取、列表、更新及软删除。
- [ ] 运行 `mvn -pl know-engine-learn test -Dtest=DocumentServiceTest,SegmentServiceTest`，确认通过。

### 任务 3：实现 REST 接口与回归验证

- [ ] 实现 `/api/documents`、`/api/documents/upload` 与 `/api/segments` 路由，使用请求体接收 DTO。
- [ ] 编写控制器路由与委托测试。
- [ ] 运行 `mvn -pl know-engine-learn test`，确认全部测试通过。
