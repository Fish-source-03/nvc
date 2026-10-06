# 08 · 登出接口缺失导致 Refresh Token 无法撤销

> **严重程度**：🟠 中高
> **所属模块**：agent-qr-auth（AuthController、RefreshTokenService）、agent-qr-web-frontend
> **设计依据**：《系统详细设计说明书》§8.15.5（`revoke(userId)` 强制登出）、§3.2.12（`/api/auth/refresh` 为白名单端点）
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析（未运行服务）

---

## 一、问题描述

前端的登出流程会调用 `POST /api/auth/revoke`，但**后端不存在该端点**。同时，后端 `RefreshTokenService` 中已实现的 `revoke(userId)` 方法**没有任何调用方**。

后果有两层：

1. **功能层**：用户点击"退出"时会发出一次注定失败的请求，前端错误拦截器会弹出"网络连接失败"提示（登出本身因异常被 catch 而继续执行，所以用户仍能退出）。
2. **安全层**：退出登录只清除了浏览器本地的 Token，**服务端 Refresh Token 仍然有效**（默认 7 天），可被用于继续签发 Access Token。该行为与 P2 双 Token 机制的设计意图不符。

---

## 二、推断依据

### 依据 1：前端确实调用了该端点

`agent-qr-web-frontend/src/api/auth.ts:19-21`

```typescript
export function revokeToken() {
  return request.post('/api/auth/revoke')
}
```

`agent-qr-web-frontend/src/stores/auth.ts:190-195` 在 logout 流程中先调用 `authApi.revokeToken()`。

### 依据 2：后端不存在该映射

`agent-qr-auth/src/main/java/org/example/agent_qr/auth/controller/AuthController.java` 的全部请求映射仅为四处：

| 行号 | 端点 |
|---|---|
| `:41` | `POST /api/auth/login` |
| `:53` | `POST /api/auth/register` |
| `:68` | `POST /api/auth/refresh` |
| `:79` | `GET /api/auth/info` |

无 `/revoke`。全仓库检索 `revoke` 的 Controller 映射亦无命中。

### 依据 3：Service 侧已实现但没有调用方

`agent-qr-auth/.../service/RefreshTokenService.java:102-106`

```java
/**
 * 撤销用户的所有 Refresh Token。
 */
public void revoke(Long userId) { ... }
```

该方法是 public，但全仓库无任何调用点（grep `revoke(` 仅命中定义处与前端 TS 文件）。

### 依据 4：前端错误处理会弹提示

`agent-qr-web-frontend/src/api/index.ts:132-139` 的 axios 错误拦截器对任意 HTTP 错误统一提示"网络连接失败"。因此 404 会转化为用户可见的错误弹窗。

### 依据 5：设计文档的意图

§8.15.5 明确列出 `revoke(userId)` 为"强制登出"能力；§3.2.12 也把 refresh 相关的管理端点纳入设计。当前状态是"service 层做了、controller 层没接"。

---

## 三、影响范围

1. **安全**：登出后 Refresh Token 仍可用 7 天。在共享设备或 Token 被窃取的场景下，攻击者可在用户"已登出"后继续获取新的 Access Token。
2. **体验**：每次登出都会出现一次无意义的错误提示。
3. **一致性**：与 P2 双 Token 机制（含令牌轮换、撤销表 `token_refresh`）的设计目标不符——表建了、字段有了、状态位有了，但没有任何入口去触发吊销。

---

## 四、修复方向

1. **补一个 Controller 端点**：在 `AuthController` 增加 `POST /api/auth/revoke`，从 `SecurityContext` 取当前用户 id，调用 `refreshTokenService.revoke(userId)`，返回 `Result.success()`。
2. **在 SecurityConfig 中保持其受保护状态**（不应加入白名单，需 `authenticated()`）。
3. **前端容错**：即使后端未修复，前端的 logout 也不应因撤销失败而弹出错误提示——建议对 revoke 调用单独捕获并忽略错误（当前依赖 catch 兜底，但拦截器已先一步弹窗）。
4. **考虑过期清理**：`token_refresh` 表的 `expire_time` 字段当前写入后无读取处，建议补一个定时清理任务（注意：需先解决文档 01 的 `@EnableScheduling` 缺失）。

---

## 五、核查边界

- 静态分析，未实际发起登出请求验证 404。
- 未连接数据库确认 `token_refresh` 表中当前有效令牌的数量。
- 未确认是否存在通过其他途径（如管理后台、运维脚本）调用 `revoke` 的可能——仓库内无相应代码。
