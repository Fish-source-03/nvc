package org.example.agent_qr.rag.retriever;

import org.example.agent_qr.common.rag.IndexableText;
import org.example.agent_qr.common.rag.IndexableTextProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BM25Retriever} 磁盘索引与异步构建测试（批次 07 · 任务 7.3 / 问题 15）。
 * <p>
 * 拦截的缺陷：索引建在堆内存（{@code ByteBuffersDirectory}）、{@code @PostConstruct} 同步阻塞启动、
 * 全量一次性加载、构建失败静默失效。本测试锁定四项验收：
 * 磁盘持久化、已存在不重建、构建期返回空、增量入口在磁盘索引下仍生效。
 * </p>
 *
 * @author agent-qr
 */
class BM25RetrieverDiskIndexTest {

    @TempDir
    Path indexDir;

    private BM25Retriever retriever;

    @BeforeEach
    void setUp() {
        retriever = newRetriever(List.of(text(1L, "人力资源 薪酬 制度"), text(2L, "财务 报销 流程")));
    }

    @Test
    @DisplayName("★ 索引落在磁盘目录（不再是 ByteBuffersDirectory 的堆内存索引）")
    void buildIndex_shouldPersistToDisk() throws Exception {
        retriever.buildIndex();

        assertThat(retriever.numDocs()).isEqualTo(2);
        try (Stream<Path> files = Files.list(indexDir)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .as("磁盘目录下应出现 Lucene 索引文件（segments_* 等）")
                    .anyMatch(name -> name.startsWith("segments_"));
        }
    }

    @Test
    @DisplayName("★ 索引已存在时启动直接加载、不重建（不读 MySQL、不改动索引文件）")
    void buildIndex_shouldLoadExistingIndexWithoutRebuild() throws Exception {
        retriever.buildIndex();
        long modifiedBefore = segmentsModifiedAt();

        // 第二个实例指向同一目录：若仍重建，会重新查询 provider 并改写索引文件
        IndexableTextProvider secondProvider = mock(IndexableTextProvider.class);
        BM25Retriever reloaded = newRetriever(secondProvider);
        reloaded.buildIndex();

        verify(secondProvider, never()).findIndexablePage(anyLong(), anyInt());
        verify(secondProvider, never()).findAllIndexable();
        assertThat(reloaded.numDocs()).as("索引内容应来自磁盘").isEqualTo(2);
        assertThat(reloaded.getBuildState()).isEqualTo(BM25Retriever.BuildState.READY);
        assertThat(segmentsModifiedAt())
                .as("重建会改写 segments 文件（mtime 前进）")
                .isEqualTo(modifiedBefore);
        assertThat(reloaded.keywordSearch("薪酬", 10)).hasSize(1);
    }

