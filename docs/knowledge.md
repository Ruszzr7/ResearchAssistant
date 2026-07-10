# 知识总结

## S1：项目脚手架 — 技术全解

---

### 一、基础概念

#### 1. 前端 vs 后端

| | 前端 | 后端 |
|------|------|------|
| **在哪运行** | 用户浏览器里 | 服务器上 |
| **做什么** | 画界面、处理点击、展示数据 | 业务逻辑、读写数据库 |
| **本项目用** | Vue 3 | Spring Boot + MyBatis Plus |

#### 2. JSON — 前后端通信的通用数据格式

```json
{ "title": "Attention Is All You Need", "authors": ["Vaswami"], "year": 2017 }
```

#### 3. RESTful API

URL 定位资源，HTTP 方法表达操作：`GET /api/papers`（查列表）、`POST /api/papers`（新增）、`DELETE /api/papers/5`（删除）。接口设计得像文件路径一样直观。

#### 4. 端口号

同一台电脑跑多个程序，端口号区分"请求给谁"：前端 `:5173`、后端 `:8080`、MySQL `:3306`。

---

### 二、前端技术栈

| 技术 | 一句话 | 类比 / 关键文件 |
|------|--------|----------------|
| **Node.js** | 让 JS 在浏览器外（命令行）运行 | — |
| **npm** | Node 的包管理器，`npm install xxx` 下载依赖 | `package.json` = 购物清单 |
| **Vite** | 开发时实时转译 `.vue` → JS；打包时压缩到 `dist/` | `vite.config.js` |
| **Vue 3** | 组件化框架，`.vue` = `<template>` + `<script>` + `<style>` | 不用手写 DOM 操作 |
| **SPA** | 单页面应用，整站只有一个 `index.html`，"换页"靠 JS 替换内容 | 体验像桌面软件 |
| **Vue Router** | 管理 URL ↔ 页面组件的映射 | `<router-view>` = 页面占位符 |
| **Element Plus** | 现成的 UI 组件库（按钮、表格、对话框） | `<el-button>` 开箱即用 |
| **Pinia** | 跨组件共享数据（如"当前选中的论文"） | 公共数据仓库 |
| **axios** | 发 HTTP 请求的库，比原生 `fetch` 更好用 | 自动处理 JSON、统一 baseURL |
| **Vite 代理** | 开发时 Vite 把 `/api` 请求转发给后端 `:8080` | 绕过浏览器跨域限制 |
| **@ 别名** | `@` = `src/` 的缩写 | 不用写 `../../../` |

---

### 三、后端技术栈

| 技术 | 一句话 | 类比 / 关键文件 |
|------|--------|----------------|
| **JDK** | Java 开发工具包（含编译器 javac），JRE 只能运行 | Java 25，Spring Boot 3 要求 ≥17 |
| **Maven** | Java 的包管理器 | `pom.xml` = npm 的 `package.json` |
| **Spring Boot** | Java 后端框架，注解驱动，约定优于配置 | `@RestController` + `@GetMapping` 就写好接口 |
| **MyBatis Plus** | ORM 框架，Java 对象 ↔ 数据库表 | `mapper.selectById(5)` 自动生成 SQL |
| **CORS 配置** | 后端声明允许哪些来源跨域访问 | 生产环境不用 Vite 代理时的保险 |
| **application.yml** | 后端所有配置集中管理 | 端口号、数据库地址、Redis |

**Spring Boot 请求链路**：`Filter Chain → DispatcherServlet → Interceptor → Controller → Service → Mapper → 数据库`，返回原路，Controller 返回值被 Jackson 序列化为 JSON。

---

### 四、文件对应关系速查

| 前端文件 | 干什么 | 后端文件 | 干什么 |
|----------|--------|----------|--------|
| `index.html` | 唯一 HTML，Vue 画在上面 | `pom.xml` | Maven 依赖清单 |
| `vite.config.js` | 端口、代理、别名 | `mvnw` | Maven 启动脚本 |
| `package.json` | npm 依赖清单 | `BackendApplication.java` | `main` 入口 |
| `src/main.js` | Vue 入口：装插件 → 挂载 | `HelloController.java` | 处理 `/api/hello` |
| `src/App.vue` | 根组件：导航 + 页面切换 | `CorsConfig.java` | 跨域配置 |
| `src/router/index.js` | URL ↔ 页面路由表 | `application.yml` | 端口、数据库配置 |
| `src/views/*.vue` | 四个页面组件 | — | — |


| 前端 | 后端 | |
|------|--------|----------------|
| Vue 3 | Spring Boot | 框架 |
| Vite| Maven | 构建/打包 |
| npm | Maven | 包管理器 |
| packet.json | pom.xml | 依赖清单 |
| .vue | .java | 代码文件 |
| 5173 | 8080 | 端口 |

---

## S2：论文库开发 — 知识总结

> 2026-06-29

---

### 一、数据库设计

#### 1. 嵌套文件夹——`parent_id` 自引用

