package org.example.agent_qr.web.websocket;

import org.example.agent_qr.common.event.ChunksBatchCreatedEvent;
import org.example.agent_qr.common.event.DocumentParsedEvent;
import org.example.agent_qr.common.event.DocumentUploadedEvent;
import org.example.agent_qr.common.event.EmbeddingCompletedEvent;
import org.example.agent_qr.knowledge.entity.Document;
import org.example.agent_qr.knowledge.enums.DocumentStatus;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 文档处理进度推送器测试（批次 10 · 任务 10.2.3，问题 34 场景①）。
 * <p>
 * 拦截的核心缺陷：前端 {@code subscribe} 是死代码 + 后端根本没有推送端点；
 * 补齐后必须保证消息<b>只投递到归属用户的目的地</b>（禁止广播含用户数据的消息）。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentProgressNotifierTest {

    private static final String DESTINATION = DocumentProgressNotifier.USER_QUEUE;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private DocumentMapper documentMapper;

    @InjectMocks
    private DocumentProgressNotifier notifier;

    @Test
    @DisplayName("★ 文档上传事件 → 推送到上传者自己的 /user/{userId}/queue/documents/progress")
    void onDocumentUploaded_shouldPushToOwnerDestination() {
        notifier.onDocumentUploaded(new DocumentUploadedEvent(this, 5L, "/tmp/a.pdf", "a.pdf", "pdf", 7L));

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSendToUser(eq("7"), eq(DESTINATION), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("type", "DOCUMENT_PROGRESS")
                .containsEntry("documentId", 5L)
                .containsEntry("status", DocumentStatus.PARSING.name())
                .containsEntry("title", "a.pdf");
    }

    @Test
    @DisplayName("★ 后续事件按 kb_document.upload_user_id 投递（不同用户的文档互不串号）")
    void onDocumentParsed_shouldIsolateUsers() {
        when(documentMapper.selectById(5L)).thenReturn(document(5L, 7L, "alice.pdf", DocumentStatus.CHUNKING));
        when(documentMapper.selectById(6L)).thenReturn(document(6L, 8L, "bob.pdf", DocumentStatus.CHUNKING));

        notifier.onDocumentParsed(new DocumentParsedEvent(this, 5L, "内容A"));
        notifier.onDocumentParsed(new DocumentParsedEvent(this, 6L, "内容B"));

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSendToUser(eq("7"), eq(DESTINATION), payloadCaptor.capture());
        verify(messagingTemplate).convertAndSendToUser(eq("8"), eq(DESTINATION), payloadCaptor.capture());
        assertThat(payloadCaptor.getAllValues())
                .extracting(payload -> payload.get("documentId"))
                .containsExactly(5L, 6L);
        // 绝不使用广播目的地推送含用户数据的消息
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("切片批量创建 → 推送 INDEXED（部分就绪：关键词可搜、向量未写）")
    void onChunksBatchCreated_shouldPushIndexed() {
        when(documentMapper.selectById(5L)).thenReturn(document(5L, 7L, "alice.pdf", DocumentStatus.INDEXED));

        notifier.onChunksBatchCreated(ChunksBatchCreatedEvent.forDocument(5L));

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSendToUser(eq("7"), eq(DESTINATION), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("status", DocumentStatus.INDEXED.name())
                .containsEntry("statusText", DocumentStatus.INDEXED.getDescription());
    }

    @Test
    @DisplayName("数据同步链路的批量事件（无 documentId）不推送用户进度")
    void onChunksBatchCreated_shouldSkipDatasourceChain() {
        notifier.onChunksBatchCreated(ChunksBatchCreatedEvent.forDatasource(3L, "batch-1"));

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("★ 向量化完成 → 以数据库最终状态推送（READY / FAILED）并带切片数")
    void onEmbeddingCompleted_shouldPushFinalStatus() {
        when(documentMapper.selectById(5L)).thenReturn(document(5L, 7L, "alice.pdf", DocumentStatus.READY));

        notifier.onEmbeddingCompleted(new EmbeddingCompletedEvent(this, 5L, 12));

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSendToUser(eq("7"), eq(DESTINATION), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("status", DocumentStatus.READY.name())
                .containsEntry("chunkCount", 12);
    }

    @Test
    @DisplayName("文档无归属用户 / 文档不存在 → 不推送（不抛异常，不影响主流程）")
    void shouldSkip_whenOwnerUnknown() {
        when(documentMapper.selectById(9L)).thenReturn(null);

        notifier.onDocumentParsed(new DocumentParsedEvent(this, 9L, "内容"));
        notifier.onDocumentUploaded(new DocumentUploadedEvent(this, 9L, "/tmp/a.pdf", "a.pdf", "pdf", null));

        verifyNoInteractions(messagingTemplate);
    }

    private static Document document(Long id, Long uploadUserId, String title, DocumentStatus status) {
        Document document = new Document();
        document.setId(id);
        document.setUploadUserId(uploadUserId);
        document.setTitle(title);
        document.setStatus(status);
        return document;
    }
}