    @Test
    @DisplayName("★ 构建期间（未就绪）BM25 检索返回空但不抛异常")
    void keywordSearch_shouldReturnEmptyWithoutException_whenIndexNotReady() {
        BM25Retriever notBuilt = new BM25Retriever();
        ReflectionTestUtils.setField(notBuilt, "indexDir", indexDir.resolve("not-built").toString());

        assertThat(notBuilt.getBuildState()).isEqualTo(BM25Retriever.BuildState.PENDING);
        assertThatCode(() -> {
            assertThat(notBuilt.keywordSearch("任意关键词", 10)).isEmpty();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★ 分页加载：几十万条切片也不会一次性全量读取（逐页 keyset 拉取）")
    void buildIndex_shouldPageThroughProvider() {
        AtomicInteger pages = new AtomicInteger();
        IndexableTextProvider paged = mock(IndexableTextProvider.class);
        when(paged.findIndexablePage(anyLong(), anyInt())).thenAnswer(invocation -> {
            long afterId = invocation.getArgument(0);
            int limit = invocation.getArgument(1);
            pages.incrementAndGet();
            List<IndexableText> page = new ArrayList<>();
            for (long id = afterId + 1; id <= Math.min(afterId + limit, 5); id++) {
                page.add(text(id, "内容 " + id));
            }
            return page;
        });
        BM25Retriever pagedRetriever = newRetriever(paged);
        ReflectionTestUtils.setField(pagedRetriever, "pageSize", 2);

        pagedRetriever.buildIndex();

        assertThat(pagedRetriever.numDocs()).isEqualTo(5);
        assertThat(pages.get()).as("5 条 / 每页 2 条 → 3 页数据 + 1 次空页收尾").isEqualTo(4);
        verify(paged, never()).findAllIndexable();
    }

    @Test
    @DisplayName("★ 磁盘索引下增量入口仍生效（回归 7.0e：幂等 updateDocument + 校验补写）")
    void addBatchToIndex_shouldStillWorkOnDiskIndex() {
        retriever.buildIndex();
        assertThat(retriever.keywordSearch("薪酬", 10)).hasSize(1);

        // 增量新增
        assertThat(retriever.addBatchToIndex(List.of(text(3L, "薪酬 结构 调整 通知")))).isEqualTo(1);
        assertThat(retriever.keywordSearch("薪酬", 10)).hasSize(2);
        assertThat(retriever.findMissingChunkIds(List.of(1L, 3L))).isEmpty();

        // 幂等：重复更新不产生重复文档
        assertThat(retriever.addBatchToIndex(List.of(text(1L, "人力资源 薪酬 制度")))).isEqualTo(1);
        assertThat(retriever.findMissingChunkIds(List.of(1L, 2L, 3L))).isEmpty();

        // 重启后（新实例加载磁盘索引）增量数据仍在
        BM25Retriever reloaded = newRetriever(mock(IndexableTextProvider.class));
        reloaded.buildIndex();
        assertThat(reloaded.numDocs()).isEqualTo(3);
        assertThat(reloaded.keywordSearch("薪酬", 10)).hasSize(2);
    }

    @Test
    @DisplayName("★ 构建失败时降级标志被置位并保留原因（不再静默失效）")
    void buildIndex_shouldExposeFailure() {
        IndexableTextProvider failing = mock(IndexableTextProvider.class);
        when(failing.findIndexablePage(anyLong(), anyInt()))
                .thenThrow(new RuntimeException("数据库连接失败"));
        BM25Retriever broken = newRetriever(failing);

        broken.buildIndex();

        assertThat(broken.getBuildState()).isEqualTo(BM25Retriever.BuildState.FAILED);
        assertThat(broken.isDegraded()).isTrue();
        assertThat(broken.getLastBuildError()).contains("数据库连接失败");
        assertThat(broken.isIndexReady()).isFalse();
        assertThat(broken.keywordSearch("任意", 10)).isEmpty();
    }

    @Test
    @DisplayName("索引目录不存在时自动创建（FSDirectory.open 语义，不需要运维预建目录）")
    void buildIndex_shouldCreateMissingDirectory() {
        Path nested = indexDir.resolve("a/b/c");
        BM25Retriever nestedRetriever = new BM25Retriever();
        ReflectionTestUtils.setField(nestedRetriever, "indexDir", nested.toString());
        ReflectionTestUtils.setField(nestedRetriever, "indexableTextProvider", (IndexableTextProvider) () -> List.of());

        assertThatCode(nestedRetriever::buildIndex).doesNotThrowAnyException();
        assertThat(Files.isDirectory(nested)).isTrue();
    }

    // ==================== 辅助 ====================

    private BM25Retriever newRetriever(List<IndexableText> texts) {
        return newRetriever((IndexableTextProvider) () -> texts);
    }

    private BM25Retriever newRetriever(IndexableTextProvider provider) {
        BM25Retriever created = new BM25Retriever();
        ReflectionTestUtils.setField(created, "indexableTextProvider", provider);
        ReflectionTestUtils.setField(created, "indexDir", indexDir.toString());
        return created;
    }

    private long segmentsModifiedAt() throws Exception {
        try (Stream<Path> files = Files.list(indexDir)) {
            return files.filter(path -> path.getFileName().toString().startsWith("segments_"))
                    .mapToLong(path -> path.toFile().lastModified())
                    .max()
                    .orElseThrow();
        }
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
