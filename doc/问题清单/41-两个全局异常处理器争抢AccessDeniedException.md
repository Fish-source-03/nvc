# 41 · 两个全局异常处理器争抢 AccessDeniedException

> **严重程度**：🟡 中
> **所属模块**：agent-qr-web（GlobalExceptionHandler）、agent-qr-auth（AbacAccessDeniedHandler）
> **设计依据**：《系统详细设计说明书》§3.2.14 AbacAccessDeniedHandler（要求返回 `ResponseEntity<Result<Void>>` + 结构化审计字段）
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析（未编译、未运行）

---

## 一、问题描述

项目中存在**两个** `@RestControllerAdvice`，都声明了处理 `AccessDeniedException` 的 `@ExceptionHandler`，且**均未标注 `@Order`**。Spring 在多个 Advice 处理同一异常时，生效者取决于 Bean 的注册/排序顺序——**行为不确定**。

同时，`AbacAccessDeniedHandler` 的返回体与设计文档不符：

- 设计 §3.2.14 要求返回 `ResponseEntity<Result<Void>>`（统一封装）；
- 实际返回**裸 `Map`**；
- 设计要求的审计字段包含 `uri` / `method`，实际**缺失**。

后果：ABAC 拒绝响应的结构与状态码不确定，前端可能把它当作"网络错误"而非"权限不足"处理。

---

## 二、推断依据

### 依据 1：两个 Advice 处理同一异常

`agent-qr-web/src/main/java/org/example/agent_qr/web/config/GlobalExceptionHandler.java:41-45`

```java
@ExceptionHandler(AccessDeniedException.class)
public Result<Void> handleAccessDenied(...) { ... }
```

`agent-qr-auth/src/main/java/org/example/agent_qr/auth/handler/AbacAccessDeniedHandler.java:27,36-57`

```java
@RestControllerAdvice
public class AbacAccessDeniedHandler {
    @ExceptionHandler(AccessDeniedException.class)
    public ... handle(...) { ... }
}
```

两者均无 `@Order` 注解。

### 依据 2：状态码与响应结构不一致

| | `GlobalExceptionHandler` | `AbacAccessDeniedHandler` |
|---|---|---|
| HTTP 状态码 | HTTP 200 + body 中 `code=403` | HTTP 403 |
| 响应体 | 统一 `Result` 封装 | **裸 `Map`**（`:37,56`） |

设计 §3.2.14 明确要求后者使用 `ResponseEntity<Result<Void>>`。实际实现既不是 `ResponseEntity` 的语义（直接返回 Map），也不是统一封装。

### 依据 3：审计字段缺失

设计 §3.2.14 要求审计字段包含：`user` / `department` / `uri` / `method` / `traceId` / `timestamp` / `reason`。

实际 `AbacAccessDeniedHandler.java:36-57` 的字段为：`timestamp` / `status` / `error` / `message` / `traceId` + `userId` / `username` / `role` / `department` —— **缺 `uri` 与 `method`**。

即"谁在什么时候被拒绝了什么权限"这条审计线索中，**"访问了哪个接口"这一关键信息丢失**。

### 依据 4：前端会把它当作网络错误

`agent-qr-web-frontend/src/api/index.ts:119-122,137` 的响应拦截器按统一 `Result` 结构判断业务错误。若 `AbacAccessDeniedHandler` 生效（返回裸 Map + HTTP 403），axios 会走 error 分支并提示"网络连接失败"，而不是"权限不足"。

---

## 三、影响范围

1. **行为不确定**：同一个请求在不同启动顺序下可能得到不同响应——这类不确定性问题的排查成本极高。
2. **审计不完整**：缺少 `uri` / `method`，无法回答"哪个接口被越权访问"。
3. **前端提示错误**：用户看到"网络连接失败"，实际是权限不足，会误导排查方向。
4. **与文档 09 关联**：ABAC 在问答链路上未落地（文档 09），而这条已落地的 ABAC 拒绝路径又存在响应结构问题——ABAC 的可观测性整体偏弱。

---

## 四、修复方向

1. **明确唯一处理者**：保留一个 `@ExceptionHandler(AccessDeniedException.class)`，或给两者加 `@Order` 明确优先级。建议让 `AbacAccessDeniedHandler` 专责 ABAC 拒绝（携带丰富的审计信息），`GlobalExceptionHandler` 移除该异常的处理器。
2. **对齐设计的响应结构**：`AbacAccessDeniedHandler` 改为返回 `ResponseEntity<Result<Void>>`，保持全站响应结构统一（前端拦截器依赖该结构）。
3. **补全审计字段**：增加 `uri` 与 `method`（可从 `HttpServletRequest` 获取）。
4. **决策状态码**：HTTP 200 + `code=403`（统一封装风格）还是 HTTP 403（语义正确）——需与前端约定一致后统一。

---

## 五、核查边界

- 静态分析，**未运行服务，未实测两个 Advice 的实际生效者**（这取决于 Spring 的 Bean 排序，需运行验证）。
- 未检查是否存在第三个处理 `AccessDeniedException` 的地方（已全仓检索该异常类，仅此两处）。
- 未确认前端的 403 处理逻辑在其他路径上是否有兜底。
