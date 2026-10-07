package org.example.agent_qr.rag.retriever;

import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.example.agent_qr.common.rag.IndexableText;
import org.example.agent_qr.common.rag.IndexableTextProvider;
import org.example.agent_qr.rag.entity.RetrievedDocument;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * BM25 关键词检索器 — 基于 Lucene <b>磁盘</b>索引（v2 形态，批次 07 · 任务 7.3）。
 * <p>
 * 使用 SmartChineseAnalyzer 进行中文智能分词，索引持久化到磁盘，
 * 支持 BM25 评分检索和增量更新（幂等的 {@code updateDocument}）。
 * </p>
 *
 * <h3>批次 07 · 任务 7.3 的改造点（问题 15）</h3>
 * <ol>
 *   <li><b>磁盘索引</b>：{@code ByteBuffersDirectory}（堆内存，每次启动全量重建）→
 *       {@link FSDirectory}（{@code agent-qr.bm25.index-dir}，默认 {@code ./data/lucene_index}）；
 *       索引已存在时<b>直接加载</b>，不重建；</li>
 *   <li><b>异步构建</b>：{@code @PostConstruct}（同步阻塞启动）→
 *       {@code @Async("indexBuilderExecutor")} + {@code @EventListener(ApplicationReadyEvent)}，
 *       构建期间 BM25 路返回空而不阻塞；</li>
 *   <li><b>分页加载</b>：{@code findAllIndexable()}（一次性全量）→
 *       {@link IndexableTextProvider#findIndexablePage} 按键集逐页读取；</li>
 *   <li><b>失败可见</b>：构建失败置 {@link BuildState#FAILED} 并保留原因，
 *       {@link #isDegraded()} 可供健康检查/日志核对（失败时混合检索退化为单路语义检索）。</li>
 * </ol>
 * <p>
 * 增量索引入口（{@link #addBatchToIndex}）与"发布方尽力 + Listener 校验补写"双保险
 * 由任务 7.0e 建立，本类只保证磁盘索引下其行为不变（任务 7.3.4 回归验证）。
 * </p>
 * <p>
 * ⚠️ 类名保留 {@code BM25Retriever}（设计 §8.15.1 称 v2 为 {@code BM25RetrieverV2}）：
 * 改名会波及 20+ 调用方，风险大于收益，文档对齐由批次 11 统一处理（任务 7.3.6 二选一）。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class BM25Retriever {

    @Autowired
    private IndexableTextProvider indexableTextProvider;

    /**
     * 磁盘索引目录（批次 07 · 任务 7.3.1）。
     * <p>设计 §8.15.1 指定 {@code ./data/lucene_index}。</p>
     */
    @Value("${agent-qr.bm25.index-dir:./data/lucene_index}")
    private String indexDir = "./data/lucene_index";

    /** 分页加载的页大小（批次 07 · 任务 7.3.3） */
    @Value("${agent-qr.bm25.page-size:1000}")
    private int pageSize = 1000;

    private final Analyzer analyzer = new SmartChineseAnalyzer();

    /** 磁盘索引目录（惰性打开；{@code indexDir} 需在 @Value 注入后读取） */
    private volatile FSDirectory directory;

    private volatile DirectoryReader reader;
    private volatile IndexSearcher searcher;

    /** 构建状态（失败可见，任务 7.3.5） */
    private volatile BuildState buildState = BuildState.PENDING;

    /** 最近一次构建失败原因（{@link BuildState#FAILED} 时非空） */
    private volatile String lastBuildError;

    /**
     * 索引构建状态。
     */
    public enum BuildState {
        /** 尚未开始构建 */
        PENDING,
        /** 构建中（或正在加载磁盘索引） */
        BUILDING,
        /** 已就绪，可检索 */
        READY,
        /** 构建失败：BM25 路不可用，混合检索退化为单路 */
        FAILED
    }

    /**
     * 启动后异步构建（或加载）磁盘索引。
     * <p>
     * 批次 07 · 任务 7.3.2：原为 {@code @PostConstruct} 同步阻塞——万级切片时显著拖慢启动。
     * 现挂到 {@code indexBuilderExecutor}（该线程池此前无消费者，正是为此预留）并等
     * {@code ApplicationReadyEvent} 触发，不阻塞容器启动。
     * </p>
     * <p>
     * 直接调用本方法（如测试）会同步执行——{@code @Async} 只对 Spring 代理生效。
     * </p>
     */
    @Async("indexBuilderExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void buildIndex() {
        buildState = BuildState.BUILDING;
        long start = System.currentTimeMillis();
        try {
            FSDirectory dir = directory();
            if (DirectoryReader.indexExists(dir)) {
                // 索引已存在 → 直接加载，不重建（任务 7.3.1）
                refreshReader();
                buildState = BuildState.READY;
                lastBuildError = null;
                log.info("BM25 磁盘索引已加载（未重建）: path={}, 条数={}, 耗时={}ms",
                        indexPath(), numDocs(), System.currentTimeMillis() - start);
                return;
            }

            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            int indexed = 0;
            try (IndexWriter writer = new IndexWriter(dir, config)) {
                long afterId = 0;
                while (true) {
                    List<IndexableText> page = indexableTextProvider.findIndexablePage(afterId, pageSize);
                    if (page == null || page.isEmpty()) {
                        break;
                    }
                    for (IndexableText text : page) {
                        if (text != null && text.getId() != null
                                && text.getContent() != null && !text.getContent().isBlank()) {
                            addToIndexInternal(writer, text);
                            indexed++;
                        }
                    }
                    afterId = maxId(page, afterId);
                }
                writer.commit();
            }
            refreshReader();
            buildState = BuildState.READY;
            lastBuildError = null;
            log.info("BM25 磁盘索引构建完成: path={}, 共索引 {} 条切片, 耗时={}ms",
                    indexPath(), indexed, System.currentTimeMillis() - start);
        } catch (Exception e) {
            buildState = BuildState.FAILED;
            lastBuildError = e.getMessage();
            log.error("BM25 索引构建失败（关键词检索路不可用，混合检索将降级为单路语义检索）: "
                    + "path={}, error={}", indexPath(), e.getMessage(), e);
        }
    }

    /**
     * 关键词检索。
     *
     * @param query 查询关键词
     * @param topK  返回的最大结果数
     * @return 检索结果列表
     */
    public List<RetrievedDocument> keywordSearch(String query, int topK) {
        List<RetrievedDocument> results = new ArrayList<>();
        IndexSearcher current = this.searcher;
        if (current == null) {
            // 构建/加载完成前一律返回空（不阻塞、不抛异常），失败时同样如此
            log.debug("BM25 索引尚未就绪（state={}），关键词检索返回空", buildState);
            return results;
        }

        try {
            QueryParser parser = new QueryParser("content", analyzer);
            Query luceneQuery = parser.parse(QueryParser.escape(query));
            TopDocs topDocs = current.search(luceneQuery, topK);

            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = current.doc(scoreDoc.doc);
                RetrievedDocument rd = new RetrievedDocument();
                rd.setDocumentId(doc.get("chunkId"));
                String chunkIdStr = doc.get("chunkId");
                if (chunkIdStr != null) {
                    try {
                        rd.setChunkId(Long.valueOf(chunkIdStr));
                    } catch (NumberFormatException e) {
                        log.debug("BM25 chunkId 解析失败: {}", chunkIdStr);
                    }
                }
                rd.setContent(doc.get("content"));
                rd.setDocumentTitle(doc.get("title"));
                rd.setSimilarity((double) scoreDoc.score);
                results.add(rd);
            }
            log.debug("BM25 检索: query={}, 返回 {} 条结果", query, results.size());
        } catch (Exception e) {
            log.error("BM25 检索失败: {}", e.getMessage());
        }

        return results;
    }

    /**
     * 增量添加文本到索引（<b>幂等</b>：同 chunkId 重复添加会替换而非追加）。
     */
    public void addToIndex(IndexableText text) {
        addBatchToIndex(List.of(text));
    }

    /**
     * 批量增量添加到索引（批次 07 · 任务 7.0.16 / 7.0e）。
     * <p>
     * <b>幂等实现</b>：按 {@code chunkId} 用 {@link IndexWriter#updateDocument} 写入——
     * 同一 chunkId 重复调用只替换文档，不会产生重复条目。这一点是"发布方尽力更新 +
     * Listener 校验补写"双保险能安全落地的前提（两处都可能重复添加同一批切片）。
     * </p>
     * <p>
     * 整批一次 {@code commit} + 一次 {@code refreshReader}，避免逐条打开 IndexWriter。
     * 磁盘索引改造（任务 7.3.1）不改变本方法的语义与幂等性。
     * </p>
     *
     * @param texts 待索引文本（空集合为无操作）
     * @return 实际写入/更新的条数；失败时返回 0 并记 ERROR（调用方决定是否降级）
     */
    public int addBatchToIndex(List<? extends IndexableText> texts) {
        if (texts == null || texts.isEmpty()) {
            return 0;
        }
        int written = 0;
        try {
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            try (IndexWriter writer = new IndexWriter(directory(), config)) {
                for (IndexableText text : texts) {
                    if (text == null || text.getId() == null
                            || text.getContent() == null || text.getContent().isBlank()) {
                        continue;
                    }
                    writer.updateDocument(new Term("chunkId", text.getId().toString()),
                            toLuceneDocument(text));
                    written++;
                }
                writer.commit();
            }
            refreshReader();
        } catch (Exception e) {
            log.error("BM25 批量索引更新失败: count={}", texts.size(), e);
            return 0;
        }
        return written;
    }

    /**
     * 找出尚未进入索引的切片 ID（批次 07 · 任务 7.0.18 的校验补写依据）。
     * <p>
     * 用 {@code chunkId} 的 Term 查询逐条判定。<b>索引尚未构建完成时（searcher 为 null）
     * 一律视为缺失</b>——宁可多写一次（写入是幂等的），也不能漏索引导致关键词检索搜不到。
     * </p>
     *
     * @param chunkIds 待校验的切片 ID 集合
     * @return 不在索引中的切片 ID（保持入参顺序）
     */
    public List<Long> findMissingChunkIds(Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        IndexSearcher current = this.searcher;
        if (current == null) {
            return new ArrayList<>(chunkIds);
        }
        List<Long> missing = new ArrayList<>();
        for (Long chunkId : chunkIds) {
            if (chunkId == null) {
                continue;
            }
            try {
                TopDocs topDocs = current.search(new TermQuery(new Term("chunkId", chunkId.toString())), 1);
                if (topDocs.scoreDocs.length == 0) {
                    missing.add(chunkId);
                }
            } catch (Exception e) {
                log.warn("BM25 索引校验失败，按缺失处理: chunkId={}, error={}", chunkId, e.getMessage());
                missing.add(chunkId);
            }
        }
        return missing;
    }

    /**
     * 判断索引是否已就绪（构建期间 BM25 路应视为不可用）。
     *
     * @return true 表示可以检索
     */
    public boolean isIndexReady() {
        return searcher != null;
    }

    /**
     * 索引是否处于降级状态（构建失败）。
     * <p>
     * 批次 07 · 任务 7.3.5：原实现失败后仅打一行日志，BM25 路静默失效——
     * 混合检索退化为单路语义检索却无人知晓。现在状态可被健康检查/日志核对。
     * </p>
     *
     * @return {@code true} 表示构建失败，关键词检索不可用
     */
    public boolean isDegraded() {
        return buildState == BuildState.FAILED;
    }

    /**
     * 当前构建状态（可观测性，任务 7.3.5）。
     *
     * @return 构建状态
     */
    public BuildState getBuildState() {
        return buildState;
    }

    /**
     * 最近一次构建失败的原因。
     *
     * @return 失败原因；未失败时为 {@code null}
     */
    public String getLastBuildError() {
        return lastBuildError;
    }

    /**
     * 当前索引中的文档数（不可用时返回 0）。
     *
     * @return 文档数
     */
    public int numDocs() {
        DirectoryReader current = this.reader;
        return current != null ? current.numDocs() : 0;
    }

    /**
     * 从索引中删除切片。
     */
    public void removeFromIndex(Long chunkId) {
        try {
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            try (IndexWriter writer = new IndexWriter(directory(), config)) {
                writer.deleteDocuments(new Term("chunkId", chunkId.toString()));
                writer.commit();
            }
            refreshReader();
        } catch (Exception e) {
            log.error("BM25 索引删除失败: chunkId={}", chunkId, e);
        }
    }

    /**
     * 磁盘索引目录路径（日志/健康检查用）。
     *
     * @return 索引目录路径
     */
    public String indexPath() {
        return Paths.get(indexDir).toAbsolutePath().normalize().toString();
    }

    /**
     * 惰性打开磁盘索引目录（{@code @Value} 注入完成前不能构造）。
     *
     * @return Lucene {@link FSDirectory}
     * @throws IOException 目录不可创建/不可写时
     */
    private FSDirectory directory() throws IOException {
        FSDirectory current = directory;
        if (current == null) {
            synchronized (this) {
                if (directory == null) {
                    Path path = Paths.get(indexDir);
                    directory = FSDirectory.open(path);
                    log.info("BM25 索引目录（磁盘）: {}", path.toAbsolutePath().normalize());
                }
                current = directory;
            }
        }
        return current;
    }

    private void addToIndexInternal(IndexWriter writer, IndexableText text) throws Exception {
        writer.addDocument(toLuceneDocument(text));
    }

    /**
     * 构造 Lucene 文档。
     *
     * @param text 可索引文本
     * @return Lucene 文档（chunkId 为 StringField，可作 Term 查询与 updateDocument 的主键）
     */
    private Document toLuceneDocument(IndexableText text) {
        Document doc = new Document();
        doc.add(new StringField("chunkId", text.getId().toString(), Field.Store.YES));
        doc.add(new TextField("content", text.getContent(), Field.Store.YES));
        doc.add(new StoredField("title", "chunk-" + text.getChunkIndex()));
        return doc;
    }

    /**
     * 取一页中的最大 ID（游标推进；无有效 ID 时保持原游标）。
     *
     * @param page    当前页
     * @param fallback 原游标
     * @return 新游标
     */
    private static long maxId(List<IndexableText> page, long fallback) {
        long max = fallback;
        for (IndexableText text : page) {
            if (text != null && text.getId() != null && text.getId() > max) {
                max = text.getId();
            }
        }
        return max;
    }

    private void refreshReader() {
        try {
            DirectoryReader newReader = DirectoryReader.open(directory());
            DirectoryReader oldReader = this.reader;
            this.reader = newReader;
            this.searcher = new IndexSearcher(newReader);
            if (oldReader != null) {
                oldReader.close();
            }
        } catch (Exception e) {
            log.error("BM25 索引 Reader 刷新失败", e);
        }
    }
}
