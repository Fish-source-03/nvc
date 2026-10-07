package org.example.agent_qr.web.scheduler;

import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.example.agent_qr.common.dlq.DeadLetterQueue;
import org.example.agent_qr.common.dlq.DlqMessageMapper;
import org.example.agent_qr.common.dlq.entity.DlqMessage;
import org.example.agent_qr.common.event.DocumentParsedEvent;
import org.example.agent_qr.datasource.mapper.DataSourceMapper;
import org.example.agent_qr.knowledge.entity.Document;
import org.example.agent_qr.knowledge.listener.ChunkEmbeddingBatchListener;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.example.agent_qr.knowledge.parser.DocumentParserService;
import org.example.agent_qr.knowledge.splitter.TextSplitter;
import org.example.agent_qr.rag.embedding.BatchEmbeddingService;
import org.example.agent_qr.rag.retriever.BM25Retriever;
import org.example.agent_qr.rag.retriever.ChromaRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PARSE / CHUNK 死信重放的<b>退避收敛</b>测试（批次 11 · 任务 11.4，风险 R30 —— 无界环路）。
 * <p>
 * <b>被拦截的环路</b>：{@code retryParse} / {@code retryChunk} 原先发布
 * {@link DocumentParsedEvent} 后<b>无条件</b>把当前消息标记成功；而消费方
 * {@code ChunkEmbeddingBatchListener#handleDocumentParsed} 是 {@code @Async} 的，
 * 切片阶段失败时会 {@code enqueue(EVENT_CHUNK, …, retryCount=0)}——确定性切片失败下
 * 每轮重放都新增一条 {@code retryCount=0} 的死信，<b>链式重放永不终止</b>（与 R1/R24 同构）。
 * </p>
 * <p>
 * 修复后重放改走<b>同步入口</b> {@code ChunkEmbeddingBatchListener#processDocumentParsed}
 * （失败上抛、不自行入队）。本测试用<b>真实的</b> {@link DeadLetterQueue} +
 * <b>真实的</b> {@link ChunkEmbeddingBatchListener}（只有外围依赖是 mock）驱动完整机制：
 * 连续 4 轮重放（每轮把 {@code nextRetryAt} 拨到过去，模拟退避时间流逝），断言：
 * ① <b>从未产生新死信</b>（修复前每轮都会新增一条 {@code retryCount=0} 的 CHUNK 死信）；
 * ② 重试次数按 1→2→3 递增、退避 9s/27s/81s（R31 口径：入队首跳 3s、重试路径从 9s 起）；
 * ③ 第 4 轮达到 max-retries 后转 {@code DEAD}（链路收敛）；④ 当前消息从未被当作成功删除。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DlqParseChunkReplayBackoffTest {

    private static final Long MSG_ID = 88L;
    private static final Long DOCUMENT_ID = 901L;

    @Mock
    private DlqMessageMapper dlqMessageMapper;

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private ChunkMapper chunkMapper;

    @Mock
    private DataSourceMapper dataSourceMapper;

    @Mock
    private TextSplitter textSplitter;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    /** 监听器自身的 DLQ 依赖（主链路失败入队）——与调度器用的"真实 DLQ"区分开 */
    @Mock
    private DeadLetterQueue listenerDeadLetterQueue;

    @Mock
    private BatchEmbeddingService batchEmbeddingService;

    @Mock
    private ChromaEmbeddingStore chromaEmbeddingStore;

    @Mock
    private ChromaRetriever chromaRetriever;

    @Mock
    private BM25Retriever bm25Retriever;

    @Mock
    private DocumentParserService parserService;

    /** 被重放的真实对象：真实的切片监听器（同步入口）+ 真实的 DLQ（退避与 DEAD 判定） */
    private ChunkEmbeddingBatchListener listener;
    private DeadLetterQueue deadLetterQueue;
    private DlqRetryScheduler scheduler;

    /** 内存中的死信行（模拟 dlq_message 表） */
    private final AtomicReference<DlqMessage> row = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        // ---------- 真实的 DeadLetterQueue（退避与 DEAD 判定用真实实现） ----------
        deadLetterQueue = new DeadLetterQueue();
        ReflectionTestUtils.setField(deadLetterQueue, "dlqMessageMapper", dlqMessageMapper);
        ReflectionTestUtils.setField(deadLetterQueue, "maxRetries", 4);
        ReflectionTestUtils.setField(deadLetterQueue, "backoffBase", 3);

        // ---------- 真实的切片监听器：切片阶段"持续失败"（确定性失败，非偶发） ----------
        listener = new ChunkEmbeddingBatchListener(documentMapper, chunkMapper, dataSourceMapper,
                textSplitter, eventPublisher, listenerDeadLetterQueue, batchEmbeddingService,
                chromaEmbeddingStore, chromaRetriever, bm25Retriever);
        when(textSplitter.split(anyString())).thenThrow(new RuntimeException("切片器持续崩溃（确定性失败）"));
        when(chunkMapper.softDeleteByDocumentId(anyLong())).thenReturn(1);

        // ---------- 待重放的消息行（默认 CHUNK） ----------
        DlqMessage msg = new DlqMessage();
        msg.setId(MSG_ID);
        msg.setEventType(DlqMessage.EVENT_CHUNK);
        msg.setDocumentId(DOCUMENT_ID);
        msg.setPayload("{\"documentId\":901}");
        msg.setStatus(DlqMessage.STATUS_PENDING);
        msg.setRetryCount(0);
        msg.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        row.set(msg);

        when(dlqMessageMapper.selectById(MSG_ID)).thenAnswer(invocation -> row.get());
        when(dlqMessageMapper.selectPendingRetries(any(LocalDateTime.class)))
                .thenAnswer(invocation -> DlqMessage.STATUS_PENDING.equals(row.get().getStatus())
                        && row.get().getNextRetryAt() != null
                        && !row.get().getNextRetryAt().isAfter((LocalDateTime) invocation.getArgument(0))
                        ? List.of(row.get()) : List.of());
        org.mockito.Mockito.doAnswer(invocation -> {
            row.get().setRetryCount(invocation.getArgument(1));
            row.get().setNextRetryAt(invocation.getArgument(2));
            return 1;
        }).when(dlqMessageMapper).updateRetry(anyLong(), anyInt(), any(LocalDateTime.class), anyString());
        org.mockito.Mockito.doAnswer(invocation -> {
            row.get().setStatus(invocation.getArgument(1));
            return 1;
        }).when(dlqMessageMapper).updateStatus(anyLong(), anyString(), anyString());

        // ---------- 调度器（PARSE / CHUNK 重放改调同步入口） ----------
        scheduler = new DlqRetryScheduler();
        ReflectionTestUtils.setField(scheduler, "deadLetterQueue", deadLetterQueue);
        ReflectionTestUtils.setField(scheduler, "dlqMessageMapper", dlqMessageMapper);
        ReflectionTestUtils.setField(scheduler, "chunkEmbeddingBatchListener", listener);
        ReflectionTestUtils.setField(scheduler, "parserService", parserService);
        ReflectionTestUtils.setField(scheduler, "documentMapper", documentMapper);
        ReflectionTestUtils.setField(scheduler, "chunkMapper", chunkMapper);
        ReflectionTestUtils.setField(scheduler, "documentDeleteServiceV2", null);
        ReflectionTestUtils.setField(scheduler, "deleteTaskMapper", null);
        ReflectionTestUtils.setField(scheduler, "fileStorageService", null);
    }

    @Test
    @DisplayName("★ R30（CHUNK）：切片持续失败时重放不产生新死信、按退避推进、max-retries 后转 DEAD")
    void chunkReplay_shouldBackOffAndReachDead_withoutProducingNewDeadLetters() {
        Document doc = new Document();
        doc.setId(DOCUMENT_ID);
        doc.setFilePath("uploads/handbook.pdf");
        doc.setFileType("pdf");
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(doc);
        when(parserService.parse(anyString(), anyString())).thenReturn("解析后的正文");
        clearInvocations(dlqMessageMapper);

        // 第 1~3 轮：退避 3^(n+1) → 9s / 27s / 81s；每轮结束后把 nextRetryAt 拨到过去
        for (int round = 1; round <= 3; round++) {
            scheduler.retryDeadLetters();

            assertThat(row.get().getRetryCount())
                    .as("第 %d 轮后重试次数应递增（失败落在当前消息上）", round)
                    .isEqualTo(round);
            assertThat(row.get().getStatus())
                    .as("未达上限前仍是 PENDING，等待退避重试")
                    .isEqualTo(DlqMessage.STATUS_PENDING);
            long expectedBackoff = (long) Math.pow(3, round + 1);
            long backoff = Duration.between(LocalDateTime.now(), row.get().getNextRetryAt()).getSeconds();
            assertThat(backoff)
                    .as("退避应为 3^%d 秒（既有指数退避机制被复用，未被绕过）", round + 1)
                    .isBetween(expectedBackoff - 2, expectedBackoff + 2);

            row.get().setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        }

        // 第 4 轮：达到 maxRetries(4) → 转 DEAD，链路收敛
        scheduler.retryDeadLetters();
        assertThat(row.get().getStatus()).isEqualTo(DlqMessage.STATUS_DEAD);

        // ① 从未产生新死信（修复前每轮都会新增一条 retryCount=0 的 CHUNK 死信）
        verify(dlqMessageMapper, never()).insert(any(DlqMessage.class));
        // ④ 当前消息从未被当作成功删除（修复前 updateRetryResult(true) 会 deleteById）
        verify(dlqMessageMapper, never()).deleteById(anyLong());
        // 每轮都真的重放了切片动作（同步入口被执行，而非"发布事件即成功"）
        verify(textSplitter, atLeast(4)).split(anyString());
    }

    @Test
    @DisplayName("★ R30（PARSE）：切片持续失败时同样落在当前消息上（不再是'发布事件即成功'）")
    void parseReplay_shouldFailCurrentMessage_whenSlicingKeepsFailing() {
        row.get().setEventType(DlqMessage.EVENT_PARSE);
        row.get().setPayload("{\"documentId\":901,\"filePath\":\"uploads/handbook.pdf\",\"fileType\":\"pdf\"}");
        when(parserService.parse(anyString(), anyString())).thenReturn("解析后的正文");
        clearInvocations(dlqMessageMapper);

        scheduler.retryDeadLetters();

        assertThat(row.get().getRetryCount()).isEqualTo(1);
        assertThat(row.get().getStatus()).isEqualTo(DlqMessage.STATUS_PENDING);
        assertThat(Duration.between(LocalDateTime.now(), row.get().getNextRetryAt()).getSeconds())
                .as("首次重试退避 9s（R31 口径）")
                .isBetween(7L, 11L);
        verify(dlqMessageMapper, never()).deleteById(anyLong());
        verify(dlqMessageMapper, never()).insert(any(DlqMessage.class));
        verify(textSplitter).split(anyString());
    }

    // ==================== 主链路语义不变 ====================

    @Test
    @DisplayName("★ 主链路语义不变：新上传文档仍走 @Async 事件入口，失败时入队一次 CHUNK 死信")
    void mainChain_shouldStayAsyncAndEnqueue_onFailure() {
        listener.handleDocumentParsed(new DocumentParsedEvent(this, DOCUMENT_ID, "正文内容"));

        verify(listenerDeadLetterQueue).enqueue(eq(DlqMessage.EVENT_CHUNK), eq(DOCUMENT_ID), anyString(), any());
        verify(documentMapper).updateStatus(DOCUMENT_ID, "FAILED");
    }

    @Test
    @DisplayName("★ 同步入口不得异步/不得自行入队；主链路入口保持 @Async + @EventListener")
    void syncEntry_shouldBeSynchronous_andMainEntryShouldStayAsync() throws Exception {
        Method main = ChunkEmbeddingBatchListener.class
                .getMethod("handleDocumentParsed", DocumentParsedEvent.class);
        assertThat(main.getAnnotation(Async.class))
                .as("主链路（新上传文档）必须保持异步，且线程池为 chunkExecutor")
                .isNotNull();
        assertThat(main.getAnnotation(Async.class).value()).isEqualTo("chunkExecutor");
        assertThat(main.getAnnotation(EventListener.class)).isNotNull();

        Method sync = ChunkEmbeddingBatchListener.class
                .getMethod("processDocumentParsed", DocumentParsedEvent.class);
        assertThat(sync.getAnnotation(Async.class))
                .as("DLQ 重放走的同步入口不得带 @Async（否则重放体又无法获知成败）")
                .isNull();

        // 同步入口失败时不得自行入队（环路的另一半）：入队只发生在主链路 catch 里
        listener.handleDocumentParsed(new DocumentParsedEvent(this, DOCUMENT_ID, "正文内容"));
        clearInvocations(listenerDeadLetterQueue);
        try {
            listener.processDocumentParsed(new DocumentParsedEvent(this, DOCUMENT_ID, "正文内容"));
            org.assertj.core.api.Assertions.fail("切片持续失败时同步入口必须上抛，交由调用方记录");
        } catch (RuntimeException expected) {
            // 预期：上抛给调用方（DlqRetryScheduler 记到当前消息上）
        }
        verify(listenerDeadLetterQueue, never()).enqueue(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("★ R30 结构性护栏：重放体不得再发布 DocumentParsedEvent（事件路径会绕过同步入口）")
    void replayBody_shouldNotPublishDocumentParsedEvent() {
        row.get().setEventType(DlqMessage.EVENT_PARSE);
        row.get().setPayload("{\"documentId\":901,\"filePath\":\"uploads/handbook.pdf\",\"fileType\":\"pdf\"}");
        when(parserService.parse(anyString(), anyString())).thenReturn("解析后的正文");

        scheduler.retryDeadLetters();

        verify(eventPublisher, never()).publishEvent(any(DocumentParsedEvent.class));
        verify(textSplitter).split(anyString());
    }
}