```
folder 表
├── id: 1, name: "AI", parent_id: null     ← 根目录
├── id: 2, name: "NLP", parent_id: 1       ← AI 的子文件夹
└── id: 3, name: "CV", parent_id: 1        ← AI 的子文件夹
```

`parent_id` 指向同一张表的 `id`（外键自引用）。`null` = 根目录。后端 Service 层递归构建树结构，前端用 `el-tree` 展示。

#### 2. 多对多关联——桥接表 `paper_tag`

一篇论文可有多个标签，一个标签可挂多篇论文。不能直接在 `paper` 表加标签列（会冗余），也不能在 `tag` 表加论文列。标准做法：加一张桥接表：

```
paper_tag
├── paper_id → paper.id
└── tag_id   → tag.id
UNIQUE(paper_id, tag_id)  ← 防止重复关联
```

#### 3. MySQL 字符集 `utf8mb4` vs JDBC `UTF-8`

- 建库用 `utf8mb4`（MySQL 内部的完整 UTF-8，支持 emoji）
- JDBC 连接字符串用 `characterEncoding=UTF-8`（Java 只认这个，不是 `utf8mb4`）
- 二者本质上是同一个编码标准，只是命名不同

#### 4. 保留字冲突：`abstract`

- 在 **MySQL** 中 `abstract` 是保留字，需要反引号：`` `abstract` ``
- 在 **Java** 中 `abstract` 是关键字，不能作变量名
- 解决：Java 字段取名 `abstractText`，`@TableField("abstract")` 指定映射，SQL 查询用 `p.\`abstract\` AS abstract_text` 别名

---

### 二、后端开发

#### 1. MyBatis Plus `BaseMapper<T>`

继承 `BaseMapper<Paper>` 后自动获得 `insert`、`selectById`、`updateById`、`deleteById` 等方法，无需写 XML。复杂查询（分页+多条件）用 `@Select` 注解手写 SQL。

#### 2. MyBatis Plus 分页插件

```java
@Bean
public MybatisPlusInterceptor mybatisPlusInterceptor() {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
    return interceptor;
}
```

配置后，MyBatis Plus 自动拦截 `IPage<Paper>` 返回类型的查询，在 SQL 末尾加 `LIMIT ?, ?`。

#### 3. `@TableField` 注解

| 用法 | 说明 |
|------|------|
| `@TableField("column_name")` | 映射到指定列名 |
| `@TableField(exist = false)` | 不在数据库中的字段（如 `List<Tag> tags`） |
| `@TableField(fill = FieldFill.INSERT)` | 插入时自动填充（如 `createdAt`） |

#### 4. 自动填充时间戳

```java
MetaObjectHandler handler = new MetaObjectHandler() {
    @Override public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
    }
};
```

配合 `@TableField(fill = FieldFill.INSERT)`，每次 `insert` 自动写入当前时间。

#### 5. `updateById` 无法更新为 null

MyBatis Plus 默认 `updateStrategy = NOT_NULL`——值为 null 的字段不生成 SQL。如果要把 `parent_id` 从有值改成 `NULL`（移出文件夹），必须：

```java
UpdateWrapper<Folder> uw = new UpdateWrapper<>();
uw.eq("id", id);
uw.set("parent_id", null);  // 显式指定
folderMapper.update(null, uw);
```

#### 6. MyBatis 动态 SQL

`<script>` 标签内可使用 `<if>`、`<where>`、`<foreach>`、`<choose>`：

```xml
<if test='keyword != null and keyword != ""'>
  AND (p.title LIKE CONCAT('%',#{keyword},'%'))
</if>
<choose>
  <when test='sortBy == "title"'> ORDER BY p.title ${sortDir}</when>
  <otherwise> ORDER BY p.created_at DESC</otherwise>
</choose>
```

注意：`${}` 直接拼字符串（有注入风险，仅用于列名/排序方向），`#{}` 用预编译占位符（安全）。

#### 7. `@Result` 与 `@MapKey`

- `@Result`：指定列到字段的映射
- `@MapKey("column")`：让查询结果以某列为 key 返回 `Map`（**value 必须是实体类**，不能是 `Long`）

#### 8. 接口方法不加 `public`（阿里规约）

Java 接口中方法默认就是 `public abstract`，显式写 `public` 是冗余的。

#### 9. 常量类消除魔法值（阿里规约）

```java
public final class ReadingStatus {
    public static final String UNREAD = "UNREAD";
    public static final String CLOSE_READ = "CLOSE_READ";
    private ReadingStatus() {} // 不可实例化
}
```

避免代码中出现裸字符串 `"UNREAD"` 散落各处。

---

### 三、前端开发

#### 1. Vue `<style scoped>` 的穿透限制

`scoped` 会给本组件元素加 `data-v-xxx`，但 Element Plus 渲染的内部 DOM（如 `el-button` → `<button>`）没有这个属性。三种穿透方案：

| 方案 | 写法 | 可靠性 |
|------|------|--------|
| `:deep()` | `.parent :deep(.el-button) { ... }` | 中 |
| 非 scoped `<style>` | 独立 style 块，不加 scoped | 高 |
| 内联 style | `style="padding:2px 4px"` | 最高 |

