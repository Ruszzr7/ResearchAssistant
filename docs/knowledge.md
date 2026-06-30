# 知识总结

## 阶段 1：项目脚手架 — 技术全解

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

## 阶段 2：论文库开发 — 知识总结

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