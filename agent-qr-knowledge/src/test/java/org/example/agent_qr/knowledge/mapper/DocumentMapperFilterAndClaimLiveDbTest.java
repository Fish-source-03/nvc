package org.example.agent_qr.knowledge.mapper;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.example.agent_qr.knowledge.entity.Document;
import org.example.agent_qr.knowledge.enums.DocumentStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;


import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档筛选与"删除抢占"的实库测试（批次 09 · 任务 9.3 + 9.6）。
 * <p>
 * <b>为什么必须连真库</b>：两处修复都落在<b>手写注解 SQL</b> 上——
 * </p>
 * <ul>
 *   <li>9.3 的筛选查询是 {@code <script>} 动态 SQL，{@code <if>} 是否被正确解析、
 *       软删条件是否生效，只有真库执行才能确认（SQL 文本断言见
 *       {@link DocumentMapperFilterAndClaimSqlTest}）；</li>
 *   <li>9.6 的条件更新依赖数据库的<b>受影响行数</b>语义（MySQL 默认返回"命中行数"）——
 *       Mockito 里 {@code thenReturn(1/0)} 只是把假设写下来，真库才能验证"第二次抢占必然拿到 0"。</li>
 * </ul>
 * <p>
 * <b>数据基线保护</b>：全部测试数据以 {@code file_name LIKE 'zz_b09_live_%'} 标记，
 * 每个用例前与结束（含 @AfterAll）<b>物理删除</b>，不触碰既有的 kb_document 基线。
 * </p>
 *
 * @author agent-qr
 */
class DocumentMapperFilterAndClaimLiveDbTest {

    /** 测试数据标记（清理依据） */
    private static final String TEST_MARKER_PREFIX = "zz_b09_live_";

    private static final String DOMAIN_A = "ZZ_B09_HR";
    private static final String DOMAIN_B = "ZZ_B09_FIN";

    private static SqlSession sqlSession;
    private static DocumentMapper documentMapper;
    private static Connection verificationConnection;

    @BeforeAll
    static void setUp() throws Exception {
        String url = dataSourceProperty("url");
        String username = dataSourceProperty("username");
        String password = dataSourceProperty("password");
        Assumptions.assumeTrue(url != null, "application.yml 中未找到数据源配置，跳过实库测试");

        try {
            verificationConnection = DriverManager.getConnection(url, username, password);
        } catch (Exception e) {
            Assumptions.abort("MySQL 不可达（" + e.getClass().getSimpleName() + "），跳过实库测试");
            return;
        }

        DataSource dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", url, username, password);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        // 与生产 MybatisPlusConfig 一致的分页插件：验证自定义 @Select 能被正确改写（含 COUNT 查询）
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        factoryBean.setPlugins(interceptor);
        SqlSessionFactory factory = factoryBean.getObject();
        factory.getConfiguration().addMapper(DocumentMapper.class);
        sqlSession = factory.openSession(true);
        documentMapper = sqlSession.getMapper(DocumentMapper.class);

        cleanup();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (sqlSession == null) {
            return;
        }
        cleanup();
        long remaining = count("SELECT COUNT(*) FROM kb_document WHERE file_name LIKE '" + TEST_MARKER_PREFIX + "%'");
        System.out.printf("[baseline] 测试数据清理后残留 kb_document(zz_b09_live_*) = %d%n", remaining);
        assertThat(remaining).isZero();
        sqlSession.close();
        verificationConnection.close();
    }

    @BeforeEach
    void resetData() {
        cleanup();
    }

    // ==================== 9.3 列表筛选 ====================

    @Test
    @DisplayName("★ 按 domain 筛选：只返回该域文档（修复前参数被 Spring 静默丢弃）")
    void selectPageByFilter_shouldFilterByDomain() {
        seedDocument(DOMAIN_A, 1, DocumentStatus.READY);
        seedDocument(DOMAIN_A, 2, DocumentStatus.READY);
        seedDocument(DOMAIN_B, 1, DocumentStatus.READY);

        IPage<Document> page = documentMapper.selectPageByFilter(new Page<>(1, 50), DOMAIN_A, null);

        assertThat(page.getRecords())
                .extracting(Document::getDomain)
                .containsOnly(DOMAIN_A)
                .hasSize(2);
    }

