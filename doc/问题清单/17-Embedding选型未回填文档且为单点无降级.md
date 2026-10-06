# 17 · Embedding 选型未回填文档且为单点无降级

> **严重程度**：🟡 中
> **所属模块**：agent-qr-rag（provider 包）、agent-qr-web（配置）
> **设计依据**：《系统详细设计说明书》§6.2.2 ProviderFactory、§6.2.4 DeepSeekEmbeddingProvider、三阶段类映射表 P1 列
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析 + 配置核对（未编译、未运行）

---

## 更正说明（重要）

本文档的**初版判断有误**，原判断为"`DeepSeekEmbeddingProvider` 缺失，属实现遗漏"。经项目负责人指出并复核配置后确认：

**Embedding 的选型是「本地 Ollama 服务 + qwen3-embedding:4b 模型」，这是有意为之的架构决策，且在配置与代码中均有明确标注**，并非"未实现"。

- `application-p1.yml:15` 注释明写：`★ 使用本地 Ollama qwen3-embedding:4b（P2 配置: ollama.embedding.*）`
- `application-p2.yml:15-17` 配置了 `ollama.embedding.base-url` 与 `model: qwen3-embedding:4b`
- `ProviderDecisionEngine.decideEmbeddingProvider()` 的 javadoc **主动说明**「仅 Ollama Embedding 可用，直接返回固定值」

另：**DeepSeek 不提供 Embedding API**，因此本文档初版提出的"实现 `DeepSeekEmbeddingProvider`"是不可行的方向。

下表列出**经复核后仍然成立**的问题。

---

## 一、问题描述

选型本身没有问题，衍生的两个事项需要处理：

1. **设计文档未回填**：设计 §6.2.2 声明 `embedding.provider` 默认值为 `deepseek`、§6.2.4 用整节描述 `DeepSeekEmbeddingProvider`、三阶段映射表 P1 列也列出该类——而实际选型是本地 Ollama + Qwen。文档与实现不符，按文档找类会找不到。
2. **Embedding 是单点，无降级链路**：LLM 侧有 `LLMCircuitBreaker` + 双 Provider（deepseek ↔ ollama）互备；Embedding 侧**只有一个来源**，本地 Ollama 不可用时，向量化与检索链路整体不可用。这是架构取舍，但需要**明确记录该风险**并决定是否接受。

另有一处配置冗余（详见问题 38）：`embedding.provider`（p1）与 `agent-qr.provider.preferred-embedding`（p3）表达同一件事，后者不参与决策。

---

## 二、推断依据

### 依据 1：选型在配置中有明确标注

`agent-qr-web/src/main/resources/application-p1.yml:14-15`

```yaml
embedding:
  provider: ollama                    # ★ 使用本地 Ollama qwen3-embedding:4b（P2 配置: ollama.embedding.*）
```

`application-p2.yml:15-17`

```yaml
  embedding:
    base-url: http://localhost:11434
    model: qwen3-embedding:4b
```

注释中的 `★` 标记说明这是经过考虑的决策点。

### 依据 2：配置项确实被代码读取

```bash
grep -rn "ollama.embedding" --include=*.java .
```

命中 `OllamaEmbeddingProvider.java:27,30`：

```java
@Value("${ollama.embedding.base-url:http://localhost:11434}")
@Value("${ollama.embedding.model:nomic-embed-text}")
```

即配置**是生效的**，模型确实为 `qwen3-embedding:4b`（yml 覆盖了代码默认值）。这与"死配置"有本质区别。

### 依据 3：硬编码返回是**有意设计**，且代码已注明

`agent-qr-rag/.../provider/ProviderDecisionEngine.java:57-65`

```java
/**
 * 决策当前应使用的 Embedding Provider。
 * <p>仅 Ollama Embedding 可用，直接返回固定值。</p>
 *
 * @return 固定返回 "ollama"
 */
public String decideEmbeddingProvider() {
    return "ollama";
}
```

javadoc 主动说明了原因。**这不是隐藏缺陷**，只是与 LLM 侧的 `decideProvider(preferredLLM, "LLM")`（`:53-55`）不对称。

### 依据 4：Embedding 无任何降级机制

- `ProviderFactory.getEmbeddingProvider()`（`:80-86`）的 switch 只有 `case "ollama"`，default 也回退 Ollama
- Embedding 侧**没有**类似 `LLMCircuitBreaker` 的熔断组件
- 失败路径为 `OllamaEmbeddingProvider` 内 `throw new RuntimeException("Ollama Embedding 调用失败: ...")`（`:60-63`），异常直接向上抛

对比 LLM 侧：`LLMCircuitBreaker.java:66-137` 实现了 CLOSED → OPEN → HALF_OPEN 状态机，熔断时切换备用 Provider。

### 依据 5：设计文档与实现不符

- §6.2.2 声明 `embedding.provider` 默认 `deepseek`，`ProviderFactory.java:29` 实际为 `ollama`
- §6.2.4 整节描述 `DeepSeekEmbeddingProvider`（基于 `OpenAiEmbeddingModel`），该类**全仓不存在**
- 三阶段映射表 P1 列列出 `DeepSeekEmbeddingProvider`

---

## 三、影响范围

