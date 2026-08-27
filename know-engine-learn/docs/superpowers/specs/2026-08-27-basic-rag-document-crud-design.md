# 基础 RAG 文档 CRUD 设计

## 目标

在 `com.jaycong.know.engine.document` 下实现基于 MyBatis-Plus 的知识文档和知识片段基础 CRUD，并提供接收纯文本、切片后持久化的文档上传接口。

## 范围

- 使用既有 `knowledge_document` 与 `knowledge_segment` 表，不引入多租户、版本、文件存储或向量生成。
- 文档上传请求接收标题、文本内容、可选描述与可选切片大小；默认按 500 个字符切片。
- 上传过程在单一事务中创建文档、批量保存片段，并将文档状态置为 `CHUNKED`。
- CRUD 使用 `deleted=1` 软删除；普通查询默认只返回 `deleted=0` 的数据。
- 片段向量相关字段只保留映射空间，不在此功能中调用 embedding 或向量库。

## 接口

- `POST /api/documents/upload`：创建文档并对 `content` 切片。
- `POST /api/documents`、`GET /api/documents/{id}`、`GET /api/documents`、`PUT /api/documents/{id}`、`DELETE /api/documents/{id}`。
- `POST /api/segments`、`GET /api/segments/{id}`、`GET /api/segments?documentId=...`、`PUT /api/segments/{id}`、`DELETE /api/segments/{id}`。

## 结构与职责

- `entity`：表字段映射。
- `mapper`：MyBatis-Plus `BaseMapper` 数据访问。
- `dto`：HTTP 请求与响应边界。
- `service`：切片、事务、软删除过滤与 CRUD 编排。
- `controller`：参数接收、HTTP 路由与响应。

## 错误处理与测试

- 标题和内容不能为空；切片大小必须大于零。
- 对不存在或已删除的记录，读取、更新、删除均返回业务异常（HTTP 404）。
- 测试覆盖文本切片边界、上传后文档/片段写入、参数校验及软删除后的不可查询。
