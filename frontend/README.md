# Research Assistant 前端

Vue 3 + Vite 前端，包含文库、检索、分析、Gap、任务中心、阅读计划和写作助手页面。

```text
npm.cmd install
npm.cmd run dev       # http://localhost:5173
npm.cmd run build
```

开发服务器将 `/api` 请求代理到 `http://localhost:8080`。前端状态主要由 Vue composables 和轻量 reactive store 管理，长任务通过后端任务接口和 SSE 获取进度。