1. **文档误导**（确定发生）：后续维护者按 §6.2.4 找 `DeepSeekEmbeddingProvider` 会找不到；按映射表核对进度会误判"P1 已完成"。
2. **单点故障风险**（取决于是否要求高可用）：
   - Ollama 服务不可用 → 新文档无法向量化（`ChunkEmbeddingListener` 失败）→ 检索只能用历史数据
   - 与 LLM 侧的对比更明显：LLM 挂了会切到 ollama，而 ollama 挂了 Embedding 没有备选
   - **注意**：这是"本地模型"路线的固有取舍——若要高可用，需要引入第二个 Embedding 来源（本地其他模型服务 / 云端 Embedding API），属架构决策而非缺陷修复

---

## 四、修复方向

### 方向 1：回填设计文档（**建议执行，成本低**）

- §6.2.2：把 `embedding.provider` 的默认值从 `deepseek` 改为 `ollama`，并说明使用本地 Qwen 模型
- §6.2.4：改写为实际的选型说明（本地 Ollama + `qwen3-embedding:4b`），说明为何不走云端 Embedding API
- 三阶段映射表：P1 列的 `DeepSeekEmbeddingProvider` 替换为 `OllamaEmbeddingProvider`
- §6.0 包结构：核对 `provider.ollama` 包的实际内容

### 方向 2：单点风险的处置（**已决策：整批失败 + 失败可见**）

| 选项 | 说明 | 结论 |
|---|---|---|
| A. 接受并记录 | 仅在文档中记录"单点"为已知取舍 | 未采用——仅记录不足以避免静默失败 |
| **B. 明确失败语义 + 失败可见** | 失败时**整批失败**，并让失败可被观测 | ✅ **已采用** |
| C. 引入第二来源 | 接入第二个 Embedding 服务实现互备 | 未采用（成本高，本地模型路线的固有取舍） |

**已确认的决策：整批失败 + 失败可见**（不是引入熔断器）。

> ⚠️ **注意：这是对设计的变更，不是缺陷修复。**
> 当前"批量失败 → 降级逐条重试"是**按设计实现**的——设计 §17.8 d 步（说明书 6850-6852 行）明确写着"失败：降级为逐条 embed() 重试"。因此 `retrySingle` 不是 bug。
> 本决策是把该语义**由"降级逐条重试"改为"整批失败"**，需同步回填设计文档。

具体含义：

- `BatchEmbeddingService.executeBatch`（`:132-158`）的"批量失败 → 逐条降级重试"路径**按变更移除**
  - 变更理由：`OllamaEmbeddingProvider.embedBatch`（`:67-73`）当前是逐条循环调用 `embed`，所以"批量失败后逐条重试"等于把**同样的失败调用再做 N 次**——在服务整体不可用时既浪费又会刷出 N 条重复错误日志
- 失败时整批 future 以异常完成，由下游（文档状态回写）消费该信号
- 即使整批失败，也必须有明确日志/告警，不得静默
- **`vectors.size() != batch.size()` 分支的去留**：与批次 05 任务 5.2.5 的实测结论联动（若改用真批量端点则保留，否则可移除）——**不要单独决定**，详见批次 07 任务 7.2.5a

### 方向 3：消除配置冗余（归属问题 38）

- `embedding.provider`（`application-p1.yml:15`，被 `ProviderFactory` 读取）
- `agent-qr.provider.preferred-embedding`（`application-p3.yml:49`，有 getter `getPreferredEmbedding()` 但不参与 `decideEmbeddingProvider()`）

两者语义重叠。建议二选一保留，或明确二者的分工（如前者为"实现选择"、后者为"偏好记录"）。

---

## 五、核查边界

### 已补充确认的事实（2026-10-06 实测）

> 下列数据由实测本机 Ollama 服务获得，不再是"未确认"状态：

| 项 | 实测结果 | 验证方式 |
|---|---|---|
| Ollama 版本 | **0.35.0** | `ollama --version` |
| 模型 `qwen3-embedding:4b` | ✅ 存在（4.0B 参数 / Q4_K_M 量化 / context 40960） | `GET /api/tags` |
| **向量维度** | **2560** | `/api/tags` 的 `embedding_length: 2560`，与实际返回向量长度一致 |
| 旧端点 `/api/embeddings` | ✅ **仍然可用**，返回 `{"embedding":[...]}` | 直接 POST 实测 |
| 新批量端点 `/api/embed` | ✅ **可用**，接受 `input` 数组，返回 `{"embeddings":[[...],[...]]}` | 直接 POST 实测 |

**注**：首次请求返回 `HTTP 000` 是**模型冷加载超过超时阈值**导致，非端点不存在——验证时需给足超时（建议 ≥60s）或先预热模型。

**维度 2560 的意义**：设计 §6.2.4 假设的 DeepSeek Embedding 维度未知且不可用；而实际使用的模型维度为 2560。一旦将来切换 Embedding 模型且维度不同，**Collection 不隔离会直接导致写入失败**——这为问题 16 的严重性提供了具体依据。

### 其余核查边界

- 静态分析为主，未实际跑通完整的文档向量化链路。
- 未实测 Embedding 调用的延迟与吞吐（这是任务 5.2.5 判断"是否改用批量端点"所需的数据）。

### 更正记录

本文档初版（文件名 `17-DeepSeekEmbeddingProvider缺失导致Embedding无降级链路.md`）把"选型变更"误判为"实现遗漏"，并提出了不可行的修复方向（实现 DeepSeek Embedding Provider）。经项目负责人指出后已更正，初版文件已删除。
