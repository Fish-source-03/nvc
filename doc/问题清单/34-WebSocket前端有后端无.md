# 34 · WebSocket 前端有后端无

> **严重程度**：🟡 中
> **所属模块**：agent-qr-web-frontend（useWebSocket）、agent-qr-web（SecurityConfig）
> **设计依据**：`doc/p3-prompt.md`（P3 双向通信）；《系统详细设计说明书》§9 未提及 WebSocket
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析（未编译、未运行）

---

## 一、问题描述

前端实现了完整的 WebSocket 客户端（STOMP over SockJS，含连接、订阅、发送、降级），而后端**完全没有 WebSocket 服务端实现**：

- 无 `@EnableWebSocketMessageBroker`；
- 无 `registerStompEndpoints` 端点注册；
- 无 `spring-boot-starter-websocket` 依赖。

后端仅有 `SecurityConfig.java:51` 一行 `/ws/**` 的放行规则——**放行了一条不存在的路径**。前端的 WebSocket 连接必然失败，实际靠 catch 降级为 SSE。

因此 `useWebSocket()` 暴露的 `send` / `subscribe` 能力**全是死代码**，不影响当前功能（因有 SSE 降级），但构成能力虚标。

---

## 二、推断依据

### 依据 1：前端有完整实现

`agent-qr-web-frontend/src/composables/useWebSocket.ts`

```
:23      连接 ${VITE_WS_URL}/ws
:38-40   使用 @stomp/stompjs + sockjs-client
:51      VITE_WS_URL 相关配置读取
```

`package.json:21,26` 声明了 `@stomp/stompjs` 与 `sockjs-client` 依赖。

`src/views/chat/ChatView.vue:334` 在组件挂载时尝试建立连接。

### 依据 2：后端无服务端实现

```bash
grep -rn "EnableWebSocket\|WebSocketConfig\|registerStompEndpoints\|SimpMessagingTemplate" --include=*.java . | grep -v target
# 零命中
```

`agent-qr-web/pom.xml` 依赖列表中**无 `spring-boot-starter-websocket`**（grep 零命中）。

### 依据 3：仅有一条"悬空"的放行规则

`agent-qr-web/src/main/java/org/example/agent_qr/web/config/SecurityConfig.java`（`:31` 注释、`:51` 规则）

```java
// 注释声称："P3 新增 WebSocket 端点放行以支持 STOMP over WebSocket"
.requestMatchers("/ws/**", "/ws/info").permitAll()
```

放行一条无实现的路径，说明实现计划存在但未完成。

### 依据 4：前端有 SSE 降级兜底

`ChatView.vue:337` 的 catch 分支在 WebSocket 连接失败时降级为 SSE。因此当前功能可用，缺陷不表现为"聊天不能用"，而是"双向通信能力不存在"。

### 依据 5：前端无失败提示

`useWebSocket.ts` 的 `send` / `subscribe` 在未连接状态下不会抛错（属死代码），调用方无从感知。

---

## 三、影响范围

1. **P3 能力虚标**：若 P3 验收标准包含"双向实时通信"，该项未达成。
2. **无谓的资源消耗**：每次打开聊天页都会尝试一次注定失败的连接（含 SockJS 的握手重试），产生控制台错误与网络请求噪音。
3. **依赖体积**：`@stomp/stompjs` + `sockjs-client` 打包进前端产物但完全未生效。
4. **安全面**：`/ws/**` 的 `permitAll()` 放行规则目前无害（无端点），但若将来实现时忘记收敛，会成为一个未鉴权的入口。

---

## 四、修复方向

1. **二选一**：
   - **方案 A（补齐后端）**：引入 `spring-boot-starter-websocket`，实现 `@EnableWebSocketMessageBroker` + STOMP 端点，把 `/ws/**` 的放行规则收敛为带鉴权的握手；
   - **方案 B（移除前端）**：删除 `useWebSocket.ts` 及其调用、移除两个依赖、同时删除后端那条悬空的放行规则与注释。
2. **无论哪个方案，都建议先明确产品需求**：当前 SSE 已能满足流式问答，需要确认 WebSocket 要解决的具体场景（如服务端主动推送、多端同步、协作）——若无明确场景，方案 B 更合适。
3. **若选择方案 A，注意鉴权**：STOMP 握手需接入现有 JWT 机制，不能沿用 `permitAll()`。

---

## 五、核查边界

- 静态分析，未运行前端/后端，未实际观测连接失败。
- 未确认 P3 的正式验收标准（`doc/p3-prompt.md` 是实施 Prompt 而非正式设计文档），因此"是否应当实现"需向作者确认。
- 未评估 SockJS 连接失败对页面性能的实际影响。
