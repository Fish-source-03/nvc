# 31 · DocumentDeleteServiceV2 在 ChromaRetriever 为空时"假成功"

> **严重程度**：🟠 中高
> **所属模块**：agent-qr-compensation（DocumentDeleteServiceV2）
> **设计依据**：《系统详细设计说明书》§10.4 DocumentDeleteServiceV2、§10.1（整体一致性保证链）
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析（未编译、未运行）

---

## 一、问题描述

`DocumentDeleteServiceV2` 的 `chromaRetriever` 依赖是**可选注入**（`@Autowired(required = false)`）。当该依赖为 null 时，代码只打印一条 WARN 日志，**随后照常把删除任务标记为 `DONE`**。

即：**向量一条都没删，任务却记录为"完成"**。这不仅让 `delete_task` 表失真（叠加文档 30），更严重的是它把"依赖缺失"这一部署/配置错误伪装成了成功。

考虑到"文档删除后仍能检索到内容"是用户可直接感知的问题，该静默成功会显著延长故障的暴露时间。

---

## 二、推断依据

### 依据 1：依赖为可选注入

`agent-qr-compensation/src/main/java/org/example/agent_qr/compensation/service/DocumentDeleteServiceV2.java:28-29`

```java
@Autowired(required = false)
private ChromaRetriever chromaRetriever;
```

`required = false` 本身是合理的降级设计（避免 compensation 模块因 rag 模块缺失而启动失败），但**降级的语义必须明确**。

### 依据 2：null 分支仅告警后继续

`DocumentDeleteServiceV2.java:64-74`

```java
try {
    if (chromaRetriever != null) {
        chromaRetriever.deleteByIds(chromaIds);
    } else {
        log.warn("ChromaRetriever 未初始化，跳过物理删除: documentId={}", documentId);
        // ← 没有 return，也没有标记失败
    }

    // 3. 标记完成
    deleteTaskMapper.updateStatus(task.getId(), DeleteTask.STATUS_DONE);   // 照常置 DONE
    log.info("ChromaDB 物理删除完成: documentId={}, chromaIdCount={}", documentId, chromaIds.size());
} catch (Exception e) { ... }
```

日志中还会输出"物理删除完成"，与事实完全相反。

### 依据 3：与同类的空输入分支对比

`DocumentDeleteServiceV2.java:56-60` 对"无可删 ID"的处理是：

```java
if (chromaIds == null || chromaIds.isEmpty()) {
    deleteTaskMapper.updateStatus(task.getId(), DeleteTask.STATUS_DONE);
    log.info("ChromaDB 物理删除无需操作（无向量 ID）: documentId={}", documentId);
    return;
}
```

这一分支置 `DONE` 是**正确的**——"没有要删的东西"确实是完成。但"依赖不可用导致跳过"与"无需删除"是两回事，当前实现把二者混为一谈。

### 依据 4：设计文档要求强一致

§10.1 的整体一致性保证链要求删除操作最终一致；§10.4 的流程描述中，跳过物理删除不属于任何合法终态。

---

## 三、影响范围

1. **残留向量 + 假成功**：向量未删除但任务标记完成，用户删了文档仍能搜到内容，且运维查表显示"删除成功"。
2. **掩盖配置错误**：若因装配问题导致 `ChromaRetriever` 始终为 null（例如模块依赖变更、Bean 名称调整），系统会长期处于"删除不生效"状态而无人察觉。
3. **与文档 29 叠加**：孤儿向量扫描本应兜底，但它恰好扫不到这类残留（见文档 29），因此**两条路径都失效**。
4. **触发条件不确定**：null 究竟是"罕见的降级"还是"常态"，取决于运行时装配情况，静态分析无法判定——这本身就是风险。

---

## 四、修复方向

1. **区分两种语义**：`chromaRetriever == null` 时应走**失败路径**（置 `FAILED` + 入 DLQ + 告警），而不是成功路径。
2. **启动期校验**：若 `ChromaRetriever` 是删除链路的必需依赖，建议在应用启动时做一次存在性检查并给出明确的启动日志（或在配置中声明为必需，去掉 `required = false`）。
3. **修正日志**：任何"跳过"的日志都不应包含"完成"字样。
4. **补充可观测性**：为"因依赖缺失而跳过"的计数增加指标，便于发现长期异常。
5. **依赖文档 30 的修复**：置 `FAILED` 需要 `DeleteTask` 的状态流转可用（当前 `STATUS_FAILED` 无引用）。

---

## 五、核查边界

- 静态分析，未运行服务，**未确认 `ChromaRetriever` 在当前装配下是否真的可能为 null**（这取决于 Spring 上下文，需运行验证）。
- 未评估去掉 `required = false` 后是否会导致 compensation 模块在缺少 rag 依赖时启动失败（需权衡模块解耦）。
