package org.example.agent_qr.knowledge.service;

import org.example.agent_qr.auth.evaluator.AbacEvaluator;
import org.example.agent_qr.auth.principal.UserPrincipal;
import org.example.agent_qr.common.BusinessException;
import org.example.agent_qr.common.event.DocumentDeleteRequestedEvent;
import org.example.agent_qr.knowledge.entity.Document;
import org.example.agent_qr.knowledge.enums.DocumentStatus;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.example.agent_qr.knowledge.mapper.DocumentMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 文档删除的重复与并发保护测试（批次 09 · 任务 9.6，问题 42）。
 * <p>
 * <b>拦截的缺陷</b>：{@code requestDeleteDocument} 旧实现只校验"文档存在"与"ABAC 权限"，
 * 不看状态——重复点击删除/请求重试/并发请求都会再次发布 {@link DocumentDeleteRequestedEvent}，
 * 下游每收一次就建一条 {@code delete_task} 并再删一遍 ChromaDB 向量。
 * </p>
 * <p>
 * 覆盖点：
 * <ol>
 *   <li>DELETING 状态 → 拒绝（设计 §5.2.1）；</li>
 *   <li>已删除（DELETED / deleted=1）→ 拒绝；</li>
 *   <li>条件更新返回 0（读-写竞态窗口）→ 拒绝且不发事件；</li>
 *   <li>正常删除流程不受影响（回归）；</li>
 *   <li><b>并发两次只有一次生效</b>——用 CAS 模拟数据库的条件更新语义；</li>
 *   <li>ABAC 检查仍在状态校验之前生效（不得被本次修复削弱）。</li>
 * </ol>
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentCommandServiceDeleteGuardTest {

    private static final long DOC_ID = 42L;

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private ChunkMapper chunkMapper;

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private AbacEvaluator abacEvaluator;

    private DocumentCommandService service;

    @BeforeEach
    void setUp() {
        service = new DocumentCommandService(documentMapper, chunkMapper, fileStorageService,
                eventPublisher, abacEvaluator);

        UserPrincipal principal = new UserPrincipal();
        principal.setUserId(7L);
        principal.setUsername("chenming");
        principal.setRole("user");
        principal.setDepartment("HR");
        principal.setClearanceLevel(2);
        principal.setAllowedDomains(List.of("HR"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));

        when(abacEvaluator.canDeleteDocument(any(), any(), any())).thenReturn(true);
        when(chunkMapper.selectByDocumentId(anyLong())).thenReturn(List.of());
        when(chunkMapper.selectChromaIdsByDocumentId(anyLong())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ==================== 状态校验 ====================

    @Test
    @DisplayName("★ 对 DELETING 状态的文档再次发起删除 → 409 拒绝（设计 §5.2.1）")
    void requestDelete_shouldReject_whenStatusIsDeleting() {
        when(documentMapper.selectById(DOC_ID)).thenReturn(document(DocumentStatus.DELETING, 0));

        assertThatThrownBy(() -> service.requestDeleteDocument(DOC_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("正在删除中");

        verify(documentMapper, never()).claimDeleting(anyLong());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("★ 对已删除（deleted=1）的文档发起删除 → 409 拒绝")
    void requestDelete_shouldReject_whenDocumentAlreadyDeleted() {
        when(documentMapper.selectById(DOC_ID)).thenReturn(document(DocumentStatus.READY, 1));

        assertThatThrownBy(() -> service.requestDeleteDocument(DOC_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已删除");

        verify(documentMapper, never()).claimDeleting(anyLong());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("文档不存在（含被 @TableLogic 过滤掉的软删文档）→ 404")
    void requestDelete_shouldReject_whenDocumentMissing() {
        when(documentMapper.selectById(DOC_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.requestDeleteDocument(DOC_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文档不存在");

        verifyNoInteractions(eventPublisher);
    }

    // ==================== 条件更新抢占 ====================

    @Test
    @DisplayName("★ 条件更新影响行数为 0（被并发请求抢先）→ 拒绝且不发布事件")
    void requestDelete_shouldReject_whenConditionalUpdateLoses() {
        when(documentMapper.selectById(DOC_ID)).thenReturn(document(DocumentStatus.READY, 0));
        when(documentMapper.claimDeleting(DOC_ID)).thenReturn(0);

        assertThatThrownBy(() -> service.requestDeleteDocument(DOC_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("正在删除中");

        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("正常删除流程：抢占成功后收集关联信息并发布一次事件（回归）")
    void requestDelete_shouldSucceed_onHappyPath() {
        when(documentMapper.selectById(DOC_ID)).thenReturn(document(DocumentStatus.READY, 0));
        when(documentMapper.claimDeleting(DOC_ID)).thenReturn(1);
        when(chunkMapper.selectByDocumentId(DOC_ID)).thenReturn(List.of());
        when(chunkMapper.selectChromaIdsByDocumentId(DOC_ID)).thenReturn(List.of("vec-1"));

        service.requestDeleteDocument(DOC_ID);

        verify(documentMapper).claimDeleting(DOC_ID);
        verify(eventPublisher, times(1)).publishEvent(any(DocumentDeleteRequestedEvent.class));
        verify(documentMapper, never()).updateStatus(anyLong(), eq(DocumentStatus.DELETING.name()));
    }

    @Test
    @DisplayName("★ 并发发起两次删除：只有一次成功，只发布一次删除事件")
    void requestDelete_shouldAllowOnlyOneWinner_underConcurrency() throws Exception {
        // 两个线程都读到 READY（模拟读-判断-写竞态窗口），胜负只由条件更新决定
        when(documentMapper.selectById(DOC_ID)).thenAnswer(invocation -> document(DocumentStatus.READY, 0));
        AtomicBoolean claimed = new AtomicBoolean(false);
        when(documentMapper.claimDeleting(DOC_ID))
                .thenAnswer(invocation -> claimed.compareAndSet(false, true) ? 1 : 0);

        // SecurityContextHolder 是 ThreadLocal —— 线程池中的线程拿不到主线程的认证信息，
        // 必须在线程内自行设置（否则两路都会因"无法获取当前用户"而失败，测试失去意义）
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Callable<Void> task = () -> {
            SecurityContextHolder.getContext().setAuthentication(authentication);
            try {
                start.await();
                service.requestDeleteDocument(DOC_ID);
                succeeded.incrementAndGet();
            } catch (BusinessException e) {
                rejected.incrementAndGet();
            } finally {
                SecurityContextHolder.clearContext();
            }
            return null;
        };

        List<Future<Void>> futures = List.of(pool.submit(task), pool.submit(task));
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        for (Future<Void> future : futures) {
            future.get(5, TimeUnit.SECONDS);   // 线程内的意外异常在此浮现
        }

        assertThat(succeeded.get()).as("并发下只能有一个请求抢占成功").isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        verify(eventPublisher, times(1)).publishEvent(any(DocumentDeleteRequestedEvent.class));
    }

    // ==================== ABAC 不可被削弱 ====================

    @Test
    @DisplayName("★ ABAC 拒绝时不得抢占状态，也不得发布事件（权限检查与状态检查并存）")
    void requestDelete_shouldKeepAbacCheck_beforeClaiming() {
        when(documentMapper.selectById(DOC_ID)).thenReturn(document(DocumentStatus.READY, 0));
        when(abacEvaluator.canDeleteDocument(any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.requestDeleteDocument(DOC_ID))
                .isInstanceOf(AccessDeniedException.class);

        verify(documentMapper, never()).claimDeleting(anyLong());
        verifyNoInteractions(eventPublisher);
    }

    // ==================== 辅助 ====================

    private static Document document(DocumentStatus status, int deleted) {
        Document doc = new Document();
        doc.setId(DOC_ID);
        doc.setTitle("员工手册");
        doc.setFilePath("/uploads/x.pdf");
        doc.setDomain("HR");
        doc.setSensitivityLevel(1);
        doc.setStatus(status);
        doc.setDeleted(deleted);
        return doc;
    }
}
