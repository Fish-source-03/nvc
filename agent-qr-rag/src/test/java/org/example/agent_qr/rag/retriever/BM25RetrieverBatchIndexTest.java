package org.example.agent_qr.rag.retriever;

import org.example.agent_qr.common.rag.IndexableText;
import org.example.agent_qr.common.rag.IndexableTextProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BM25Retriever} 增量索引测试（批次 07 · 任务 7.0.16 / 7.0e）。
 * <p>
 * 双保险方案要求"发布方尽力更新 + Listener 校验补写"两处都可能对同一批切片调用
 * {@code addBatchToIndex}，因此<b>幂等是硬要求</b>：重复更新不得产生重复文档
 * （否则同一内容在关键词检索里会出现两次，且评分被重复计数污染）。
 * </p>
 *
 * @author agent-qr
 */
class BM25RetrieverBatchIndexTest {

    /** 磁盘索引目录（批次 07 · 任务 7.3 起为 FSDirectory，测试必须用临时目录，不得污染仓库） */
    @TempDir
    Path indexDir;

    private BM25Retriever retriever;

    @BeforeEach
    void setUp() {
        retriever = newRetriever(indexDir, (IndexableTextProvider) List::of);
        retriever.buildIndex();
    }

    static BM25Retriever newRetriever(Path indexDir, IndexableTextProvider provider) {
        BM25Retriever retriever = new BM25Retriever();
        ReflectionTestUtils.setField(retriever, "indexableTextProvider", provider);
        ReflectionTestUtils.setField(retriever, "indexDir", indexDir.toString());
        return retriever;
    }

    @Test
    @DisplayName("★ 重复执行 BM25 索引更新不产生重复文档（按 chunkId updateDocument）")
    void addBatchToIndex_shouldBeIdempotent() {
        List<IndexableText> texts = List.of(text(1L, "人力资源 薪酬 制度"), text(2L, "财务 报销 流程"));

        assertThat(retriever.addBatchToIndex(texts)).isEqualTo(2);
        assertThat(retriever.addBatchToIndex(texts)).as("重复添加应替换而非追加").isEqualTo(2);
        assertThat(retriever.addBatchToIndex(texts)).isEqualTo(2);

        assertThat(retriever.findMissingChunkIds(List.of(1L, 2L))).isEmpty();
        // 同一内容只应命中一次（重复文档会让结果出现重复条目）
        assertThat(retriever.keywordSearch("薪酬", 10))
                .as("重复索引会让同一内容命中多次")
                .hasSize(1);
        assertThat(retriever.keywordSearch("薪酬", 10).get(0).getChunkId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("★ 校验能力：找出未进入索引的切片 ID，供 Listener 补写")
    void findMissingChunkIds_shouldReportMissingOnly() {
        retriever.addBatchToIndex(List.of(text(1L, "人力资源 薪酬 制度")));

        List<Long> missing = retriever.findMissingChunkIds(List.of(1L, 2L, 3L));

        assertThat(missing).containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("索引未构建（构建中）时一律视为缺失——宁可多写一次也不能漏索引")
    void findMissingChunkIds_shouldTreatAllAsMissing_whenIndexNotReady() {
        BM25Retriever notBuilt = newRetriever(indexDir.resolve("not-built"), (IndexableTextProvider) List::of);

        assertThat(notBuilt.isIndexReady()).isFalse();
        assertThat(notBuilt.findMissingChunkIds(List.of(1L, 2L))).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("空内容/无主键切片被跳过，不写坏索引")
    void addBatchToIndex_shouldSkipInvalidTexts() {
        List<IndexableText> texts = new ArrayList<>();
        texts.add(text(1L, "正常内容"));
        texts.add(text(2L, "   "));
        texts.add(text(null, "无主键"));

        assertThat(retriever.addBatchToIndex(texts)).isEqualTo(1);
        assertThat(retriever.findMissingChunkIds(List.of(1L))).isEmpty();
        assertThat(retriever.addBatchToIndex(List.of())).isZero();
        assertThat(retriever.addBatchToIndex(null)).isZero();
    }

    private static IndexableText text(Long id, String content) {
        return new IndexableText() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public Integer getChunkIndex() {
                return 0;
            }

            @Override
            public String getContent() {
                return content;
            }
        };
    }
}
