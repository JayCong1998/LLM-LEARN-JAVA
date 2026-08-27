# 文档上传页接口适配设计

## 目标

使 `src/main/resources/static/upload.html` 与 `DocumentController#uploadFile` 的最新 multipart 接口及 `ApiResponse` 响应结构一致。

## 接口契约

请求地址为 `POST /api/document/upload`。页面提交 `file`、`title`、`description`、`knowledgeBaseType` 和 `version`；当知识库类型为 `DATA_QUERY` 时额外提交 `tableName`。不提交接口不存在的 `accessibleBy` 字段。

成功响应使用 `{ code: 0, message: string, data: null }`，因此页面只以 `code === 0` 判定业务成功，不读取文档 ID、标题、版本 ID、状态或切片数量。

## 页面行为

保留文件选择、拖放、标题和描述自动填充、`DATA_QUERY` 的表名校验及上传进度。新增可编辑的版本号，初始值为 `1.0.0`。上传成功后显示服务端提示并跳转至文档管理页；失败时显示统一响应中的 `message`。

删除依赖历史上传返回值的切片配置界面与 `/api/document/split/{documentId}` 调用，因为这些不属于当前 Controller 的上传接口契约。

## 验证

使用静态契约检查确认页面包含全部有效字段、不包含 `accessibleBy`，并按 `ApiResponse.code` 与 `ApiResponse.message` 解析响应；随后运行 Maven 测试，确认项目未受影响。
