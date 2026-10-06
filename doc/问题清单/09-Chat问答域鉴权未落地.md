# 09 · Chat 问答域鉴权未落地（§3.2.13 未实现）

> **严重程度**：🔴 高（安全缺陷）
> **所属模块**：agent-qr-rag（ChatController、ChatQueryService）
> **设计依据**：《系统详细设计说明书》§3.2.13（ChatController 的 `@PreAuthorize("@abac.canQueryDomain(principal,#request.domain)")`）
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析（未运行服务）

---

## 一、问题描述

ABAC 体系中，"用户只能检索自己有权访问的业务域"这条核心约束，在 RAG 问答链路上**完全没有实现**：

1. `ChatController` 的问答接口没有任何 ABAC 注解或权限判断；
2. 请求体不接受 `domain` 参数（设计文档要求在请求中传入业务域）；
3. `AbacEvaluator.canQueryDomain(...)` 作为该约束的判定入口，**没有任何生产调用方**——只在 `AbacEvaluator` 内部被另一方法引用。

结果是：任何登录用户都可以对全部业务域的知识发起提问，检索范围不受其 `allowedDomains` / `clearanceLevel` 限制。**这是 ABAC 在问答主链路上的完全缺失**，而非部分降级。

---

## 二、推断依据

### 依据 1：设计文档明确要求

《系统详细设计说明书》§3.2.13（说明书 1031-1038 行）规定 ChatController 的问答接口应带：

```java
@PreAuthorize("@abac.canQueryDomain(principal, #request.domain)")
```

### 依据 2：实际实现无任何鉴权

`agent-qr-rag/src/main/java/org/example/agent_qr/rag/controller/ChatController.java:50-62`

```java
@PostMapping("/ask")
public Result<Map<String, Object>> ask(@RequestBody Map<String, Object> request) {
    String query = (String) request.get("query");
    if (query == null || query.isBlank()) {
        throw new BusinessException("问题不能为空");
    }
    Long conversationId = request.get("conversationId") != null
            ? Long.valueOf(request.get("conversationId").toString())
            : null;
    Long userId = getCurrentUserId();
    Map<String, Object> result = chatQueryService.ask(query, conversationId, userId);
    return Result.success(result);
}
```

- 无 `@PreAuthorize`；
- 请求体只读取 `query` 与 `conversationId`，**不读取 `domain`**；
- 仅取了 `userId` 用于会话归属，未做任何权限判定。

`ChatController.java:74` 的 SSE 端点 `/ask/stream` 同样如此（且见文档 30：前端传入的 `domain` 被静默丢弃）。

### 依据 3：`canQueryDomain` 无生产调用方

```bash
grep -rn "canQueryDomain" --include=*.java . | grep -v target
```

命中仅 3 处，全部在 `AbacEvaluator.java` 自身：

| 行号 | 性质 |
|---|---|
| `:53` | 方法定义 |
| `:59` | 方法内部的拒绝日志 |
| `:119` | 被同类的 `canUploadToDomain` 内部调用 |

即：**没有任何 Controller 或 Service 调用它**。该方法是"为被使用而写、但从未被使用"。

### 依据 4：域过滤在检索层也不生效

即使绕过鉴权问题，检索层也没有按用户权限裁剪候选集：
- `HybridRetriever` 的域过滤来自路由结果，而非用户权限（见文档 18）；
- 当域过滤器存在但候选集为空时，该过滤会被静默跳过（见文档 18），退化为全库检索。

两条路径都没有"按用户权限限制检索范围"的逻辑。

### 依据 5：对照其他模块

`KnowledgeController.java:49` 的上传接口确实使用了 `@PreAuthorize("@abac.canUploadToDomain(principal,#domain)")`，说明 ABAC 注解式鉴权在本项目中是可用的、其他模块也在用——Chat 模块属于**遗漏**而非"机制不可用"。

---

## 三、影响范围

1. **越权检索**：普通用户可检索高密级/无权限业务域的知识内容，并让 LLM 将其组织成自然语言答案返回。这比"列表接口泄露"更危险，因为它直接输出了内容本身。
2. **ABAC 覆盖率虚高**：从注解数量上看 ABAC 已铺开（auth/knowledge/datasource/compensation 四处），但问答这条**最核心的业务链路**没有覆盖。
3. **与文档 07 叠加**：用户刷新令牌后 ABAC 属性全部丢失，即便修复了 Chat 域鉴权，判定也会因属性为空而拒绝——两个缺陷需一并修复。
4. **前端已具备 UI 但后端未接**：`ChatInput.vue:35-76` 已实现域选择器，用户以为在限定范围，实际无效。

---

## 四、修复方向

1. **接入 ABAC 判定**：在 `ChatController` 的 `/ask` 与 `/ask/stream` 上补 `@PreAuthorize("@abac.canQueryDomain(principal, #request.domain)")`，并在请求体中解析 `domain`。
2. **处理"未指定域"的语义**：设计文档中 `canQueryDomain` 对 `domain == null` 是**放行**（`AbacEvaluator.java:53-65` + `UserPrincipal.hasDomainAccess`）。若不指定域即放行，等于给了绕过入口——建议在问答链路上**强制要求 domain**，或对全局检索按用户 `allowedDomains` 做**检索后过滤**。
3. **检索层兜底**：不能只依赖入口鉴权。建议在 `HybridRetriever` 或 `StructuredFilterService` 中按当前用户的 `allowedDomains` 强制裁剪候选集，形成"入口校验 + 检索兜底"双保险。
4. **补充测试**：至少一条"用户 A 提问用户 B 的业务域应被拒绝"的用例。

---

## 五、核查边界

- 静态分析，未实际发起问答请求验证越权。
- 未确认前端域选择器传入的 `domain` 值域与后端 `allowedDomains` 的取值是否使用同一套枚举（该对齐问题独立于本缺陷）。
- 未评估 `ChatQueryService` 内部是否存在未在签名中体现的权限逻辑——已检索该类未引用 `AbacEvaluator`。
