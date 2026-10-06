# 36 · 生产环境 API 路径双前缀导致请求全部 404

> **严重程度**：🟠 中高
> **所属模块**：agent-qr-web-frontend（api/index.ts、.env.production）
> **设计依据**：前端环境配置约定
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析（未构建、未部署）

---

## 一、问题描述

生产环境构建时，axios 的 `baseURL` 为 `/api`，而所有 API 调用自身又以 `/api/...` 开头。axios 的 URL 合并规则是 `baseURL + '/' + url`，因此实际请求路径会变成 **`/api/api/auth/login`** 形式的双前缀。

开发环境因 `baseURL` 是绝对地址 `http://localhost:9090`（不含路径部分），拼接结果正确，**因此该缺陷只在生产构建中暴露**。

后果：生产部署后所有 API 请求 404，前端功能整体不可用。

---

## 二、推断依据

### 依据 1：生产环境 baseURL 为 `/api`

`agent-qr-web-frontend/.env.production`

```
VITE_API_BASE_URL=/api
```

### 依据 2：开发环境为绝对地址，因此掩盖了问题

`agent-qr-web-frontend/.env.development`

```
VITE_API_BASE_URL=http://localhost:9090
# P2 SSE 流式超时（毫秒，默认 5 分钟）
VITE_SSE_TIMEOUT=300000
# ...
```

开发环境下 `baseURL` 无路径成分，与调用路径拼接后为 `http://localhost:9090/api/auth/login` —— **正确**。

### 依据 3：axios 实例直接使用该值作为 baseURL

`src/api/index.ts:14`

```typescript
baseURL: import.meta.env.VITE_API_BASE_URL || '',
```

### 依据 4：所有 API 调用路径已自带 `/api` 前缀

`src/api/auth.ts:6,9,12,16,20`、`src/api/knowledge.ts:12,18,22` 等全部为：

```typescript
return request.post<any, ApiResult<LoginVO>>('/api/auth/login', data)
return request.get<any, ApiResult<PageResult<DocumentInfo>>>('/api/knowledge/documents', { params })
```

### 依据 5：拼接结果

axios 的 `combineURLs(baseURL, relativeURL)` 语义为"去掉 baseURL 尾部斜杠 + `/` + 去掉 relativeURL 首部斜杠"：

| 环境 | baseURL | 调用路径 | 实际请求 |
|---|---|---|---|
| 开发 | `http://localhost:9090` | `/api/auth/login` | `http://localhost:9090/api/auth/login` ✅ |
| 生产 | `/api` | `/api/auth/login` | **`/api/api/auth/login`** ❌ |

### 依据 6：SSE 与刷新令牌的拼接方式更特殊

`src/api/index.ts:78` 与 `:181` 使用模板字符串：

```typescript
`${import.meta.env.VITE_API_BASE_URL}/api/auth/refresh`
```

生产环境下为 `/api/api/auth/refresh`，与依据 5 同样错误；开发环境下为 `http://localhost:9090/api/auth/refresh`，正确。

即同一处缺陷在**两种拼接方式**中都存在。

### 依据 7：代理配置只对开发生效

`vite.config.ts:25-32` 配置了 `'/api' → http://localhost:9090` 的开发代理。该代理仅在 dev server 生效，生产环境由 Nginx 或同源部署提供服务——若反向代理未配置 `/api` 转发规则，双前缀路径即便修正后也需要代理配合。

---

## 三、影响范围

1. **生产环境前端整体不可用**：所有接口 404，登录、问答、管理功能全部失效。
2. **难以在开发阶段发现**：本地 `npm run dev` 完全正常，只有生产构建 + 部署才暴露——属"只在发布时爆炸"的缺陷，与文档 03（Dockerfile）同类。
3. **与登录流程叠加**：`/api/api/auth/refresh` 失败会导致 Token 刷新链路中断（叠加文档 08 的登出问题，认证相关的边缘路径问题较多）。

---

## 四、修复方向

1. **二选一，保持一致性**：
   - **方案 A**：把 `.env.production` 改为 `VITE_API_BASE_URL=''`（空字符串），让调用路径自带的 `/api` 前缀生效；
   - **方案 B**：把 `.env.production` 改为 `VITE_API_BASE_URL='/'`（或部署对应的网关路径），同时把所有 `src/api/*.ts` 中的 `/api/...` 改为去掉前缀的相对路径。
   建议 **方案 A**，改动面最小（一个文件一行）。
2. **统一 SSE / refresh 的拼接方式**：`api/index.ts:78,181` 的模板字符串应改用统一的 `baseURL` 拼接逻辑，避免散落的字符串拼接。
3. **部署验证**：确认生产环境的反向代理（Nginx / 网关）对 `/api` 前缀的转发规则，特别是 SSE 端点的缓冲与超时配置。
4. **建议补一条生产构建的冒烟测试**：该缺陷可通过"构建后用 preview 模式访问一次登录接口"发现——正是复盘经验 3"性能/功能验证必须纳入 DoD"的同类问题。

---

## 五、核查边界

- 静态分析，未执行 `npm run build`、未部署、未实测请求路径。
- axios 的 URL 合并语义基于其 `combineURLs` 的既有实现，未在运行期验证。
- 未检查部署环境（Nginx 配置、网关路由）——仓库中未见相关配置。