#### 2. el-tree 显示顺序由 `children` 数组决定

修改节点的 `sortOrder` 属性**不会**改变树的可视顺序。必须对父节点的 `children` 数组进行 splice/swap 操作，树才会重排。

#### 3. `:key` 强制重渲染

Vue 对深层嵌套对象属性变更可能检测不到。给组件加 `:key="counter"`，每次操作后 `counter++`——Vue 销毁旧组件、创建新组件，100% 刷新。

#### 4. el-tree `accordion` vs 手动实现

`accordion` 只在点击展开箭头时生效，点击节点标签不管。如果需要在点击节点时也收起同级，必须手动调用 `getNode(id).expand()/collapse()`。

#### 5. `el-dropdown` + `el-button` 间距问题

Element Plus 的 `el-button` 自带 `padding: 8px 15px`、`min-width`、相邻按钮 `margin-left`。图标按钮需显式覆盖 `padding: 2px 4px; min-width: auto`。

#### 6. 弹窗内树数据本地副本 + 批量提交

编辑弹窗的理想模式：打开时深克隆一份本地数据 → 所有操作在本地副本进行 → 点"确认"批量调 API → 点"取消"丢弃。避免每次操作立刻调 API 导致树状态刷新和网络开销。

#### 7. `flex` + `margin-top: auto` 推到底部

在 flex 容器中，某个子元素设 `margin-top: auto` 会自动推到容器底部，适合"筛选"这类始终在底部的内容。

---

### 四、踩坑速查

| 问题 | 原因 | 解决 |
|------|------|------|
| 编译报"找不到符号 getId()" | Lombok 与 JDK 25 不兼容 | 手写 getter/setter |
| 启动报 `Unsupported character encoding 'utf8mb4'` | JDBC 不认 MySQL 内部编码名 | 改为 `UTF-8` |
| abstract 列读出为 null | MyBatis 列名映射冲突 | SQL 用 `AS abstract_text` |
| PowerShell 无法运行 `./mvnw` | 缺少 `.cmd` 文件 | 创建 `mvnw.cmd` |
| 文件夹移出失败 | `updateById` 忽略 null 字段 | 用 `UpdateWrapper` 显式 set null |
| CSS 样式不生效 | scoped 无法穿透 Element Plus | 内联 style |
| 文件夹排序不刷新 | 改 sortOrder 不改变 children 数组 | 直接 swap 数组元素 |
| 编辑树操作无视觉反馈 | Vue 未检测深层变更 | `:key` 计数器强制重建 |

---

## S3：PDF 存储与数据底座 — 知识总结

> 2026-06-30

---

### 一、Spring Boot 文件上传

#### 1. Multipart 上传配置

Spring Boot 默认上传限制为 1MB，需要在 `application.yml` 中放大：

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 50MB
```

否则上传稍大的 PDF 就报 413 Payload Too Large。

#### 2. Multipart 控制器接收

```java
@PostMapping("/upload")
public Result<Paper> upload(
    @RequestParam("file") MultipartFile file,  // 文件
    @RequestParam("title") String title,       // 其他字段
    ...) { }
```

前端用 `FormData` 发送，`Content-Type: multipart/form-data`。不能再用 `@RequestBody` 接收 JSON。

#### 3. 文件保存

```java
File dest = new File(storageDir, fileName);
file.transferTo(dest);  // 核心：直接写入磁盘
```

`transferTo()` 是 Spring 封装的，底层调用 `InputStream.transferTo(OutputStream)`。

#### 4. 文件下载/预览

```java
Resource resource = new FileSystemResource(file);
return ResponseEntity.ok()
    .contentType(MediaType.APPLICATION_PDF)
    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=...")
    .body(resource);
```

- `inline` = 浏览器内嵌预览；`attachment` = 强制下载
- 中文文件名需 `URLEncoder.encode(name, UTF-8).replace("+", "%20")`，用 `filename*=UTF-8''` 格式

---

### 二、Apache PDFBox 文本提取

#### 1. 依赖

```xml
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
    <version>3.0.4</version>
</dependency>
```

#### 2. API 使用（3.x）

```java
try (PDDocument doc = Loader.loadPDF(file)) {      // 3.x: Loader.loadPDF(File)
    PDFTextStripper stripper = new PDFTextStripper();
    stripper.setSortByPosition(true);               // 按坐标排序文字
    String text = stripper.getText(doc);             // 提取全文
}
```

**坑**：PDFBox 2.x 的 `PDDocument.load(file)` 和 `RandomAccessReadBufferedFile` 在 3.x 已移除，改用 `Loader.loadPDF(File)`。

#### 3. 纯图片 PDF 无法提取文字

PDFBox 提取的是文字层（text layer），扫描版 PDF（图片）返回空字符串。需要 OCR 才能处理，不在 MVP 范围内。

---

### 三、Crossref API（DOI 元数据）

#### 1. 接口

```
GET https://api.crossref.org/works/{doi}
```

免费、无需 API Key。返回 JSON 含标题、作者、年份、期刊、摘要、关键词等。

#### 2. 字段映射

| Crossref 字段 | 数据库字段 |
|--------------|-----------|
| `message.title[0]` | title |
| `message.author[].given + family` | authors |
| `message.issued.date-parts[0][0]` | year |
| `message.container-title[0]` | source |
| `message.abstract` | abstractText |
| `message.subject[]` | keywords |
| `message.URL` | sourceUrl |

#### 3. `||` 短路陷阱

```javascript
// ❌ 错误：默认值 2025 是 truthy，永远覆盖 Crossref 数据
form.value.year = form.value.year || crossrefYear

