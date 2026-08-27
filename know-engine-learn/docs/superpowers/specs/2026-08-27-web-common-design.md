# Web 通用基础能力设计

## 目标

为现有 RAG 注释库 REST 接口提供稳定的统一返回结构、集中错误编码、参数校验和全局异常转换能力，使调用方能同时依据 HTTP 状态与业务错误码处理失败结果。

## 范围

- 覆盖普通 JSON REST 接口；不改变数据库模型和服务业务职责。
- `GET /api/chat/stream` 保持 `Flux<String>` 的流式输出，不包裹统一 JSON 响应。
- 现有文档、片段与普通聊天接口迁移至统一响应格式。
- 对请求参数、资源不存在、业务冲突与未知系统错误提供集中处理。

## 响应模型

所有非流式接口返回 `ApiResponse<T>`：

```json
{
  "code": 0,
  "message": "操作成功",
  "data": {}
}
```

- `code` 是稳定的业务错误码，成功固定为 `0`。
- `message` 是面向调用方的中文提示。
- `data` 承载成功数据；失败时为 `null`。
- `ApiResponse` 提供 `success()`、`success(data)`、`success(message, data)`、`fail(errorCode)`、`fail(errorCode, message)` 及 `fail(code, message)` 静态工厂方法。

分页响应作为 `data` 的专用模型 `PageResponse<T>`，不改变顶层结构：

```json
{
  "code": 0,
  "message": "操作成功",
  "data": {
    "records": [],
    "pageNumber": 1,
    "pageSize": 20,
    "total": 0,
    "totalPages": 0
  }
}
```

`PageResponse<T>` 提供从 MyBatis-Plus `IPage<T>` 构造的工厂方法，统一计算总页数并保留查询记录。

## 错误码与 HTTP 映射

以 `ErrorCode` 枚举作为单一来源，包含业务码、HTTP 状态和默认中文提示：

| 错误码 | HTTP 状态 | 含义 |
| --- | --- | --- |
| 0 | 200 | 成功 |
| 40000 | 400 | 请求参数错误 |
| 40400 | 404 | 资源不存在 |
| 40900 | 409 | 业务冲突 |
| 50000 | 500 | 系统内部错误 |

## 异常处理

- `BusinessException` 携带 `ErrorCode` 和可选的业务提示。
- `GlobalExceptionHandler` 使用 `@RestControllerAdvice`：
  - 将 `BusinessException` 转为其对应 HTTP 状态和失败响应；
  - 将 `MethodArgumentNotValidException`、`BindException`、`ConstraintViolationException`、`HttpMessageNotReadableException` 统一转为 `40000`；
  - 将未预期异常转为 `50000`，服务端记录完整异常信息，响应不泄露实现细节。
- 服务层找不到未删除的文档或片段时抛出 `BusinessException(RESOURCE_NOT_FOUND)`；更新和删除遵循相同语义。

## 参数校验与迁移

- 请求 DTO 使用 Jakarta Bean Validation 注解表达不能为空、长度、数值范围等约束；字段均保留简洁中文注释。
- Controller 参数使用 `@Valid`，路径和查询参数按需使用约束注解。
- Controller 的公开方法保留用途说明 Javadoc，返回 `ApiResponse<T>`；删除操作使用 `ApiResponse<Void>`。
- 聊天的非流式端点也返回 `ApiResponse<String>`，流式端点保持原样。

## 测试

- 单元测试覆盖 `ApiResponse` 各静态工厂方法及 `PageResponse` 总页数计算。
- Web 层测试覆盖成功响应、DTO 校验失败（400/40000）、资源不存在（404/40400）、业务冲突（409/40900）和未知异常（500/50000）。
- 保证流式聊天端点的响应类型未受统一包装影响。