    @Test
    @DisplayName("★ 按 sensitivityLevel 筛选：只返回该密级文档")
    void selectPageByFilter_shouldFilterBySensitivityLevel() {
        seedDocument(DOMAIN_A, 1, DocumentStatus.READY);
        seedDocument(DOMAIN_A, 3, DocumentStatus.READY);
        seedDocument(DOMAIN_B, 3, DocumentStatus.READY);

        IPage<Document> page = documentMapper.selectPageByFilter(new Page<>(1, 50), null, 3);

        assertThat(page.getRecords())
                .extracting(Document::getSensitivityLevel)
                .containsOnly(3)
                .hasSize(2);
    }

    @Test
    @DisplayName("★ 两个筛选条件同时生效（交集）")
    void selectPageByFilter_shouldCombineBothFilters() {
        seedDocument(DOMAIN_A, 1, DocumentStatus.READY);
        seedDocument(DOMAIN_A, 3, DocumentStatus.READY);
        seedDocument(DOMAIN_B, 3, DocumentStatus.READY);

        IPage<Document> page = documentMapper.selectPageByFilter(new Page<>(1, 50), DOMAIN_A, 3);

        assertThat(page.getRecords()).hasSize(1);
        assertThat(page.getRecords().get(0).getDomain()).isEqualTo(DOMAIN_A);
        assertThat(page.getRecords().get(0).getSensitivityLevel()).isEqualTo(3);
    }

    @Test
    @DisplayName("★ 已软删文档不出现在筛选结果中（@TableLogic 对手写 SQL 不生效，必须手写 deleted = 0）")
    void selectPageByFilter_shouldExcludeSoftDeletedRows() {
        Document live = seedDocument(DOMAIN_A, 1, DocumentStatus.READY);
        Document deleted = seedDocument(DOMAIN_A, 1, DocumentStatus.READY);
        documentMapper.softDelete(deleted.getId());

        IPage<Document> page = documentMapper.selectPageByFilter(new Page<>(1, 50), DOMAIN_A, null);

        assertThat(page.getRecords()).extracting(Document::getId).containsExactly(live.getId());
    }