// ✅ 正确：显式判断
if (crossrefYear) form.value.year = crossrefYear
```

---

### 四、前端 Pattern

#### 1. el-upload 在 dialog 中的问题

Element Plus 的 `el-upload` 在 `el-dialog` 内会产生多个隐藏 `<input type="file">`，弹窗打开时触发大量文件选择器，导致页面卡死。**解决方案**：不用 el-upload，改用原生 `<input type="file" style="display:none">` + 自定义 div 绑定 click/drop 事件。

```html
<input type="file" ref="inputRef" accept=".pdf" @change="onChange" style="display:none" />
<div @click="$refs.inputRef.click()" @drop.prevent="onDrop">...</div>
```

#### 2. PDF 内嵌预览 overlay

全屏 overlay 覆盖主内容但保留导航栏：
```css
.pdf-overlay {
    position: fixed;
    top: 56px;          /* 留出导航栏 */
    left: 0; right: 0; bottom: 0;
    z-index: 9999;
}
```

#### 3. Element Plus 菜单 border 穿透

`el-header` 的 `border-bottom` 会被 `el-menu` 内部白色背景覆盖。**最稳方案**：不用 CSS border，直接在 template 中插 `<div style="height:1px;background:#dcdfe6">`。

#### 4. 删除确认弹窗

```javascript
import { ElMessageBox } from 'element-plus'

async function confirmDelete() {
    try {
        await ElMessageBox.confirm('确定删除？', '确认', {
            confirmButtonText: '删除',
            cancelButtonText: '取消',
            type: 'warning'
        })
        await deletePaper()
    } catch {}
    // 用户点取消 → catch 块静默忽略（reject 即取消）
}
```

---

### 五、踩坑速查（新增）

| 问题 | 原因 | 解决 |
|------|------|------|
| 上传 PDF 报 413 | Spring Boot 默认上传 1MB | `max-file-size: 50MB` |
| PDFBox 编译报错 | 3.x 移除了 `PDDocument.load()` | 改用 `Loader.loadPDF(File)` |
| PDF 路径找不到 | `./data/papers` 相对 Tomcat 临时目录 | `user.dir` 解析为绝对路径 |
| el-upload 页面卡死 | dialog 内产生大量 file input | 原生 input + 自定义拖拽区 |
| 中文文件名下载乱码 | HTTP 头只支持 ASCII | `URLEncoder + filename*=UTF-8''` |
| DOI 年份始终显示 2025 | `||` 短路：默认值 truthy | 显式 `if (crossrefYear)` 覆盖 |
| header 分割线断开 | el-menu 背景覆盖 border-bottom | 用真实 `<div>` 替代 CSS border |

---

## S4：需求对齐审计与功能补全 — 知识总结

> 2026-07-03

---

### 一、Spring 循环依赖

#### 1. 典型场景

`AsyncTaskService` 注入 `PaperService`，`PaperService` 又注入 `AsyncTaskService`，Spring Boot 默认禁止循环引用，启动报错：

```
Requested bean is currently in creation: Is there an unresolvable circular reference?
```

#### 2. 解决思路

| 方案 | 适用场景 |
|------|----------|
| 拆层：把底层操作下沉到 Mapper/DAO | 两个 Service 互相调用时最干净 |
| `@Lazy` 延迟注入 | 必须互相调用时 |
| 启用 `spring.main.allow-circular-references=true` | 不推荐，掩盖设计问题 |

本项目中 `AsyncTaskService` 只需要更新 `paper.pdf_path`，直接注入 `PaperMapper` 而非 `PaperService`，既打破循环，又避免多余事务。

---

### 二、Spring `@Async` 与线程池

#### 1. 启用异步

```java
@EnableAsync
@SpringBootApplication
public class BackendApplication { ... }
```

#### 2. 自定义线程池

```java
@Bean("taskExecutor")
public Executor taskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(20);
    executor.setQueueCapacity(200);
    executor.setThreadNamePrefix("task-");
    executor.initialize();
    return executor;
}
```

#### 3. 使用

```java
@Async("taskExecutor")
public void processPaperAsync(Long paperId) { ... }
```

**注意**：同一个类内部调用 `@Async` 方法不会走代理，因此不会异步。必须外部调用。

---

### 三、全局异常处理

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        return Result.error(400, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("服务器内部错误", e);
        return Result.error(500, "服务器内部错误: " + e.getMessage());
    }
}
```

- `@RestControllerAdvice` 统一捕获 Controller 层抛出的异常
- 避免 Tomcat 默认返回 HTML 错误页，前后端都能按统一格式处理

