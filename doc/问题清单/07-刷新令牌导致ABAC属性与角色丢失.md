# 07 · 刷新令牌导致 ABAC 属性与角色丢失

> **严重程度**：🔴 高
> **所属模块**：agent-qr-auth（RefreshTokenService）
> **设计依据**：《系统详细设计说明书》§8.15.5 RefreshTokenService — 双 Token 机制、§3.2.10 JwtUtil 的 6 项 ABAC claim
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析（未运行服务、未实际调用刷新接口）

---

## 一、问题描述

`RefreshTokenService.refresh()` 在轮换令牌后需要重建用户信息以签发新 Token。当前实现用一个**空的 `SysUser` 对象**，只回填 `id` 与 `username`，其余字段全部为 null；更严重的是角色被一个**两个分支返回相同值**的三元表达式硬编码为 `"user"`。

后果分两类：

- **降权**：管理员刷新令牌后，新 Access Token 的角色 claim 变为 `user`，所有管理员能力立即失效。
- **属性清零**：`department`、`clearanceLevel`、`allowedDomains`、`title` 全部丢失，`AbacEvaluator` 的所有域判定、密级判定、职级判定都会命中拒绝分支。

用户只要经历一次令牌刷新（Access Token 默认 30 分钟过期，属于高频路径），权限即降级。

---

## 二、推断依据

### 依据 1：角色被硬编码为 "user"

`agent-qr-auth/src/main/java/org/example/agent_qr/auth/service/RefreshTokenService.java:87-91`

```java
// 4. 签发新令牌对（需要用户信息）
SysUser user = new SysUser();
user.setId(stored.getUserId());
// 从旧 JWT 解析用户名
user.setUsername(jwtUtil.getUsernameFromToken(refreshToken));
user.setRole(jwtUtil.getUsernameFromToken(refreshToken) != null ? "user" : "user");
```

三元表达式的两个分支字面量**完全相同**（均为字符串 `"user"`），无论条件真假结果一致——这是一个明显的书写错误，且不会有任何编译警告。

### 依据 2：其余字段从未赋值

紧接上述代码的 `SysUser` 对象没有任何 `setDepartment` / `setClearanceLevel` / `setAllowedDomains` / `setTitle` 调用，这些字段保持 null。

### 依据 3：null 属性会一路传导到鉴权判定

签发链路：`RefreshTokenService.java:94` → `issueTokens(user)` → `JwtUtil.generateAccessToken(...)`

`agent-qr-auth/.../util/JwtUtil.java:103-120` 的 ABAC claim 取自这些字段；`UserPrincipal.java:49-66` 的 `fromClaims(...)` 再还原为 principal。字段为 null 时 claim 为空，principal 对应属性即为 null。

### 依据 4：AbacEvaluator 对 null 属性的处理使其必然被拒

`agent-qr-auth/.../evaluator/AbacEvaluator.java` 中的判定（如 `:85`、`:94`、`:123`、`:153`）依赖 `clearanceLevel` / `title` / `allowedDomains` 的比较；`UserPrincipal.hasClearance`（`UserPrincipal.java:100`）在属性为 null 时返回 false。

因此刷新后的令牌在以下能力上全部失效：域访问（`canQueryDomain`）、文档上传/删除（`canUploadToDomain`/`canDeleteDocument`）、用户管理（`canModifyUser`/`canCreateUser`）、看板查看（`canViewDashboard`）、数据源管理（`canManageDatasource`）。

### 依据 5：与设计文档的对照

设计 §8.15.5 的 `issueTokens` 要求"签发双 Token + 落库 + 返回 expiresIn"；§3.2.10 要求 Access Token 携带 6 项 ABAC claim。当前实现在**签发格式**上完全符合，但在**取值来源**上是空的——属"格式对、内容错"。

### 依据 6：无测试覆盖

`agent-qr-auth/src/test` 不存在，全仓库 Java 测试文件数为 0（见文档 33）。该缺陷无法被现有任何测试发现。

---

## 三、影响范围

1. **管理员体验断裂**：登录后约 30 分钟（Access Token 有效期）内权限正常，刷新一次后突然失去全部管理功能，且无任何错误提示——用户会表现为"用着用着就没权限了"。
2. **ABAC 体系名存实亡**：ABAC 是本项目 P2 的核心设计目标之一，刷新路径使其在长时间会话中完全失效。
3. **降权方向安全，升权方向需排查**：当前 bug 是"降权"，不构成越权；但需确认是否存在反方向路径（例如刷新令牌可当 Access Token 使用，见文档 08 的相关分析）。
4. **推翻复盘报告自评**：与文档 06 共同说明"权限管控链路已跑通"的结论不成立。

---

## 四、修复方向

1. **从数据库重新加载完整用户**：`refresh()` 中应根据 `stored.getUserId()` 调用 `sysUserMapper.selectById(...)` 获取完整 `SysUser`，而非构造空对象。项目已有 `SysUserMapper`，改动量小。
2. **删除错误的三元表达式**：角色应取自数据库记录，不应硬编码。
3. **同时处理用户被禁用/删除的场景**：重新加载后应校验 `status`，避免已禁用用户通过刷新继续获得令牌。
4. **补充测试**：至少覆盖"管理员刷新后仍具备 ADMIN 角色与完整 ABAC 属性""已禁用用户刷新应被拒绝"两条用例。

---

## 五、核查边界

- 静态分析，未运行服务、未实际调用 `/api/auth/refresh` 验证响应。
- 未检查 `RefreshTokenService` 是否有其他调用方传入完整用户对象（已确认 `issueTokens(SysUser)` 仅被 `refresh()` 与登录流程调用）。
- JJWT 0.11.5 对数值 claim 的类型转换行为未实测（可能影响 `userId` 的反序列化，属独立事项）。
