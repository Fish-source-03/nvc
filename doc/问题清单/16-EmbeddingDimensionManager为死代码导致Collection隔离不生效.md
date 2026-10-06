# 16 · EmbeddingDimensionManager 为死代码导致 Collection 隔离不生效

> **严重程度**：🟡 中
> **所属模块**：agent-qr-rag（EmbeddingDimensionManager、BatchEmbeddingService、ChromaConfig）
> **设计依据**：《系统详细设计说明书》§8.15.3 EmbeddingDimensionManager — Collection 隔离 + 维度检测 `[P3]`
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析（未编译、未运行）

---

## 一、问题描述

设计 §8.15.3 要求 `EmbeddingDimensionManager` 承担两项职责：**按 Embedding 模型隔离 ChromaDB Collection**（避免不同模型的向量维度冲突）与**维度检测**。

实际该类的完整调用链是断的：

1. `BatchEmbeddingService` 注入并调用它生成 Collection 名，但**那个方法本身没有任何调用方**；
2. 真正决定 Collection 名的仍是固定配置（`enterprise_knowledge`）；
3. 类内的 `ensureCollection()` **恒返回 true，从不真正查询 ChromaDB**；
4. 该类是 git 未跟踪的新增文件，说明是近期添加但尚未接入。

结果：切换 Embedding 模型不会自动隔离 Collection，P3 声称的该能力在运行时不存在。

---

## 二、推断依据

### 依据 1：完整引用链只有 3 处，且末端断裂

```bash
grep -rn "EmbeddingDimensionManager\|getEffectiveCollectionName" --include=*.java . | grep -v target
```

命中结果：

| 位置 | 性质 |
|---|---|
| `EmbeddingDimensionManager.java:42` | 类定义 |
| `BatchEmbeddingService.java:43` | `@Autowired` 字段注入 |
| `BatchEmbeddingService.java:186` | `getEffectiveCollectionName()` 方法定义（调用 dimensionManager） |

**`getEffectiveCollectionName()` 自身零调用方**——注入的依赖只被一个没人调用的方法使用。整条链在此断开。

### 依据 2：实际 Collection 名来自固定配置

`agent-qr-rag/.../config/ChromaConfig.java:38-39` 在装配时使用固定名称；对应 `agent-qr-web/src/main/resources/application-p1.yml:20` 配置的 `enterprise_knowledge`。

因此无论使用哪个 Embedding 模型，写入与检索都指向同一个 Collection。

### 依据 3：`ensureCollection()` 恒返回 true，不做检测

`agent-qr-rag/.../embedding/EmbeddingDimensionManager.java:96-105`

```java
// 注释自述：EmbeddingDimensionManager 仅负责命名和缓存管理
public boolean ensureCollection(...) {
    ...
    return true;      // 从不查询 chromaClient.collectionExists()
}
```

设计 §8.15.3 要求的 `chromaClient.collectionExists()` 维度检测未实现。类内 `:80-87` 的检测逻辑仅"打日志 + 置缓存 true"。

### 依据 4：相关配置项的处理需分两类

`application-p3.yml:52-53` 存在两个键，全仓库均**无 Java 读取点**，但性质不同：

```yaml
    collection-prefix: kb                  # ChromaDB Collection 前缀（暂未使用）
    auto-dimension-check: true             # 启动时自动检测向量维度一致性
```

| 配置键 | 注释 | 性质 |
|---|---|---|
| `collection-prefix` | `（暂未使用）` | **诚实占位符** —— 注释已如实标注未使用，属预留配置，**不是缺陷** |
| `auto-dimension-check` | `启动时自动检测向量维度一致性` | **误导性** —— 注释声称会做检测，但无读取点且 `ensureCollection()` 恒返回 true |

因此配置层面的问题集中在 `auto-dimension-check`：它让人以为维度检测已开启，实际没有。（`collection-prefix` 按已确认的 38 号决策「能接线就接线」处理，接通即可。）

### 依据 5：文件未被 git 跟踪

```bash
git status
# ?? agent-qr-rag/src/main/java/org/example/agent_qr/rag/embedding/EmbeddingDimensionManager.java
```

属新增待接入状态。

---

## 三、影响范围

1. **模型切换会导致维度冲突**：若运维按设计文档切换 Embedding 模型（如从 Ollama 切到 DeepSeek，维度不同），新向量会写入同一 Collection，而 ChromaDB 对 Collection 内向量维度是一致性约束——写入将失败或检索结果错乱。
2. **P3 能力虚标**：设计文档 §8.15.3、P3 任务书、yml 配置三处都呈现该能力已具备，实际未生效。
3. **排查困难**：`ensureCollection` 恒返回 true，问题发生时不会有任何前置告警。
4. **与文档 17 关联**：Embedding Provider 本身无降级链，再叠加 Collection 不隔离，模型侧的容错能力整体薄弱。

---

## 四、修复方向

1. **接通调用链**：让实际写入/检索路径使用 `getEffectiveCollectionName()` 的结果（涉及 `ChunkEmbeddingListener`、`DataSyncEtlListener`、`ChromaRetriever` 的 Collection 名来源统一）。
2. **实现真实的维度检测**：`ensureCollection()` 应调用 `chromaClient` 查询 Collection 是否存在及其维度，不匹配时按策略处理（新建带维度后缀的 Collection / 报错 / 触发重建）。
3. **让配置项生效**：`collection-prefix` 与 `auto-dimension-check` 应被 `@Value` 读取；若不打算使用，应从 yml 中删除，避免误导。
4. **补充迁移方案**：已有的 `enterprise_knowledge` Collection 需要一次性迁移或兼容读取策略，否则切换命名规则会导致历史数据检索不到。

---

## 五、核查边界

### 已补充确认的事实（2026-10-06 实测）

- **当前 Embedding 模型维度为 2560**（`qwen3-embedding:4b`，经 `GET /api/tags` 的 `embedding_length` 与实测返回向量长度双重确认，详见问题 17）
- 这为"维度冲突"提供了具体依据：一旦切换到此维度之外的模型，写入同一 Collection 会出现维度不匹配

### 其余核查边界

- 静态分析，未运行服务、未与 ChromaDB 交互。
- **未确认 ChromaDB 侧当前实际存在哪些 Collection、其维度分别是多少**（需连 ChromaDB 查询，本次未做）。
- 未评估历史数据的迁移成本（取决于当前向量库规模）。
- 该文件为 git 未跟踪状态，可能正处于开发中——本结论反映的是**当前工作区快照**的状态。
