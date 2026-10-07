package org.example.agent_qr.compensation.listener;

import org.example.agent_qr.common.dlq.DeadLetterQueue;
import org.example.agent_qr.common.dlq.entity.DlqMessage;
import org.example.agent_qr.common.event.DocumentDeleteRequestedEvent;
import org.example.agent_qr.compensation.service.DocumentDeleteServiceV2;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.example.agent_qr.knowledge.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * {@link DocumentDeleteListener} 物理文件清理测试（批次 08 · 任务 8.4，问题 32）。
 * <p>
 * 拦截的缺陷：{@code DocumentDeleteRequestedEvent.filePath} 的用途在设计 §7.2.6 中写明为
 * "清理上传文件"，但消费方<b>从未读取</b>——文档删除后 {@code uploads/} 下的物理文件永久残留，
 * 磁盘占用只增不减（且用户以为已删除）。
 * </p>
 * <p>
 * 使用<b>真实</b>的 {@link FileStorageService} 与临时目录（而非 Mock），
 * 以验证"文件确实被删除"这一事实本身；幂等性与失败路径同样走真实文件系统。
 * </p>
 *
 * @author agent-qr
 */
class DocumentDeleteListenerTest {

    @TempDir
    Path uploadDir;

    private DocumentMapper documentMapper;
    private ChunkMapper chunkMapper;
    private DocumentDeleteServiceV2 documentDeleteServiceV2;
    private DeadLetterQueue deadLetterQueue;
    private FileStorageService fileStorageService;
    private DocumentDeleteListener listener;

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        chunkMapper = mock(ChunkMapper.class);
        documentDeleteServiceV2 = mock(DocumentDeleteServiceV2.class);
        deadLetterQueue = mock(DeadLetterQueue.class);

        fileStorageService = new FileStorageService();
        ReflectionTestUtils.setField(fileStorageService, "uploadDir", uploadDir.toString());

        listener = new DocumentDeleteListener(documentMapper, chunkMapper,
                documentDeleteServiceV2, deadLetterQueue);
        ReflectionTestUtils.setField(listener, "fileStorageService", fileStorageService);
    }

    // ==================== 用例：删除文档后物理文件确实被清理 ====================

    @Test
    @DisplayName("★ 文档删除后 uploads/ 下的物理文件被删除（修复前 filePath 无任何消费者）")
    void handleDocumentDeleteRequested_shouldDeletePhysicalFile() throws Exception {
        String relativePath = storeTempFile("2026/10", "zz_b08_doc.pdf");
        Path stored = uploadDir.resolve(relativePath);
        assertThat(stored).exists();

        listener.handleDocumentDeleteRequested(event(900L, relativePath));

        assertThat(stored).as("物理文件应被清理").doesNotExist();
        // 删除链路其余环节不受影响
        verify(chunkMapper).softDeleteByDocumentId(900L);
        verify(documentMapper).softDelete(900L);
        verify(documentDeleteServiceV2).asyncPhysicalDelete(eq(900L), any());
        verify(deadLetterQueue, never()).enqueue(anyString(), anyLong(), anyString(), any());
    }

    // ==================== 用例：幂等（文件已不存在不抛异常） ====================

    @Test
    @DisplayName("★ 幂等：文件已被人工删除时删除操作不抛异常、不产生 DLQ")
    void handleDocumentDeleteRequested_shouldBeIdempotent_whenFileAlreadyMissing() {
        assertThatCode(() -> listener.handleDocumentDeleteRequested(event(901L, "2026/10/never-existed.pdf")))
                .doesNotThrowAnyException();

        verify(deadLetterQueue, never()).enqueue(anyString(), anyLong(), anyString(), any());
    }

    // ==================== 用例：失败入 DLQ ====================

    @Test
    @DisplayName("★ 文件删除失败时入 DLQ（DELETE 类型，payload 带 filePath 供重放）")
    void handleDocumentDeleteRequested_shouldEnqueueDlq_whenFileDeleteFails() throws Exception {
        // 构造一个"非空目录"充当删除失败（deleteIfExists 抛 DirectoryNotEmptyException）
        Path notEmptyDir = Files.createDirectories(uploadDir.resolve("2026/12"));
        Files.writeString(notEmptyDir.resolve("inner.txt"), "x");

        listener.handleDocumentDeleteRequested(event(902L, "2026/12"));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(deadLetterQueue).enqueue(eq(DlqMessage.EVENT_DELETE), eq(902L), payload.capture(), any());
        assertThat(payload.getValue())
                .contains("\"filePath\":\"2026/12\"")
                .contains("\"documentId\":902");
        // 文件失败不阻断向量删除（两者并列）
        verify(documentDeleteServiceV2).asyncPhysicalDelete(eq(902L), any());
    }

    // ==================== 用例：filePath 为空时不调用删除 ====================

    @Test
    @DisplayName("★ filePath 为空时不调用文件删除（无需清理，不该触发任何文件操作）")
    void handleDocumentDeleteRequested_shouldNotDelete_whenFilePathIsBlank() {
        FileStorageService spied = spy(fileStorageService);
        ReflectionTestUtils.setField(listener, "fileStorageService", spied);

        listener.handleDocumentDeleteRequested(event(903L, null));
        listener.handleDocumentDeleteRequested(event(904L, "   "));

        verify(spied, never()).delete(any());
        verify(deadLetterQueue, never()).enqueue(anyString(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("★ FileStorageService 未装配时只告警跳过，不影响删除主链路")
    void handleDocumentDeleteRequested_shouldSkipFileCleanup_whenServiceMissing() {
        ReflectionTestUtils.setField(listener, "fileStorageService", null);

        listener.handleDocumentDeleteRequested(event(905L, "2026/10/whatever.pdf"));

        verify(chunkMapper).softDeleteByDocumentId(905L);
        verify(documentMapper).softDelete(905L);
        verify(documentDeleteServiceV2).asyncPhysicalDelete(eq(905L), any());
        verify(deadLetterQueue, never()).enqueue(anyString(), anyLong(), anyString(), any());
    }

    // ==================== 辅助 ====================

    private String storeTempFile(String subDir, String fileName) throws Exception {
        Path dir = Files.createDirectories(uploadDir.resolve(subDir));
        Path file = dir.resolve(fileName);
        Files.writeString(file, "测试内容");
        return subDir + "/" + fileName;
    }

    private static DocumentDeleteRequestedEvent event(Long documentId, String filePath) {
        return new DocumentDeleteRequestedEvent(documentId, List.of(1L, 2L), List.of("v-1"), filePath);
    }
}
