# API 契约基线

## 响应格式

成功和业务错误沿用统一包络：

```json
{"code": 200, "message": "success", "data": {}}
```

| HTTP 状态 | `code` | 含义 |
|---:|---:|---|
| 400 | 400 | JSON 无效、字段缺失或值越界 |
| 404 | 404 | 资源不存在 |
| 409 | 409 | 幂等键或任务状态冲突 |
| 429 | 429 | 异步任务或同步 AI 容量已满 |
| 502 | 502 | 外部 AI/文献服务不可用 |
| 500 | 500 | 内部错误；不返回 Provider、数据库或密钥细节 |

文件下载保持二进制/404 响应；SSE 错误使用 `event:error`，只发送客户端可见信息。所有 API 响应返回 `X-Request-Id` 便于本地排查。

## 请求边界

- 文件夹、标签、Workflow、Agent/Search 和论文批量操作使用 DTO 与边界校验。
- `POST /api/papers`、`PUT /api/papers/{id}` 只接受可编辑论文元数据和阅读字段；ID、PDF 路径、时间戳、处理状态和标签由服务端管理。
- `POST /api/search/execute` 只接受已定义的 `keywords_en` 和提取字段，关键词限制为 1–50 项。
- `POST /api/search/import` 最多接收 500 条类型化论文记录，每条必须有非空标题。
- `POST /api/agent/workflow/{taskId}/confirm` 只接受 `selected` 和 `folderId`；未知字段忽略以保持前后端兼容。

契约测试位于后端 Controller 测试目录，更新字段时应同步更新 Vue 请求和 MockMvc 场景。
