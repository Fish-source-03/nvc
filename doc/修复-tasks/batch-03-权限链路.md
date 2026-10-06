# 批次 03 · 权限链路修复

> **涵盖问题**：41（双异常处理器争抢）、07（刷新令牌丢 ABAC）、06（用户列表无鉴权+口令外泄）、08（登出接口缺失）、09（Chat 域鉴权未落地）、33 断裂 2/3/4（前后端契约）、**问题 39 的 B2（`canModifyUser` 实现偏离设计）**
> **前置依赖**：批次 01 任务 1.0（测试基础设施）
> **批次内顺序**：**严格** 3.1 → 3.2 → 3.3 → 3.4 → 3.5 → 3.6
> **可并行**：与批次 02、06、08、09 无文件交集；但本批次触及高频路径，完成后需回归验证

---

## 批次目标

修复权限管控链路上的三处断裂与两处结构性缺陷，并同步对齐前后端契约。

> ⚠️ **硬约束 3**：任务 3.2（07 刷新令牌）**必须**先于 3.3（06 admin 鉴权）。否则管理员登录后刷新一次令牌，就会被自己的修复锁在门外。

---

## 涉及文件

| 文件 | 任务 |
|---|---|
| `agent-qr-web/.../config/GlobalExceptionHandler.java` | 3.1 |
| `agent-qr-auth/.../handler/AbacAccessDeniedHandler.java` | 3.1 |
| `agent-qr-auth/.../service/RefreshTokenService.java` | 3.2 |
| `agent-qr-auth/.../controller/AdminController.java` | 3.3（06 + 33 断裂2，**一次改完**） |
| `agent-qr-user/.../entity/SysUser.java` | 3.3 |
| `agent-qr-web/.../config/SecurityConfig.java` | 3.3 |
| `agent-qr-auth/.../controller/AuthController.java` | 3.4 |
| `agent-qr-web-frontend/src/api/auth.ts`（如需） | 3.4 |
| `agent-qr-rag/.../controller/ChatController.java` | 3.5（09 + 33 断裂3，**一次改完**） |
| `agent-qr-rag/.../service/ChatQueryService.java` | 3.5 |
| `agent-qr-rag/.../retriever/HybridRetriever.java` | 3.5（检索侧兜底，如需） |
| `agent-qr-auth/.../evaluator/AbacEvaluator.java` | 3.6 |
| `doc/系统详细设计说明书.md` | 3.6（**仅限 `canModifyUser` 相关章节**，且仅在 3.6.2 决定增加约束时才需改） |

**不得修改**：本批次之外的任何文件。

---

## 任务 3.1 — 统一 AccessDeniedException 的处理者（问题 41）

> 问题详情：`doc/问题清单/41-两个全局异常处理器争抢AccessDeniedException.md`

**本任务必须最先执行**：3.3 与 3.5 修复后才开始高频产生 403 响应，若响应结构不确定，前端会把它显示为"网络连接失败"。

- [ ] **3.1.1** 明确唯一处理者
  - 当前 `GlobalExceptionHandler` 与 `AbacAccessDeniedHandler` 都处理 `AccessDeniedException`，且均无 `@Order`
  - **建议**：让 `AbacAccessDeniedHandler` 专责 ABAC 拒绝（携带丰富审计信息），从 `GlobalExceptionHandler` 中移除该异常的处理器
  - 若选择保留两者，必须加 `@Order` 明确优先级

- [ ] **3.1.2** 对齐响应结构
  - `AbacAccessDeniedHandler` 当前返回**裸 `Map`**，设计 §3.2.14 要求 `ResponseEntity<Result<Void>>`
  - 改为统一 `Result` 封装（前端拦截器依赖该结构）

- [ ] **3.1.3** 补全审计字段
  - 设计 §3.2.14 要求：`user` / `department` / `uri` / `method` / `traceId` / `timestamp` / `reason`
  - 当前缺 `uri` 与 `method`，从 `HttpServletRequest` 获取并补上

- [ ] **3.1.4** 决策并统一 HTTP 状态码
  - 两条路径当前不一致（HTTP 200 + body code=403 vs HTTP 403）
  - **建议倾向 HTTP 403**（语义正确），但需与前端拦截器约定一致后统一
  - 与 `33` 的修复保持一致

### 补充测试

- [ ] 用例：ABAC 拒绝时返回的响应体结构为统一 `Result` 格式（含 `code` / `message`）
- [ ] 用例：审计字段包含 `uri` 与 `method`
- [ ] 用例：只有一个处理器生效（避免歧义）

