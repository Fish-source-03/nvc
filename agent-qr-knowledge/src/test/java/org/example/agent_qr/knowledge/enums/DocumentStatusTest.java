package org.example.agent_qr.knowledge.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档双状态机测试（批次 07 · 任务 7.0a / 7.0.1）。
 * <p>
 * 拦截的核心缺陷（问题 28）：原状态机把"切片入库"与"向量已写入"混为一谈，
 * 提交向量化任务后即置 READY，用户看到"就绪"却搜不到内容。
 * </p>
 *
 * @author agent-qr
 */
class DocumentStatusTest {

    @Test
    @DisplayName("★ 共 8 个状态，INDEXED 插在 CHUNKING 与 EMBEDDING 之间")
    void shouldHaveEightStatuses_withIndexedBetweenChunkingAndEmbedding() {
        assertThat(DocumentStatus.values()).hasSize(8);

        List<String> names = List.of(
                DocumentStatus.UPLOADED.name(),
                DocumentStatus.PARSING.name(),
                DocumentStatus.CHUNKING.name(),
                DocumentStatus.INDEXED.name(),
                DocumentStatus.EMBEDDING.name(),
                DocumentStatus.READY.name());

        assertThat(names).containsExactly("UPLOADED", "PARSING", "CHUNKING", "INDEXED", "EMBEDDING", "READY");

        // 声明顺序即状态流转顺序：INDEXED 必须在 CHUNKING 之后、EMBEDDING 之前
        assertThat(DocumentStatus.valueOf("INDEXED").ordinal())
                .isGreaterThan(DocumentStatus.CHUNKING.ordinal())
                .isLessThan(DocumentStatus.EMBEDDING.ordinal());
    }

    @Test
    @DisplayName("★ INDEXED 与 EMBEDDING 语义可区分：已入库（关键词可搜） vs 向量化中")
    void indexedAndEmbedding_shouldBeDistinguishable() {
        assertThat(DocumentStatus.INDEXED.getDescription()).contains("关键词可搜");
        assertThat(DocumentStatus.EMBEDDING.getDescription()).contains("向量化中");
        assertThat(DocumentStatus.READY.getDescription()).doesNotContain("部分");
        assertThat(DocumentStatus.INDEXED.getDescription())
                .as("INDEXED 不得被描述为完全就绪")
                .isNotEqualTo(DocumentStatus.READY.getDescription());
    }

    @Test
    @DisplayName("每个状态都有中文描述（前端展示依赖）")
    void everyStatus_shouldHaveDescription() {
        for (DocumentStatus status : DocumentStatus.values()) {
            assertThat(status.getDescription()).as(status.name() + " 缺少描述").isNotBlank();
        }
    }
}