---

### 四、CORS 安全

#### 1. 开发环境

只允许前端开发服务器：

```java
registry.addMapping("/api/**")
    .allowedOrigins("http://localhost:5173")
    .allowCredentials(true)
    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
```

#### 2. 生产环境

不要写死 origin，应从配置文件读取：

```java
@Value("${app.cors.allowed-origins:http://localhost:5173}")
private String allowedOrigins;
```

---

### 五、参数校验

#### 1. DTO + `@Valid`

```java
public record GapRequest(
    @NotNull @Size(min = 3, message = "至少需要 3 篇论文")
    List<Long> paperIds
) {}
```

```java
@PostMapping("/gap")
public Result<?> gap(@RequestBody @Valid GapRequest request) { ... }
```

#### 2. 全局捕获校验失败

```java
@ExceptionHandler(MethodArgumentNotValidException.class)
public Result<Void> handleValidation(MethodArgumentNotValidException e) {
    String msg = e.getBindingResult().getFieldErrors().stream()
        .map(FieldError::getDefaultMessage)
        .collect(Collectors.joining("; "));
    return Result.error(400, msg);
}
```

---

### 六、HttpClient 重定向

Java 11+ `HttpClient` 默认对 GET/HEAD 跟随 `NORMAL` 级别重定向，但某些站点仍返回 301。显式声明更保险：

```java
HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(15))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build();
```

另外，URL 尽量使用站点的 canonical 地址，避免依赖重定向。

---

### 七、踩坑速查（新增）

| 问题 | 原因 | 解决 |
|------|------|------|
| 启动报循环依赖 | `AsyncTaskService` 与 `PaperService` 互相注入 | `AsyncTaskService` 改注入 `PaperMapper` |
| 异常返回 HTML | 缺少全局异常处理 | 加 `@RestControllerAdvice` |
| `ORDER BY` 被注入 | `${sortDir}` 直接拼接 | Service 层白名单仅允许 ASC/DESC |
| arXiv PDF 下载 301 | URL 带 `.pdf` 触发重定向 | 改用 canonical URL 并显式 follow redirect |
| CORS 其他来源也能访问 | 配置成 `allowedOriginPatterns("*")` | 收紧为 `allowedOrigins("http://localhost:5173")` |
| 手动创建论文 processingStatus 为 null | 数据库默认 NULL | schema 默认值改为 `PENDING` |
| 上传大文件失败 | Spring 默认限制 1MB | `multipart.max-file-size: 50MB` |

---

## S5：LangChain4j 集成 — 知识总结

> 2026-07-06

---

### 一、LangChain4j 1.0.0 版本选型与依赖

#### 1. 核心依赖

```xml
<properties>
    <langchain4j.version>1.0.0</langchain4j.version>
</properties>

<dependencies>
    <!-- AiServices、@SystemMessage、@UserMessage、结构化输出 POJO -->
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j</artifactId>
        <version>${langchain4j.version}</version>
    </dependency>
    <!-- OpenAI 兼容模型（DeepSeek / Kimi / OpenRouter 等） -->
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-open-ai</artifactId>
        <version>${langchain4j.version}</version>
    </dependency>
</dependencies>
```

**坑**：`AiServices` 不在 `langchain4j-core` 中，必须在 `langchain4j` artifact 里。1.0.0 API 与 0.x 差异很大。

---

### 二、关键 API 变化（1.0.0 vs 0.x）

| 0.x 概念 | 1.0.0 对应 | 说明 |
|---|---|---|
| `ChatLanguageModel` | `ChatModel` | 同步聊天模型接口 |
| `StreamingChatLanguageModel` | `StreamingChatModel` | 流式聊天模型接口 |
| `model.generate(messages)` | `model.chat(messages)` | 返回 `ChatResponse` |
| `Response<AiMessage>` | `ChatResponse` | 通过 `aiMessage()` / `tokenUsage()` 取结果 |
| `AiServices.builder(...).chatLanguageModel(...)` | `.chatModel(...)` | 参数改为 `ChatModel` |
| `dev.langchain4j.data.message.SystemMessage` | 仍在 `dev.langchain4j.data.message` | 注解同名类在 `dev.langchain4j.service` |

---

### 三、AiServices 编程式构建

当配置需要运行时从 DB 读取时，不要用 starter 的 `@AiService` 自动扫描，而是手动构建 Bean：

```java
@Configuration
public class ResearchAiConfig {

    @Bean
    public ResearchAiService researchAiService(LangChain4jModelFactory modelFactory) {
        return AiServices.builder(ResearchAiService.class)
                .chatModel(modelFactory.createChatModel())
                .build();
    }
}
```

接口示例：

```java
public interface ResearchAiService {

    @SystemMessage("你是学术论文审稿人...")
    @UserMessage("请分析：\n\n{{it}}")
    Result<PaperAnalysisResult> analyzePaper(String text);
}
```

- `{{it}}` 代表单个参数。
- 返回 `Result<T>` 才能拿到 `tokenUsage()`；直接返回 POJO 拿不到用量。

---

### 四、结构化输出 POJO