### 验收标准

- [ ] 全仓库仅有一个 `@ExceptionHandler(AccessDeniedException.class)`，或两者有明确的 `@Order`
- [ ] 拒绝响应为统一 `Result` 结构
- [ ] 审计字段完整（含 `uri`、`method`）
- [ ] 补充的测试通过

### 禁止事项

- ❌ 不要删除 ABAC 的审计日志能力（这是本处理器的主要价值）
- ❌ 不要改变其他异常（`BusinessException`、`ValidationException`）的处理逻辑

---

## 任务 3.2 — 刷新令牌保留 ABAC 属性与角色（问题 07）

> 问题详情：`doc/问题清单/07-刷新令牌导致ABAC属性与角色丢失.md`

**硬约束 3：本任务必须先于 3.3 完成。**

- [ ] **3.2.1** 从数据库重新加载完整用户
  - 当前 `refresh()` 构造空 `SysUser`，只回填 `id` 与 `username`
  - 改为根据 `stored.getUserId()` 调用 `sysUserMapper.selectById(...)` 获取完整记录

- [ ] **3.2.2** 删除错误的三元表达式
  - 当前：`user.setRole(jwtUtil.getUsernameFromToken(refreshToken) != null ? "user" : "user")`
  - **两个分支字面量完全相同**，属书写错误；角色应取自数据库记录

- [ ] **3.2.3** 校验用户状态
  - 重新加载后应校验 `status`，避免已禁用/已删除用户通过刷新继续获得令牌
  - 若状态异常应拒绝刷新并返回明确错误

- [ ] **3.2.4** 确认 ABAC 字段完整传递
  - `department` / `clearanceLevel` / `allowedDomains` / `title` 都应进入新 Access Token
  - 链路：`SysUser` → `JwtUtil.generateAccessToken` → `UserPrincipal.fromClaims`

### 补充测试（**本批次最关键的一组测试**）

- [ ] 用例：管理员刷新令牌后，新 Access Token 的 role 仍为 `admin`（**这条用例能拦住原缺陷**）
- [ ] 用例：刷新后 `department` / `clearanceLevel` / `allowedDomains` / `title` 与刷新前一致
- [ ] 用例：已禁用用户刷新应被拒绝
- [ ] 用例：`UserPrincipal.isAdmin()` 在刷新后仍返回 true

### 验收标准

- [ ] `refresh()` 不再构造空 `SysUser`，改为从数据库加载
- [ ] 三元表达式错误已修正
- [ ] 上述 4 条测试全部通过

### 禁止事项

- ❌ 不要修改 `JwtUtil` 的 claim 生成格式（格式是正确的，问题在取值来源）
- ❌ 不要修改 Refresh Token 的轮换逻辑（当前 `revoked=true` 的实现等价有效）

---

## 任务 3.3 — 用户列表鉴权与口令外泄（问题 06 + 33 断裂 2）

> 问题详情：`doc/问题清单/06-用户列表接口无鉴权且泄露口令哈希.md`、`doc/问题清单/33-前后端契约断裂.md`（断裂 2）

**本任务合并修改 `AdminController.listUsers`——它同时被两个问题触及，分两次改会互相覆盖。**

- [ ] **3.3.1** 给 `listUsers` 加权限校验
  - 二选一（建议都做）：
    - 在方法上加 `@PreAuthorize("hasRole('ADMIN')")`
    - 将 `SecurityConfig.java` 的 `/api/admin/**` 从 `.authenticated()` 改为 `hasRole("ADMIN")`（与设计 §3.2.12 对齐）

- [ ] **3.3.2** 阻断口令外泄
  - 给 `SysUser.password` 加 `@JsonIgnore`
  - **更稳妥**：改为返回不含敏感字段的 VO/DTO，避免后续新增敏感字段时再次遗漏
  - 二选一或都做

- [ ] **3.3.3**（33 断裂 2）补齐 `department` / `title` 查询参数
  - 前端 `src/api/user.ts` 与 `UserManageView.vue` 已经在传这两个参数，后端只声明了 `page`/`size`/`keyword`，被 Spring 静默忽略
  - 在 `listUsers` 上增加 `@RequestParam(required = false) String department` 与 `String title`
  - 透传到 `SysUserMapper.selectPage` 的查询条件（需同步改 SQL）

### 补充测试（**本批次最关键的一组测试**）

- [ ] 用例：普通用户（role=user）访问 `GET /api/admin/users` 返回 403
- [ ] 用例：管理员访问返回 200
- [ ] 用例：响应 JSON 中**不包含** `password` 字段（**这条用例能拦住口令外泄**）
- [ ] 用例：传入 `department` / `title` 时查询条件生效（返回结果被正确过滤）

