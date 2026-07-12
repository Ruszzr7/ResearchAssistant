# API Contract Baseline

Mission 11.2 keeps the existing JSON success envelope:

```json
{"code": 200, "message": "success", "data": {}}
```

Validation and service failures use the same body with the corresponding HTTP status:

| HTTP | `code` | Meaning |
|---:|---:|---|
| 400 | 400 | Invalid JSON, missing field, or value outside the documented bounds |
| 404 | 404 | Resource not found |
| 409 | 409 | Idempotency or state conflict |
| 429 | 429 | Async/task or synchronous AI capacity exhausted |
| 502 | 502 | External AI/literature provider unavailable |
| 500 | 500 | Internal failure; provider/database details are never returned |

Request bodies for folders, tags, workflow submission, agent search/plan, and paper batch move are validated DTOs. Existing JSON field names remain unchanged so the Vue client does not need a transport migration.

File downloads keep their normal binary/404 responses. SSE endpoints keep `event:error` but only emit client-safe messages. `X-Request-Id` is returned on API responses for local troubleshooting.