```java
@Data
public class PaperAnalysisResult {

    @Description("论文领域，如 AI / CV / NLP")
    private String domain;

    @Description("三句话概括核心贡献")
    private String coreContribution;

    private List<String> datasets;
    // ...
}
```

- `@Description` 帮助 LLM 理解字段含义。
- LangChain4j 会自动把 POJO 转成 JSON schema 并要求模型按 JSON 输出。
- 模型输出不稳定时务必保留 fallback（旧解析或重试）。

---

### 五、动态配置工厂

```java
@Component
public class LangChain4jModelFactory {

    private final SettingsService settingsService;

    public ChatModel createChatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(normalizeBaseUrl(settingsService.getValue("base_url")))
                .apiKey(settingsService.getValue("api_key"))
                .modelName(settingsService.getValue("model"))
                .temperature(resolveTemperature())
                .maxTokens(4096)
                .timeout(Duration.ofSeconds(120))
                .build();
    }
}
```

- 每次调用都重新读 settings，支持前端修改后热生效。
- `OpenAiChatModel` 的 baseUrl 需要带 `/v1`；它会再追加 `/chat/completions`。

---

### 六、踩坑速查

| 问题 | 原因 | 解决 |
|---|---|---|
| 编译报找不到 `dev.langchain4j.service.AiServices` | 只引了 `langchain4j-core` | 加 `langchain4j` artifact |
| `AiServices.builder(...).chatLanguageModel(...)` 不存在 | 1.0.0 改名 | 用 `.chatModel(...)` |
| `OpenAiChatModel.generate(...)` 不存在 | 1.0.0 改名 | 用 `.chat(SystemMessage, UserMessage)` |
| 拿不到 token usage | 方法返回了纯 POJO | 返回 `Result<T>`，调用 `result.tokenUsage()` |
| POJO 字段为空 | LLM 输出不稳定 | 加 fallback 路径；或调 temperature / 换模型 |
| baseUrl 404 | 多拼或少拼 `/v1` | 工厂里统一规范化成 `.../v1` |

---

## S6：LangChain4j 多轮 ChatMemory — 知识总结

> 2026-07-06

---

### 一、ChatMemory 与 ChatMemoryStore

LangChain4j 的 `ChatMemory` 负责在单次请求中组装历史消息，`ChatMemoryStore` 负责持久化。

```java
// 内存版（仅本次 JVM 有效）
ChatMemory memory = MessageWindowChatMemory.withMaxMessages(20);

// 持久版（自定义 store）
ChatMemory memory = MessageWindowChatMemory.builder()
        .id(memoryId)
        .maxMessages(20)
        .chatMemoryStore(myStore)
        .build();
```

- `id(memoryId)`：同 id 会共用同一份历史。
- `maxMessages(20)`：只保留最近 20 条，避免 token 爆炸。
- `ChatMemoryStore` 接口只有 `getMessages` / `updateMessages` / `deleteMessages` 三个方法。

### 二、通过 AiServices 使用记忆

```java
@Bean
public ResearchAiService researchAiService(LangChain4jModelFactory modelFactory,
                                            ChatMemoryStore chatMemoryStore) {
    return AiServices.builder(ResearchAiService.class)
            .chatModel(modelFactory.createChatModel())
            .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                    .id(memoryId)
                    .maxMessages(20)
                    .chatMemoryStore(chatMemoryStore)
                    .build())
            .build();
}
```

接口方法：

```java
@UserMessage("{{question}}")
Result<String> chat(@MemoryId String memoryId, @V("question") String question);
```

- `@MemoryId` 标记会话标识。
- `@V("question")` 把参数注入模板变量 `{{question}}`。
- **不要**在方法上加 `@SystemMessage`  if 你还想通过记忆动态注入上下文；LangChain4j 的注解 system message 会覆盖或优先于记忆中的 system message。

### 三、MySQL 持久化实现要点

1. 表结构：`memory_id`（会话标识）、`role`（SYSTEM/USER/AI）、`content`、时间戳。
2. `updateMessages` 通常是“全量覆盖”：先 delete 再 insert。
3. 注意 MyBatis 注解：删除语句用 `@Delete`，不要误用 `@Select`。
4. 消息类型转换：
   - `SystemMessage` → `SYSTEM`
   - `UserMessage` → `USER`
   - `AiMessage` → `AI`
   - `UserMessage.singleText()` 取文本。

### 四、多轮对话的调用方设计

首次调用时注入上下文作为 system message，后续调用只传问题：

```java
public String chatAbout(String conversationId, String context, String question) {
    List<ChatMessage> messages = chatMemoryStore.getMessages(conversationId);
    if (messages.isEmpty()) {
        String systemContent = "角色提示...";
        if (context != null && !context.isBlank()) {
            systemContent += "\n\n上下文：\n" + context;
        }
        chatMemoryStore.updateMessages(conversationId,
                List.of(SystemMessage.from(systemContent)));
    }
    Result<String> result = researchAiService.chat(conversationId, question);
    return result.content();
}
```

### 五、踩坑速查