### 验收标准

- [ ] 普通用户无法访问用户列表接口
- [ ] 响应体不含 `password`
- [ ] `department` / `title` 筛选真实生效
- [ ] 上述 4 条测试全部通过

### 禁止事项

- ❌ 不要删除 `keyword` 参数（现有功能）
- ❌ 不要改成返回实体以外的结构时丢失前端依赖的 `IPage` 分页字段（`records`/`total`/`size`/`current`/`pages`）

---

## 任务 3.4 — 补齐登出接口（问题 08 + 33 断裂 4）

> 问题详情：`doc/问题清单/08-登出接口缺失导致RefreshToken无法撤销.md`、`doc/问题清单/33-前后端契约断裂.md`（断裂 4）

- [ ] **3.4.1** 在 `AuthController` 增加 `POST /api/auth/revoke`
  - 从 `SecurityContext` 取当前用户 id
  - 调用已存在但无调用方的 `refreshTokenService.revoke(userId)`
  - 返回 `Result.success()`

- [ ] **3.4.2** 确认安全配置
  - 该端点**不应**加入白名单，需 `authenticated()`
  - 确认 `SecurityConfig` 的 `anyRequest().authenticated()` 已覆盖

- [ ] **3.4.3** 前端容错（可选，但建议）
  - 即使后端已补齐，前端 logout 流程不应因 revoke 失败而弹出错误提示
  - 检查 `src/stores/auth.ts` 的调用是否单独捕获并忽略错误

### 补充测试

- [ ] 用例：调用 `POST /api/auth/revoke` 后，该用户的 Refresh Token 被标记为 `revoked`
- [ ] 用例：撤销后使用旧 Refresh Token 刷新应被拒绝
- [ ] 用例：未携带 Token 调用该端点返回 401/403

### 验收标准

- [ ] 端点存在且可被调用
- [ ] 调用后 `token_refresh` 表中对应用户的令牌状态为已撤销
- [ ] 撤销后无法再用旧 Refresh Token 换新令牌
- [ ] 上述 3 条测试通过

### 禁止事项

- ❌ 不要把 `/api/auth/revoke` 加入 `permitAll` 白名单
- ❌ 不要改动 `/api/auth/refresh` 的现有逻辑

---

## 任务 3.5 — Chat 问答域鉴权（问题 09 + 33 断裂 3）

> 问题详情：`doc/问题清单/09-Chat问答域鉴权未落地.md`、`doc/问题清单/33-前后端契约断裂.md`（断裂 3）

**本任务合并修改 `ChatController`——它同时被两个问题触及。**

- [ ] **3.5.1** 解析请求体中的 `domain`（33 断裂 3）
  - 前端 `src/api/chat.ts` 的 `askStream` 已经在传 `domain`，后端 `ChatController` 只取 `query`/`conversationId`
  - 在 `/ask` 与 `/ask/stream` 中解析 `domain` 并向下传递

- [ ] **3.5.2** 接入 ABAC 判定（09）
  - 按设计 §3.2.13 要求，使用 `canQueryDomain` 做域校验
  - **注意**：`canQueryDomain` 对 `domain == null` 是**放行**的。直接在入口放行会导致绕过，需明确策略：
    - **建议**：问答接口**强制要求 domain**（缺失时返回 400），避免"不传域即全局检索"的绕过路径
    - 若选择允许缺省，则必须在检索层按用户 `allowedDomains` 兜底过滤

- [ ] **3.5.3** 检索侧兜底（**不应只依赖入口鉴权**）
  - 在 `HybridRetriever` 或 `StructuredFilterService` 中按当前用户的 `allowedDomains` 强制裁剪候选集
  - 形成"入口校验 + 检索兜底"双保险
  - **注意**：此项与批次 04 的「任务 4.2」同改 `HybridRetriever`，本批次只做权限相关的过滤，不要改动空候选集的守卫逻辑

### 补充测试

- [ ] 用例：用户 A 携带用户 A 无权限的 `domain` 提问，返回 403
- [ ] 用例：用户 A 携带自身有权限的 `domain` 提问，正常返回
- [ ] 用例：未携带 `domain` 的请求按既定策略处理（强制拒绝或按 allowedDomains 兜底）——**测试需固化该策略**
- [ ] 用例：检索层按 `allowedDomains` 裁剪后，不返回越域内容

### 验收标准

