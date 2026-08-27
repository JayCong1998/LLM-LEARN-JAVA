# 文档分页查询设计

## 目标

为文档管理页面提供 `GET /api/document/page` 接口，将标题、状态和知识库类型筛选及分页统一放到后端执行。

## 接口

请求参数：

- `current`：当前页，从 1 开始，必填且为正数。
- `size`：每页数量，必填且为正数。
- `docTitle`：可选，按文档标题模糊匹配。
- `status`：可选，按文档状态精确匹配。
- `knowledgeBaseType`：可选，按知识库类型精确匹配。

响应使用 `ApiResponse<PageResponse<KnowledgeDocument>>`。业务数据位于 `data`，分页字段为 `records`、`pageNumber`、`pageSize`、`total` 和 `totalPages`。

## 后端实现

Controller 负责校验分页参数并调用 Service。Service 使用 MyBatis-Plus 分页查询：固定排除已软删除文档；仅在筛选值非空时追加相应条件；结果按创建时间倒序。分页结果转换为现有 `PageResponse`。

## 前端实现

`document.html` 保持现有筛选控件和分页控件。文档列表请求携带当前页、每页数量及三个筛选参数；解析统一响应后检查 `code`，从 `data` 读取记录、总数和总页数。业务失败时显示后端返回的 `message`。

## 测试

为 Service 增加分页查询测试，验证分页参数、未删除条件、三个筛选条件与创建时间倒序。为 Controller 增加接口测试，验证参数校验及统一响应封装。
