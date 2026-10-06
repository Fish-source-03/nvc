# 15 · BM25Retriever 未升级为磁盘索引与异步构建

> **严重程度**：🟡 中
> **所属模块**：agent-qr-rag（BM25Retriever）
> **设计依据**：《系统详细设计说明书》§8.15.1 BM25Retriever v2 — Lucene 磁盘索引 + 异步构建 `[P2]`
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析（未编译、未运行）

---

## 一、问题描述

设计 §8.15.1 要求把 BM25 检索升级为 v2：使用 **Lucene 磁盘索引**、通过 `@Async("indexBuilderExecutor")` **异步构建**、**分页加载**索引文本，并监听 `EmbeddingCompletedEvent` 做**增量更新**。

实际实现仍是 v1 形态：

1. 使用 `ByteBuffersDirectory`（**堆内存**索引），无磁盘持久化；
2. `@PostConstruct` **同步阻塞**构建，且异常仅记录日志；
3. `findAllIndexable()` **一次性全量加载**，无分页；
4. 无增量更新，无事件监听。

注：`agent-qr-web` 的 `AsyncConfigV2.java:95` 已经定义好了 `indexBuilderExecutor` 线程池，但**没有消费者**——正是为 v2 准备的池子。

---

## 二、推断依据

### 依据 1：使用内存索引而非磁盘索引

`agent-qr-rag/src/main/java/org/example/agent_qr/rag/retriever/BM25Retriever.java:20,47`

```java
import org.apache.lucene.store.ByteBuffersDirectory;
...
private final ByteBuffersDirectory directory = new ByteBuffersDirectory();
```

设计要求的磁盘索引（如 `FSDirectory` 指向 `./data/lucene_index`）未被使用；全仓检索 `FSDirectory` 零命中。

### 依据 2：`@PostConstruct` 同步阻塞构建

`BM25Retriever.java:52-74`

```java
/**
 * 启动时从 MySQL 加载全量索引文本构建 Lucene 内存索引。
 */
@PostConstruct
public void buildIndex() {
    try {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        try (IndexWriter writer = new IndexWriter(directory, config)) {
            writer.deleteAll();
            List<IndexableText> texts = indexableTextProvider.findAllIndexable();   // 全量加载
            for (IndexableText text : texts) { ... }
            writer.commit();
        }
        refreshReader();
        log.info("BM25 索引构建完成，共索引 {} 条切片", ...);
    } catch (Exception e) {
        log.error("BM25 索引构建失败", e);      // 异常被吞，应用继续启动
    }
}
```

- 该方法在 **Spring 启动阶段同步执行**，索引数据量大时会显著延长启动时间；
- 每次启动都要**全量重建**（`writer.deleteAll()` + 全量遍历）；
- 构建失败仅记录日志不中断——若失败，BM25 检索路静默失效，混合检索退化为单路。

### 依据 3：无分页、无增量

全仓检索：`grep "findAllIndexable"` 仅命中定义与上述调用点；无分页参数、无游标。

设计 §8.15.1 要求监听 `EmbeddingCompletedEvent` 做增量更新，实际 `BM25Retriever` 无任何事件监听方法（类中无 `@EventListener`）。

### 依据 4：`indexBuilderExecutor` 已备好但无人使用

`agent-qr-web/.../web/config/AsyncConfigV2.java:95` 定义了 `indexBuilderExecutor` 线程池（设计 §8.15.1 指定给索引构建使用，`:5026` 有记录）。

```bash
grep -rn "indexBuilderExecutor" --include=*.java .
# 仅命中 AsyncConfigV2 中的定义处，无任何 @Async 使用
```

即线程池是"为 v2 预留的接口"，v2 未实现。

### 依据 5：类名未升级

设计文档称 v2 类为 `BM25RetrieverV2`，实际类名仍为 `BM25Retriever`。全仓无 `BM25RetrieverV2`。

---

## 三、影响范围

1. **启动时间随数据量线性增长**：切片数量达到万级时，每次重启都要重建全量内存索引。
2. **内存占用**：索引常驻堆内存，与文档 20（大数据源同步性能）叠加时会加剧内存压力。
3. **索引时效性**：仅启动时构建，运行期新增的切片（如新上传文档、数据源同步新增记录）**在重启前不会被 BM25 检索到**——混合检索的一路长期处于"部分过期"状态。
4. **启动期耦合**：索引构建失败不影响启动，问题被静默掩盖。

---

## 四、修复方向

按设计 §8.15.1 实施 v2：

1. **索引持久化**：改用 `FSDirectory` 指向磁盘目录，启动时若索引已存在则直接加载而非重建。
2. **异步构建**：把 `@PostConstruct` 的构建逻辑移到 `@Async("indexBuilderExecutor")` 方法中，避免阻塞启动；并增加构建状态标志，构建完成前 BM25 路返回空而非阻塞。
3. **分页加载**：`findAllIndexable()` 改为分页/游标形式，避免一次性全量加载。
4. **增量更新**：监听 `EmbeddingCompletedEvent`（或新增切片事件）做 `updateDocument`，替代全量重建。
5. **索引构建失败应可见**：当前 `catch` 仅 `log.error`，建议失败时置降级标志并在健康检查/看板中暴露。

---

## 五、核查边界

- 静态分析，未运行服务、未实测启动耗时与索引规模。
- 未连接数据库确认 `kb_chunk` 的实际切片总量（决定该问题的实际严重程度）。
- 未评估 `IndexableTextProvider` 的 `findAllIndexable()` 具体 SQL 是否已有隐性上限——已确认其为全量查询。
