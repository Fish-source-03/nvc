package org.example.agent_qr.knowledge.listener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.common.dlq.DeadLetterQueue;
import org.example.agent_qr.common.dlq.entity.DlqMessage;
import org.example.agent_qr.common.event.DocumentParsedEvent;
import org.example.agent_qr.common.event.DocumentUploadedEvent;
import org.example.agent_qr.knowledge.enums.DocumentStatus;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.example.agent_qr.knowledge.parser.DocumentParserService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 文档解析事件监听器（P2 增强版）。
 * <p>
 * P1 原有：异步解析文档。
 * P2 增强：解析失败时通过 DeadLetterQueue 入队进行指数退避重试。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentParseListener {

    private final DocumentMapper documentMapper;
    private final DocumentParserService parserService;
    private final ApplicationEventPublisher eventPublisher;
    private final DeadLetterQueue deadLetterQueue;

    /**
     * 处理文档上传完成事件（P2 DLQ 集成版）。
     */
    @Async("parseExecutor")
    @EventListener
    public void handleDocumentUploaded(DocumentUploadedEvent event) {
        Long documentId = event.getDocumentId();
        log.info("开始解析文档: id={}, fileType={}", documentId, event.getFileType());

        documentMapper.updateStatus(documentId, DocumentStatus.PARSING.name());

        try {
            String content = parserService.parse(event.getFilePath(), event.getFileType());
            log.info("文档解析完成: id={}, 字符数={}", documentId, content.length());
            eventPublisher.publishEvent(new DocumentParsedEvent(this, documentId, content));
        } catch (Exception e) {
            log.error("文档解析失败: id={}, error={}", documentId, e.getMessage(), e);
            documentMapper.updateStatus(documentId, DocumentStatus.FAILED.name());
            documentMapper.updateErrorMsg(documentId, e.getMessage());

            // P2: 死信队列入队
            String payload = String.format("{\"documentId\":%d,\"filePath\":\"%s\",\"fileType\":\"%s\"}",
                    documentId, event.getFilePath(), event.getFileType());
            deadLetterQueue.enqueue(DlqMessage.EVENT_PARSE, documentId, payload, e);
        }
    }
}
