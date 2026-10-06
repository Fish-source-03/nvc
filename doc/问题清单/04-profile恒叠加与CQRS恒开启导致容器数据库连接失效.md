# 04 · profile 恒叠加与 CQRS 恒开启导致容器数据库连接失效

> **严重程度**：🔴 高
> **所属模块**：agent-qr-web（application.yml / application-p3.yml）、docker-compose.yml
> **设计依据**：《系统详细设计说明书》§9.1（"各阶段通过 `spring.profiles.active` 切换"）、§8.13（CQRS）
> **核查日期**：2026-10-06
> **核查方式**：静态配置分析（未启动服务、未连接数据库）

---

## 一、问题描述

设计文档 §9.1 描述三阶段配置通过 `spring.profiles.active` **切换**。实际实现是三个 profile **同时叠加激活**（`p1, p2, p3`），后者覆盖前者。

由此产生的连锁后果：P3 配置永久生效 → `agent-qr.cqrs.enabled=true` 永久成立 → CQRS 数据源永久装配 → 数据源绑定走 `application-p3.yml` 中硬编码的 `localhost` 地址，而 `docker-compose.yml` 注入的 `SPRING_DATASOURCE_URL`（指向 `mysql:3306`）**根本不会被读取**。

---

## 二、推断依据

### 依据 1：profile 同时激活，非"切换"

`agent-qr-web/src/main/resources/application.yml:5`

```yaml
spring:
  profiles:
    active: p1, p2, p3
```

`docker-compose.yml:74`

```yaml
SPRING_PROFILES_ACTIVE: p1, p2, p3
```

两处一致，均为三阶段叠加。

### 依据 2：P3 的 CQRS 开关因此恒为 true

`agent-qr-web/src/main/resources/application-p3.yml:37-38`

```yaml
agent-qr:
  cqrs:
    enabled: true
```

### 依据 3：CQRS 装配条件正是该开关

`agent-qr-web/.../web/config/CqrsDataSourceConfig.java:34-46`

```java
@ConditionalOnProperty(name = "agent-qr.cqrs.enabled", havingValue = "true")
```

由于依据 1、2，此条件在**任何**运行方式下都成立。

### 依据 4：CQRS 的库地址是硬编码 localhost

`agent-qr-web/src/main/resources/application-p3.yml:10,22`（键名）

```yaml
spring:
  datasource:
    write:
      jdbc-url: jdbc:mysql://localhost:3308/...   # 值已脱敏（仅保留主机端口）
    read:
      jdbc-url: jdbc:mysql://localhost:3309/...
```

### 依据 5：compose 注入的 URL 被绕过

`docker-compose.yml:75`

```yaml
SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/${MYSQL_DATABASE:-agent_qr}?...
```

该环境变量映射到标准的 `spring.datasource.url`。但在依据 2、3 的条件下，实际绑定的是 `spring.datasource.write.jdbc-url` / `spring.datasource.read.jdbc-url` 两个前缀，标准键不再参与装配。容器内 `localhost` 没有 MySQL 服务（compose 中的服务名是 `mysql`），连接必然失败。

### 依据 6：deployment 脚本无 profile 切换能力

`start.sh:57-79`、`start.bat:51-68` 均无 profile 参数，只执行 `docker compose up`，profile 完全由 yml/compose 固定。

---

## 三、影响范围

1. **容器化部署无法连接数据库**：与文档 03 叠加，容器化路径存在双重硬阻塞（构建失败 + 连库失败）。
2. **本地开发失去 profile 隔离**：三阶段配置同时生效意味着无法单独验证 P1 行为，P3 的开关（CQRS、语义路由等）在开发环境也强制打开。
3. **运维误导**：修改 `SPRING_DATASOURCE_URL` 无任何效果，但不会有任何报错——属"改了没反应"型陷阱。
4. **与设计文档 §9.1 明确矛盾**：文档描述的是切换机制，实现是叠加机制，两者对不上。

---

## 四、修复方向

1. **明确 profile 策略**：若确为"P3 覆盖 P1/P2"的叠加设计，应在设计文档 §9.1 中改写说明，并删除已失效的 `application.yml` 中的数据源键；若本意是切换，则应改为单一 profile（如通过 `--spring.profiles.active=p3` 传入）。
2. **CQRS 的数据源地址应外部化**：改为 `${SPRING_DATASOURCE_WRITE_URL:jdbc:mysql://localhost:3308/...}` 形式，让容器环境可覆盖。
3. **补充启动自检**：CQRS 装配时应校验读/写库连通性并在失败时给出明确日志（当前 `readReplicaFallbackToPrimary` 只打印日志，见文档 05）。

---

## 五、核查边界

- 未启动服务、未执行 `docker compose up`，未实际观测连接失败。
- 配置值中仅保留主机与端口，凭据类信息未读取、未记录。
- 未检查是否存在外部配置中心（Nacos 等）覆盖上述键——仓库中未见相关依赖。