| 问题 | 原因 | 解决 |
|---|---|---|
| 模型无法记住上文 | 没配 `chatMemoryProvider` 或 id 不同 | 给 `MessageWindowChatMemory` 设 id 并注入 store |
| 模型说“没有上下文” | chat 方法上的 `@SystemMessage` 覆盖了记忆中的 system message | 移除 chat 方法的 `@SystemMessage`，全由记忆注入 |
| 删除消息报错“return null from primitive int” | `deleteByMemoryId` 用了 `@Select` | 改用 `@Delete` |
| 多参数时模板不识别 `{{it}}` | 存在 `@MemoryId` + 问题两个参数 | 用 `@V("question")` + `{{question}}` |
| 同 conversationId 第二次请求无记忆 | 旧后端进程仍在运行，没加载新代码 | 杀掉旧进程后重启 |

---

## S7：LangChain4j 工具调用 — 知识总结

> 2026-07-06

---

### 一、用 `@Tool` 把现有服务暴露给 LLM

```java
@Component
public class ResearchTools {

    private final ArxivFetcher arxivFetcher;
    // ...

    @Tool("Search arXiv for papers matching the query. Returns title, authors, summary, ...")
    public List<Map<String, Object>> searchArxiv(String query, int maxResults) {
        // 复用已有 fetcher
    }
}
```

- `@Tool` 的 value 就是 LLM 看到的“函数说明”，要写清楚输入、输出、用途。
- 工具方法可以是同步的； LangChain4j 会自动生成 schema 并在请求中发给模型。
- 失败时返回空结果或带 `error` 的 map，不要抛异常，否则整个 Agent 调用中断。

### 二、AiServices 注册工具

```java
@Bean
public ResearchToolAgent researchToolAgent(LangChain4jModelFactory modelFactory,
                                            ResearchTools researchTools) {
    return AiServices.builder(ResearchToolAgent.class)
            .chatModel(modelFactory.createChatModel())
            .tools(researchTools)
            .build();
}
```

- `.tools(...)` 可以传入单个工具实例或数组。
- 工具接口里不要有 `@MemoryId` 方法，除非你确实需要记忆。

### 三、工具型 Agent 与对话型 Agent 拆分的必要性

**不要**把需要工具的 `verifyGaps` 和需要记忆的 `chat` 放在同一个 `AiServices` 接口里，尤其当该接口配置了 `chatMemoryProvider`。

原因：
- 无 `@MemoryId` 的方法会使用默认 memoryId（如 `"default"`）。
- 如果该 memory 里存过内容为空的历史消息（例如早期调试），LangChain4j 加载时会抛 `IllegalArgumentException: text cannot be null`。
- 工具任务通常是无状态的，独立配置更干净。

推荐做法：

| Agent 接口 | 是否需要记忆 | 是否加载工具 | 示例方法 |
|---|---|---|---|
| `ResearchAiService` | 是 | 否 | `analyzePaper`、`chat` |
| `ResearchToolAgent` | 否 | 是 | `verifyGaps` |

### 四、工具返回值的约定

为了让 LLM 能正确理解工具结果，返回结构最好稳定：

- arXiv 搜索：返回 `List<Map<String, Object>>`，字段固定 `title`、`summary`、`arxivId`、`pdfUrl`。
- 本地搜索：返回 `id`、`title`、`year`、`pdfPath`。
- 错误：返回空集合或在 map 里加 `"error"` 字段，不要抛异常。

### 五、调用方解析工具 Agent 输出

工具 Agent 的 LLM 输出通常是 JSON，需要调用方自己解析：