    @Test
    @DisplayName("★ 分页插件对自定义查询生效（total 为筛选后的总数，而非全表）")
    void selectPageByFilter_shouldBePaginated() {
        seedDocument(DOMAIN_A, 1, DocumentStatus.READY);
        seedDocument(DOMAIN_A, 1, DocumentStatus.READY);
        seedDocument(DOMAIN_A, 1, DocumentStatus.READY);

        IPage<Document> firstPage = documentMapper.selectPageByFilter(new Page<>(1, 2), DOMAIN_A, null);

        assertThat(firstPage.getRecords()).hasSize(2);
        assertThat(firstPage.getTotal())
                .as("COUNT 查询也应带上筛选条件（MyBatis-Plus 依据原 SQL 生成）")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("回归：不传筛选条件时返回全部存活文档（既有列表行为）")
    void selectPageByFilter_shouldReturnAllLiveDocuments_withoutFilters() {
        Document a = seedDocument(DOMAIN_A, 1, DocumentStatus.READY);
        Document b = seedDocument(DOMAIN_B, 2, DocumentStatus.READY);

        IPage<Document> page = documentMapper.selectPageByFilter(new Page<>(1, 500), null, null);

        assertThat(page.getRecords()).extracting(Document::getId).contains(a.getId(), b.getId());
    }

    // ==================== 9.6 删除抢占 ====================

    @Test
    @DisplayName("★ 条件更新抢占：第一次成功（1），第二次失败（0），状态落库为 DELETING")
    void claimDeleting_shouldSucceedOnceOnly() {
        Document doc = seedDocument(DOMAIN_A, 1, DocumentStatus.READY);

        assertThat(documentMapper.claimDeleting(doc.getId()))
                .as("首次抢占成功")
                .isEqualTo(1);

        // 库内状态已置 DELETING（读出来核对）
        Document reloaded = documentMapper.selectById(doc.getId());
        assertThat(reloaded.getStatus()).isEqualTo(DocumentStatus.DELETING);

        assertThat(documentMapper.claimDeleting(doc.getId()))
                .as("并发/重复请求必须拿到 0 —— 这就是'只有一次生效'的数据库保证")
                .isZero();
    }

    @Test
    @DisplayName("★ 对已软删文档抢占失败（deleted = 0 条件阻止与软删请求互相覆盖）")
    void claimDeleting_shouldFail_forSoftDeletedDocument() {
        Document doc = seedDocument(DOMAIN_A, 1, DocumentStatus.READY);
        documentMapper.softDelete(doc.getId());

        assertThat(documentMapper.claimDeleting(doc.getId())).isZero();
    }

    @Test
    @DisplayName("★ 状态为 NULL 的行也能被抢占（status IS NULL 分支，避免漏占位）")
    void claimDeleting_shouldHandleNullStatus() throws Exception {
        Document doc = seedDocument(DOMAIN_A, 1, DocumentStatus.READY);
        try (PreparedStatement ps = verificationConnection.prepareStatement(
                "UPDATE kb_document SET status = NULL WHERE id = ?")) {
            ps.setLong(1, doc.getId());
            ps.executeUpdate();
        }

        assertThat(documentMapper.claimDeleting(doc.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("回归：updateStatus 语义未变（无条件更新，仍可对齐状态机写入）")
    void updateStatus_shouldRemainUnconditional() {
        Document doc = seedDocument(DOMAIN_A, 1, DocumentStatus.READY);
        documentMapper.claimDeleting(doc.getId());

        int affected = documentMapper.updateStatus(doc.getId(), DocumentStatus.FAILED.name());

        assertThat(affected).isEqualTo(1);
        assertThat(documentMapper.selectById(doc.getId()).getStatus()).isEqualTo(DocumentStatus.FAILED);
    }

    // ==================== 辅助 ====================

    private static Document seedDocument(String domain, int sensitivityLevel, DocumentStatus status) {
        Document doc = new Document();
        doc.setTitle("批次09实库测试");
        doc.setFileName(TEST_MARKER_PREFIX + System.nanoTime() + ".txt");
        doc.setFilePath("/tmp/" + doc.getFileName());
        doc.setFileType("txt");
        doc.setFileSize(10L);
        doc.setStatus(status);
        doc.setUploadUserId(1L);
        doc.setDomain(domain);
        doc.setSensitivityLevel(sensitivityLevel);
        doc.setSensitivityLabel("内部");
        doc.setDeleted(0);
        documentMapper.insert(doc);
        return doc;
    }

    private static long count(String sql) throws Exception {
        try (PreparedStatement ps = verificationConnection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 物理删除本轮测试写入的全部数据（含已软删的行） */
    private static void cleanup() {
        if (verificationConnection == null) {
            return;
        }
        try (PreparedStatement ps = verificationConnection.prepareStatement(
                "DELETE FROM kb_document WHERE file_name LIKE ?")) {
            ps.setString(1, TEST_MARKER_PREFIX + "%");
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("测试数据清理失败", e);
        }
    }

    private static String dataSourceProperty(String key) {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++) {
            Path candidate = dir.resolve(Path.of("agent-qr-web", "src", "main", "resources", "application.yml"));
            if (Files.exists(candidate)) {
                try {
                    String content = Files.readString(candidate, StandardCharsets.UTF_8);
                    Matcher matcher = Pattern.compile("(?m)^\\s*" + key + ":\\s*(\\S+)\\s*$").matcher(content);
                    if (!matcher.find()) {
                        return null;
                    }
                    return resolvePlaceholder(matcher.group(1));
                } catch (Exception e) {
                    return null;
                }
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static String resolvePlaceholder(String raw) {
        Matcher matcher = Pattern.compile("\\$\\{([A-Za-z0-9_]+):([^}]*)}").matcher(raw);
        if (matcher.matches()) {
            String fromEnv = System.getenv(matcher.group(1));
            return fromEnv != null ? fromEnv : matcher.group(2);
        }
        return raw;
    }
}
