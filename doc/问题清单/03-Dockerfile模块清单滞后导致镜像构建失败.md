# 03 · Dockerfile 模块清单滞后导致镜像构建失败

> **严重程度**：🔴 高
> **所属模块**：项目根级（`Dockerfile`）
> **设计依据**：《系统详细设计说明书》三阶段类映射表（12 个 Maven 模块）
> **核查日期**：2026-10-06
> **核查方式**：静态文件比对（未执行 `docker build`）

---

## 一、问题描述

根 `pom.xml` 声明了 **12 个** Maven 模块，但 `Dockerfile` 只 COPY 了 **7 个**模块的 `pom.xml` 与 `src`——恰好是 P1 阶段的模块集合。P2 新增的 5 个模块（compensation / datasource / data-quality / etl / catalog）在构建上下文中**完全不存在**。

由于 Maven 在解析根 pom 的 `<modules>` 时要求每个子模块目录必须存在，构建会在反应堆初始化阶段直接失败。

---

## 二、推断依据

### 依据 1：根 pom 声明 12 个模块

`pom.xml:24-37`（12 个 `<module>` 条目）

```
agent-qr-common / auth / user / knowledge / rag / statistics / web
agent-qr-compensation / datasource / data-quality / etl / catalog
```

与磁盘实际目录一一对应（`ls -d agent-qr-*/` 同样返回 12 个后端模块）。

### 依据 2：Dockerfile 只 COPY 7 个模块

`Dockerfile:16-31`

```dockerfile
COPY agent-qr-common/pom.xml agent-qr-common/
COPY agent-qr-auth/pom.xml    agent-qr-auth/
COPY agent-qr-user/pom.xml    agent-qr-user/
COPY agent-qr-knowledge/pom.xml agent-qr-knowledge/
COPY agent-qr-rag/pom.xml     agent-qr-rag/
COPY agent-qr-statistics/pom.xml agent-qr-statistics/
COPY agent-qr-web/pom.xml     agent-qr-web/
# ... 同样只 COPY 上述 7 个模块的 src
```

**缺失的 5 个模块**：`agent-qr-compensation`、`agent-qr-datasource`、`agent-qr-data-quality`、`agent-qr-etl`、`agent-qr-catalog`。

### 依据 3：构建命令会解析全部模块

`Dockerfile:36`

```dockerfile
RUN mvn clean package -pl agent-qr-web -am -Dmaven.test.skip=true
```

`-pl agent-qr-web -am`（also-make）会让 Maven 先加载根 pom 的全部 `<modules>`。缺少任一子模块目录时，Maven 报错：

```
[ERROR] Child module /build/agent-qr-compensation of /build/pom.xml does not exist
```

即**镜像构建必定失败**，而非"构建出的镜像缺少某些功能"。

### 依据 4：web 模块本身依赖缺失的 5 个模块

`agent-qr-web/pom.xml:67-92` 声明了对 P2 五个模块的依赖。因此即使把 `-pl agent-qr-web -am` 改为只构建 web，也会因依赖解析失败而报错——两条路径都不通。

### 依据 5：与复盘报告的时间线吻合

复盘报告第 181 行指出"从 P1 的 7 个模块到 P2 的 12 个模块"。Dockerfile 的模块清单停留在 P1 时代，说明**部署脚本未随模块扩张同步更新**，该缺陷从 P2 阶段起一直存在。

---

## 三、影响范围

1. **容器化交付链路完全阻塞**：`docker-compose up` 无法产出可用镜像。
2. **影响范围被低估**：由于本地开发（IDE 直接运行）不受影响，编译通过、测试通过都不暴露此问题——属于"只在部署时爆炸"的缺陷类型。
3. **CI/CD 若基于此 Dockerfile，则从未成功构建过 P2 之后的产物**。

---

## 四、修复方向

1. 补齐 `Dockerfile:16-31` 中 5 个缺失模块的 `pom.xml` 与 `src` COPY 指令。
2. **建议改为通配符形式**（如 `COPY agent-qr-*/pom.xml ./` + 逐目录重命名，或使用 `COPY . .` 配合完善的 `.dockerignore`），避免每次新增模块都要改 Dockerfile——当前写法是"每次加模块都会忘记改"的结构性诱因。
3. 若有意分级构建，应在设计文档 §9 中记录各阶段镜像的模块边界，而非让 Dockerfile 与 pom 静默漂移。

---

## 五、核查边界

- 未执行 `docker build`（会写文件、拉取镜像），结论基于 pom 模块清单与 Dockerfile COPY 清单的静态比对，以及 Maven 反应堆的既有语义。
- 未检查 CI 配置（仓库中未见 `.github/` 等 CI 目录）。