- [ ] `/ask` 与 `/ask/stream` 都接收并校验 `domain`
- [ ] 越域提问被拒绝
- [ ] 检索层有独立的权限兜底（不依赖入口）
- [ ] 上述 4 条测试通过

### 禁止事项

- ❌ **不要修改 `HybridRetriever` 中空候选集的守卫逻辑**（属批次 04 的任务 4.2，本批次只加权限过滤）
- ❌ 不要给 `canQueryDomain` 增加 `domain == null` 时抛异常的默认行为（会影响其他调用方）
- ❌ 不要移除前端已有的域选择器控件

---

## 任务 3.6 — 恢复 `canModifyUser` 的 admin 直通（问题 5，已确认的实现错误）

> 问题详情：`doc/问题清单/39-设计文档内部矛盾与实现脱节.md` 的 B2 项
> **来源**：2026-10-06 的待确认事项 #5，**已确认以设计为准**

- [ ] **3.6.1** 恢复设计原文的逻辑
  - **设计 §3.2.9 原文**：
    ```java
    public boolean canModifyUser(UserPrincipal user, Long targetUserId) {
        if (user.isAdmin()) return true;
        if (user.getUserId().equals(targetUserId)) return true;
        return false;
    }
    ```
  - **当前实现**：本人放行；编辑他人需**职级与密级双高于目标**；**admin 不直通**
  - **确认结论**：实现的变更是**偏离设计的错误**——它会导致 admin 无法管理用户（除非其职级恰好高于目标），使"用户管理"功能实际不可用
  - 改为与设计一致

- [ ] **3.6.2** ⚠️ 评估是否需要补充"防自提权"约束
  - 恢复 admin 直通后，admin 将可以修改**自己的** `title` / `clearanceLevel` / `role` / `allowedDomains`
  - **这是真实的权限膨胀风险**——admin 可给自己提权
  - **请评估后决定**是否增加约束（如"不可修改自己的职级/密级/角色"），并在报告中说明
  - 已知可复用的资产：`AdminController` 中已有针对自编辑的字段级限制（`UpdateUserDTO` 相关）
  - **若决定增加约束，需同步更新设计文档**（设计原文没有这一层）

### 补充测试

- [ ] 用例：admin 可修改任意用户（**这条用例能拦住原缺陷**）
- [ ] 用例：普通用户只能修改自己
- [ ] 用例：普通用户修改他人被拒绝
- [ ] 用例：（若 3.6.2 增加约束）admin 修改自己的职级/角色被拒绝

### 验收标准

- [ ] `canModifyUser` 的 admin 分支与设计一致
- [ ] 3.6.2 的评估结论已在报告中说明
- [ ] 上述测试通过

### 禁止事项

- ❌ 不要保留"职级与密级双高于目标"的判定（那是导致 admin 不可用的原因）
- ❌ 若增加防自提权约束，必须同步回填设计文档，不要只改代码

---

## 批次验收

- [ ] 任务 3.1、3.2、3.3、3.4、3.5 全部完成
- [ ] **硬约束 3 未被违反**（3.2 先于 3.3）
- [ ] 全量回归：登录 → 刷新令牌 → 访问 admin 接口 → 问答 → 登出，全流程正常
- [ ] 项目可编译，`mvn test` 通过
- [ ] 已更新 `progress.md`

## 回归验证建议

1. 用普通用户登录，依次访问 `/api/admin/users`、问答接口（越域），确认都被拒绝
2. 用管理员登录，等待/触发一次令牌刷新，确认仍可访问 admin 接口（**验证硬约束 3**）
3. 登出后，用旧 Refresh Token 尝试刷新，确认被拒绝
4. 检查用户列表响应的原始 JSON，确认无 `password` 字段

---

## 子 Agent 启动指令（可复制）

```
执行 doc/修复-tasks/batch-03-权限链路.md 的全部任务。

严格按 3.1 → 3.2 → 3.3 → 3.4 → 3.5 顺序，不得调整。
关键：3.2（刷新令牌）必须先于 3.3（admin 鉴权），否则管理员刷新一次令牌后会被自己的修复锁在门外。

任务 3.3 同时涉及问题 06 与 33 断裂 2，任务 3.5 同时涉及问题 09 与 33 断裂 3——
这两处必须一次改完，不要拆成两次修改。

本批次触及高频路径（鉴权、问答），完成后请完整执行一遍回归验证。
只修改「涉及文件」章节列出的文件。
每个任务都要配套补充自动化测试（见 README 第七节）。
不输出任何 API Key、Token、密码、哈希原文。
```
