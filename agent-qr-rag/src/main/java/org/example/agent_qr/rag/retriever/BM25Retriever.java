package org.example.agent_qr.rag.retriever;

import jakarta.annotation.PostConstruct;
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
import org.apache.lucene.store.ByteBuffersDirectory;
import org.example.agent_qr.common.rag.IndexableText;
import org.example.agent_qr.common.rag.IndexableTextProvider;
import org.example.agent_qr.rag.entity.RetrievedDocument;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * BM25 关键词检索器 — 基于 Lucene 内存索引。
 * <p>
 * 使用 SmartChineseAnalyzer 进行中文智能分词，在内存中构建倒排索引，
 * 支持 BM25 评分检索和增量更新。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class BM25Retriever {

    @Autowired
    private IndexableTextProvider indexableTextProvider;

    private final ByteBuffersDirectory directory = new ByteBuffersDirectory();
    private final Analyzer analyzer = new SmartChineseAnalyzer();
    private volatile DirectoryReader reader;
    private volatile IndexSearcher searcher;

    /**
     * 启动时从 MySQL 加载全量索引文本构建 Lucene 内存索引。
     */
    @PostConstruct
    public void buildIndex() {
        try {
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            try (IndexWriter writer = new IndexWriter(directory, config)) {
                writer.deleteAll();
                List<IndexableText> texts = indexableTextProvider.findAllIndexable();
                for (IndexableText text : texts) {
                    if (text.getContent() != null && !text.getContent().isBlank()) {
                        addToIndexInternal(writer, text);
                    }
                }
                writer.commit();
            }
            refreshReader();
            log.info("BM25 索引构建完成，共索引 {} 条切片", reader != null ? reader.numDocs() : 0);
        } catch (Exception e) {
            log.error("BM25 索引构建失败", e);
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
        if (searcher == null) {
            return results;
        }

        try {
            QueryParser parser = new QueryParser("content", analyzer);
            Query luceneQuery = parser.parse(QueryParser.escape(query));
            TopDocs topDocs = searcher.search(luceneQuery, topK);

            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.doc(scoreDoc.doc);
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
            try (IndexWriter writer = new IndexWriter(directory, config)) {
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
     * 从索引中删除切片。
     */
    public void removeFromIndex(Long chunkId) {
        try {
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            try (IndexWriter writer = new IndexWriter(directory, config)) {
                writer.deleteDocuments(new Term("chunkId", chunkId.toString()));
                writer.commit();
            }
            refreshReader();
        } catch (Exception e) {
            log.error("BM25 索引删除失败: chunkId={}", chunkId, e);
        }
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

    private void refreshReader() {
        try {
            DirectoryReader newReader = DirectoryReader.open(directory);
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