```java
Result<String> result = researchToolAgent.verifyGaps(gapReport);
String json = extractJson(result.content());   // 去掉可能的 ```json 包裹
List<Map<String, Object>> verified = objectMapper.readValue(json, List.class);
```

务必保留 fallback：Agent 调用可能超时、模型不按要求返回 JSON、工具失败。

### 六、踩坑速查

| 问题 | 原因 | 解决 |
|---|---|---|
| `text cannot be null` 来自 `JdbcChatMemoryStore` | 历史消息中某条 AI message 内容为空 | 清理 conversation 表脏数据；把工具方法拆到无 memory 的 Agent |
| 模型不调用工具 | 工具说明写得太抽象 | `@Tool` value 里明确输入输出和用途 |
| 工具抛异常导致 Agent 失败 | 工具方法把外部 API 异常抛出 | 工具内部 try-catch，返回空结果或错误 map |
| 同个 Bean 既有记忆又有工具，工具方法报错 | 默认 memoryId 污染 | 拆分接口/Bean |

---

## S8：LangChain4j Agent 推荐 — 知识总结

> 2026-07-07

---

### 一、推荐类任务也适合结构化输出 POJO

标签、文件夹、阅读状态推荐的结果结构固定，非常适合用 POJO + `@Description` 让 LangChain4j 自动生成 JSON schema：

```java
public class TagSuggestionResult {
    @Description("3-5 个精准的技术关键词标签，英文优先，用列表返回")
    private List<String> tags;
    // getter/setter
}
```

接口方法：

```java
@SystemMessage("你是一位学术文献分类专家。请根据论文标题和摘要建议 3-5 个精准的技术关键词标签。")
@UserMessage("论文标题：{{title}}\n摘要：{{abstract}}\n\n请返回 JSON：{\"tags\":[\"tag1\", \"tag2\", ...]}")
Result<TagSuggestionResult> suggestTags(@V("title") String title, @V("abstract") String abstractText);
```

### 二、@Description 的正确包名

用于 POJO 字段描述的 `@Description` 必须是：

```java
import dev.langchain4j.model.output.structured.Description;
```

`dev.langchain4j.service.Description` 是用于服务方法参数的，不能用在 POJO 上。

### 三、推荐结果的后处理

LLM 返回的 POJO 可能不完全符合业务约束，调用方需要兜底：

```java
String normalizeReadingStatus(String raw) {
    return switch (raw.toUpperCase()) {
        case "READING" -> ReadingStatus.READING;
        case "READ" -> ReadingStatus.READ;
        default -> ReadingStatus.UNREAD;
    };
}
```

### 四、前端接线要点

- 标签建议：拿到字符串数组后，对已有标签直接勾选，不存在的标签先调 `POST /tags` 创建再勾选。
- 阅读状态建议：拿到 `{status, reason}` 后更新 `currentPaper.readingStatus` 并调保存接口。
- 文件夹推荐：导入对话框已有按钮，后端接口升级后前端无需改动。

### 五、踩坑速查

| 问题 | 原因 | 解决 |
|---|---|---|
| 编译找不到 `@Description` | 包名引错 | 用 `dev.langchain4j.model.output.structured.Description` |
| POJO 字段为空 | `@Description` 不够清晰或模型未按要求输出 | 优化描述，保留 fallback |
| 推荐结果不合法（如状态拼写错误） | 模型输出不稳定 | 调用方做归一化和默认值兜底 |

---

## S9：LangChain4j 流式输出 — 知识总结

> 2026-07-07

---

### 一、StreamingChatModel 基本用法

```java
StreamingChatModel model = modelFactory.createStreamingModel();

model.chat(
    List.of(SystemMessage.from(systemPrompt), UserMessage.from(userMessage)),
    new StreamingChatResponseHandler() {
        @Override
        public void onPartialResponse(String partialResponse) {
            // 每个 token
        }

        @Override
        public void onCompleteResponse(ChatResponse response) {
            // 流结束
        }

        @Override
        public void onError(Throwable error) {
            // 流式过程中出错
        }
    }
);
```

- 1.0.0 接口名是 `onPartialResponse` / `onCompleteResponse` / `onError`。
- 调用是阻塞的，直到整个流结束；如果要在 HTTP 响应中推送，需要确保调用方线程等待完成。

### 二、与 Spring StreamingResponseBody 配合

```java
@Override
public StreamingResponseBody chatStream(String systemPrompt, String userMessage) {
    return out -> streamService.streamChat(out, systemPrompt, userMessage);
}
```

- `streamChat` 内部负责 `CountDownLatch.await()`，等 LLM 流结束再返回，避免 Tomcat 提前关闭连接。
- 每收到 token 立即 `writer.flush()`，配合 Controller 里 `response.setBufferSize(0)` 保证实时推送。

### 三、处理客户端断开

流式响应期间用户可能刷新或关闭页面，此时 `writer.flush()` 会抛 `IOException`。需要：

```java
boolean outputClosed = false;

public void onPartialResponse(String token) {
    if (outputClosed) return;
    try {
        sendEvent(writer, "token", token);
        writer.flush();
    } catch (Exception e) {
        outputClosed = true;
        log.warn("客户端已断开，停止推送");
    }
}
```

否则 LangChain4j 会继续回调，产生大量 "Response not usable after response errors" 日志。

### 四、对 Provider 异常 JSON 的回退

LangChain4j 内部会用 Jackson 解析每个 SSE chunk。某些 Provider（如 Kimi）的 `reasoning_content` 字段可能返回非法 JSON，导致 LangChain4j 直接 `onError` 且一条 token 都不输出。

解决方案：
- 记录 `onError` 并不在前端写 error。
- 等 `model.chat` 返回后，如果 `fullContent` 为空，抛异常让上层走手动 SSE 解析。
- 手动解析可以 `try-catch` 每一行，忽略坏行，继续后续 token。

### 五、踩坑速查

| 问题 | 原因 | 解决 |
|---|---|---|
| 编译找不到 `StreamingResponseHandler` | 包名错误 | `dev.langchain4j.model.chat.response.StreamingChatResponseHandler` |
| 接口名 `onNext` 不存在 | 1.0.0 已改名 | 用 `onPartialResponse` |
| 前端收不到流 | 未 `flush` 或 Tomcat 缓冲 | `setBufferSize(0)` + 每次 `writer.flush()` |
| 客户端断开后日志刷屏 | 继续向已关闭的 response 写入 | 加 `outputClosed` 标志 |
| LangChain4j 流式报错但手动可以 | Provider 返回非法 JSON | 保留手动 SSE 回退 |

---