package org.example.agent_qr.rag.retriever;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.example.agent_qr.catalog.dto.DomainRoutingResult;
import org.example.agent_qr.rag.entity.RetrievedDocument;
import org.example.agent_qr.rag.filter.StructuredFilterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * RRF 融合的跨路去重键测试（批次 09 · 任务 9.1，问题 18）。
 * <p>
 * <b>拦截的缺陷</b>：语义路（{@link ChromaRetriever}）的 {@code documentId} 是 ChromaDB 的
 * {@code embeddingId}（UUID），关键词路（{@link BM25Retriever}）是 chunkId 字符串——
 * 同一切片在两路得到<b>两个不同的去重键</b>，
 * {@link HybridRetriever#hybridSearch} 的 RRF 融合便无法把它们合并：
 * </p>
 * <ol>
 *   <li>结果<b>重复</b>——同一切片同时以 UUID 与 chunkId 两个条目返回给用户；</li>
 *   <li>分数<b>被低估</b>——两路各拿到一半权重，本应叠加的分量丢失。</li>
 * </ol>
 * <p>
 * 本测试用<b>真实的 {@link ChromaRetriever}</b>（只替换底层 {@link ChromaEmbeddingStore}）
 * 串起完整双路链路，因此能直接拦住"某一路改回去重键"的回退。
 * </p>
 * <p>
 * 说明：为保证"先失败、后通过"的判别力，断言同时覆盖"条目数 = 1"与
 * "分数 = 两路权重之和"——只断言前者时，若 RRF 公式被误改为取最大值仍会通过。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HybridRetrieverRrfDedupTest {

    /** ChromaDB 向量 id（UUID）——修复前被当作 documentId 的取值 */
    private static final String EMBEDDING_ID = "6e6a6f05-c3c6-41ae-b268-7dec27e51fef";

    private static final double SEMANTIC_WEIGHT = 0.55;
    private static final double KEYWORD_WEIGHT = 0.45;
    private static final int RRF_K = 15;

    @Mock
    private ChromaEmbeddingStore chromaEmbeddingStore;

    @Mock
    private BM25Retriever bm25Retriever;

    @Mock
    private RerankerService rerankerService;

    @Mock
    private StructuredFilterService structuredFilterService;

    private ChromaRetriever chromaRetriever;
    private HybridRetriever hybridRetriever;

    @BeforeEach
    void setUp() {
        chromaRetriever = new ChromaRetriever();
        ReflectionTestUtils.setField(chromaRetriever, "chromaEmbeddingStore", chromaEmbeddingStore);

        hybridRetriever = new HybridRetriever();
        ReflectionTestUtils.setField(hybridRetriever, "chromaRetriever", chromaRetriever);
        ReflectionTestUtils.setField(hybridRetriever, "bm25Retriever", bm25Retriever);
        ReflectionTestUtils.setField(hybridRetriever, "rerankerService", rerankerService);
        ReflectionTestUtils.setField(hybridRetriever, "structuredFilterService", structuredFilterService);
        ReflectionTestUtils.setField(hybridRetriever, "semanticWeight", SEMANTIC_WEIGHT);
        ReflectionTestUtils.setField(hybridRetriever, "keywordWeight", KEYWORD_WEIGHT);
        ReflectionTestUtils.setField(hybridRetriever, "wideTopK", 20);
        ReflectionTestUtils.setField(hybridRetriever, "finalTopK", 30);
        ReflectionTestUtils.setField(hybridRetriever, "rrfK", RRF_K);

        // Rerank 在本测试中不是被测对象，直接透传融合结果
        when(rerankerService.rerank(any(), anyList(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    // ==================== ★ 缺陷复现点：同一切片被两路召回 ====================

    @Test
    @DisplayName("★ 同一 chunk 被两路召回 → 融合后只出现一次，且分数为两路权重之和（问题 18）")
    void hybridSearch_shouldDedupByChunkId_whenSameChunkRecalledByBothPaths() {
        givenSemanticHit(EMBEDDING_ID, 7387L, "员工手册", 0.91);
        when(bm25Retriever.keywordSearch(any(), anyInt())).thenReturn(List.of(bm25Doc(7387L, "员工手册")));

        List<RetrievedDocument> results = hybridRetriever.hybridSearch(
                "年假有多少天", new float[]{0.1f}, DomainRoutingResult.fallback(), List.of());

        assertThat(results)
                .as("修复前语义路 key=UUID、关键词路 key=chunkId，同一切片会产生两条结果")
                .hasSize(1);
        assertThat(results.get(0).getDocumentId())
                .as("两路必须使用同一去重键（chunkId 字符串）")
                .isEqualTo("7387");
        assertThat(results.get(0).getChunkId()).isEqualTo(7387L);
        assertThat(results.get(0).getSimilarity())
                .as("分数必须是两路之和（0.55/16 + 0.45/16），只取其中一路即为'分数被低估'")
                .isEqualTo((SEMANTIC_WEIGHT + KEYWORD_WEIGHT) / (RRF_K + 1));
    }

    @Test
    @DisplayName("★ 仅被一路召回的 chunk 正常保留（去重不得误合并不同切片）")
    void hybridSearch_shouldKeepSinglePathHits() {
        givenSemanticHit(EMBEDDING_ID, 1L, "仅语义命中", 0.80);
        when(bm25Retriever.keywordSearch(any(), anyInt())).thenReturn(List.of(bm25Doc(2L, "仅关键词命中")));

        List<RetrievedDocument> results = hybridRetriever.hybridSearch(
                "请假流程", new float[]{0.1f}, DomainRoutingResult.fallback(), List.of());

        assertThat(results).extracting(RetrievedDocument::getDocumentId)
                .containsExactlyInAnyOrder("1", "2");
        assertThat(results).allSatisfy(doc -> assertThat(doc.getSimilarity()).isGreaterThan(0.0));
    }

    @Test
    @DisplayName("★ 三路召回同一 chunk + 一路独有 → 各归其位（回归：多命中去重正确）")
    void hybridSearch_shouldDedupAllHits_withinSamePath() {
        when(chromaEmbeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of(
                        match(EMBEDDING_ID, 5L, "同一文档", 0.95),
                        match("11111111-2222-3333-4444-555555555555", 9L, "另一文档", 0.60))));
        when(bm25Retriever.keywordSearch(any(), anyInt()))
                .thenReturn(List.of(bm25Doc(5L, "同一文档"), bm25Doc(6L, "关键词独有")));

        List<RetrievedDocument> results = hybridRetriever.hybridSearch(
                "制度", new float[]{0.1f}, DomainRoutingResult.fallback(), List.of());

        assertThat(results).extracting(RetrievedDocument::getDocumentId)
                .containsExactlyInAnyOrder("5", "9", "6");
    }

    // ==================== 根因：语义路的标识来源 ====================

    @Test
    @DisplayName("★ ChromaRetriever 的 documentId 取 chunkId（元数据），而非 embeddingId（UUID）")
    void similaritySearch_shouldExposeChunkIdAsDocumentId() {
        givenSemanticHit(EMBEDDING_ID, 7387L, "员工手册", 0.91);

        List<RetrievedDocument> documents = chromaRetriever.similaritySearch(new float[]{0.1f}, 5);

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).getDocumentId())
                .as("文档 18 的根因：语义路此前返回 embeddingId，两路无法合并")
                .isEqualTo("7387")
                .isNotEqualTo(EMBEDDING_ID);
        assertThat(documents.get(0).getChunkId()).isEqualTo(7387L);
        assertThat(documents.get(0).getDocumentTitle()).isEqualTo("员工手册");
    }

    @Test
    @DisplayName("★ R37：历史向量元数据缺 chunk_id → documentId 带 vector: 命名空间前缀（不再与 chunkId 混淆）")
    void similaritySearch_shouldFallbackToNamespacedVectorId_whenChunkIdMissing() {
        when(chromaEmbeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of(
                        new EmbeddingMatch<>(0.7, EMBEDDING_ID, null, TextSegment.from("历史向量")))));

        List<RetrievedDocument> documents = chromaRetriever.similaritySearch(new float[]{0.1f}, 5);

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).getDocumentId())
                .as("回退值必须带显式命名空间，标识非空且不可能被误读为 chunkId")
                .isEqualTo(RetrievedDocument.VECTOR_ID_NAMESPACE_PREFIX + EMBEDDING_ID)
                .isNotEqualTo(EMBEDDING_ID);
        assertThat(documents.get(0).getChunkId()).isNull();
        assertThat(documents.get(0).getDocumentTitle())
                .as("连 chunkId 都没有时才使用兜底标题")
                .isEqualTo(RetrievedDocument.UNTITLED_DOCUMENT_TITLE);
    }

    // ==================== R37：documentTitle 口径（三条路径统一占位） ====================

    @Test
    @DisplayName("★ R37：语义路有真实标题 → 用真实标题；缺 document_title → 与关键词/聚合路同占位（chunk-<chunkId>）")
    void similaritySearch_shouldUsePlaceholderTitle_whenDocumentTitleMetadataMissing() {
        Metadata metadata = new Metadata();
        metadata.put("chunk_id", "7387");
        when(chromaEmbeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of(
                        new EmbeddingMatch<>(0.9, EMBEDDING_ID, (Embedding) null,
                                TextSegment.from("内容", metadata)))));

        List<RetrievedDocument> documents = chromaRetriever.similaritySearch(new float[]{0.1f}, 5);

        assertThat(documents.get(0).getDocumentTitle())
                .as("占位口径与 BM25 索引/聚合路径同源，避免同一文档三种标题")
                .isEqualTo(RetrievedDocument.placeholderTitle(7387L))
                .isEqualTo("chunk-7387");
    }

    @Test
    @DisplayName("★ R37：占位标题构造口径唯一（有 chunkId → chunk-<id>；无 → 未命名文档）")
    void placeholderTitle_shouldBeTheSingleConvention() {
        assertThat(RetrievedDocument.placeholderTitle(42L)).isEqualTo("chunk-42");
        assertThat(RetrievedDocument.placeholderTitle(null))
                .isEqualTo(RetrievedDocument.UNTITLED_DOCUMENT_TITLE);
    }

    // ==================== 辅助 ====================

    private void givenSemanticHit(String embeddingId, Long chunkId, String title, double score) {
        when(chromaEmbeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of(match(embeddingId, chunkId, title, score))));
    }

    private static EmbeddingMatch<TextSegment> match(String embeddingId, Long chunkId, String title, double score) {
        Metadata metadata = new Metadata();
        if (chunkId != null) {
            metadata.put("chunk_id", chunkId.toString());
        }
        metadata.put("document_title", title);
        return new EmbeddingMatch<>(score, embeddingId, (Embedding) null, TextSegment.from("内容-" + chunkId, metadata));
    }

    private static RetrievedDocument bm25Doc(long chunkId, String title) {
        RetrievedDocument document = new RetrievedDocument();
        document.setDocumentId(String.valueOf(chunkId));
        document.setChunkId(chunkId);
        document.setDocumentTitle(title);
        document.setContent("内容-" + chunkId);
        document.setSimilarity(12.5);
        return document;
    }
}
