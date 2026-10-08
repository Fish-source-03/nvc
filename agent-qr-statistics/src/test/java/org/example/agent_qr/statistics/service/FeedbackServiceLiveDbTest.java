package org.example.agent_qr.statistics.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.example.agent_qr.common.BusinessException;
import org.example.agent_qr.rag.entity.Conversation;
import org.example.agent_qr.rag.entity.Message;
import org.example.agent_qr.rag.mapper.ConversationMapper;
import org.example.agent_qr.rag.mapper.MessageMapper;
import org.example.agent_qr.statistics.entity.DailyStats;
import org.example.agent_qr.statistics.mapper.DailyStatsMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * {@link FeedbackService} 满意率计数的 <b>实库</b>测试（遗留项 R53）。
 * <p>
 * 为什么必须打真实 MySQL：R53 的缺陷形态是"<b>SQL 影响 0 行且不报错</b>"——
 * 服务代码、Mapper 接口、Mockito 全部"正常"，只有把"提交反馈"跑成真实的
 * INSERT/UPDATE 再回读 {@code stat_daily}，才能证明"计数正确落库"。
 * 修复前：当天无 {@code stat_daily} 行时 {@code UPDATE … WHERE stat_date=?} 静默影响 0 行
 * （实测反馈返回 200，表无今日行、无任何变化）——本类用例 ① 即为此缺陷的拦截网。
 * </p>
 * <p>
 * <b>数据基线保护</b>：整个测试类跑在一个 <b>autocommit=false 的会话</b>里，
 * 每个用例结束 {@code rollback()}——写入（含 chat_conversation/chat_message 夹具行）
 * 从不提交，{@code stat_daily} / {@code chat_*} 的已提交状态在测试前后逐项比对必须完全一致
 * （{@code @AfterAll} 硬断言 + 打印前后快照）。
 * 会话必须显式装配 {@link JdbcTransactionFactory}（原因见 {@code setUp} 注释：
 * 默认的 SpringManagedTransactionFactory 在无 Spring 事务时 commit/rollback 是空操作、
 * 连接保持 autocommit → 回滚失效、基线被写坏）。
 * 库不可达时按仓库既有实库用例的约定 {@link Assumptions#abort} 跳过。
 * </p>
 *
 * @author agent-qr
 */
class FeedbackServiceLiveDbTest {

    private static final LocalDate TODAY = LocalDate.now();

    /** 反馈提交者（＝会话 owner） */
    private static final long OWNER_ID = 7L;

    /** 他人（越权场景的消息 owner） */
    private static final long OTHER_USER_ID = 8L;

    private static SqlSession sqlSession;
    private static DailyStatsMapper dailyStatsMapper;
    private static MessageMapper messageMapper;
    private static ConversationMapper conversationMapper;
    private static FeedbackService service;

    /** 独立连接：只读已提交状态，用于基线前后比对（看不到测试会话的未提交写入） */
    private static Connection verifyConnection;

    private static long baselineStatDailyTotal;
    private static String baselineTodayRow;
    private static long baselineConversationTotal;
    private static long baselineMessageTotal;

    @BeforeAll
    static void setUp() throws Exception {
        String url = dataSourceProperty("url");
        String username = dataSourceProperty("username");
        String password = dataSourceProperty("password");
        Assumptions.assumeTrue(url != null, "application.yml 中未找到数据源配置，跳过实库测试");

        try {
            verifyConnection = DriverManager.getConnection(url, username, password);
        } catch (Exception e) {
            Assumptions.abort("MySQL 不可达（" + e.getClass().getSimpleName() + "），跳过实库测试");
            return;
        }

        DataSource dataSource = new UnpooledDataSource(
                "com.mysql.cj.jdbc.Driver", url, username, password);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        // ★ 必须显式指定 JdbcTransactionFactory：SqlSessionFactoryBean 默认用
        //   SpringManagedTransactionFactory，它在"无 Spring 事务"时 commit/rollback 均为空操作，
        //   而连接自身 autocommit=true → 每条语句立即提交、rollback 无法撤销（实测：
        //   夹具行与 stat_daily 新行全部落库，基线被破坏）。JdbcTransactionFactory 会真正
        //   setAutoCommit(false)，使 @AfterEach 的 rollback 生效、测试数据从不提交。
        factoryBean.setTransactionFactory(new JdbcTransactionFactory());
        SqlSessionFactory factory = factoryBean.getObject();
        factory.getConfiguration().addMapper(DailyStatsMapper.class);
        factory.getConfiguration().addMapper(MessageMapper.class);
        factory.getConfiguration().addMapper(ConversationMapper.class);
        // autocommit=false：所有写入在本类结束时随 rollback 消失（基线复原的机制保证）
        sqlSession = factory.openSession(false);

        dailyStatsMapper = sqlSession.getMapper(DailyStatsMapper.class);
        messageMapper = sqlSession.getMapper(MessageMapper.class);
        conversationMapper = sqlSession.getMapper(ConversationMapper.class);
        service = new FeedbackService(messageMapper, dailyStatsMapper, conversationMapper);

        // 前置防呆：若回滚机制失效（连接处于 autocommit），本类所有用例都不许跑
        assertThat(sqlSession.getConnection().getAutoCommit())
                .as("实库测试必须在非自动提交会话中运行，否则测试写入不可回滚、会破坏数据基线")
                .isFalse();

        baselineStatDailyTotal = count("SELECT COUNT(*) FROM stat_daily");
        baselineTodayRow = todayRowSnapshot();
        baselineConversationTotal = count("SELECT COUNT(*) FROM chat_conversation");
        baselineMessageTotal = count("SELECT COUNT(*) FROM chat_message");
        System.out.printf("[baseline-before] stat_daily=%d, 今日行=[%s], chat_conversation=%d, chat_message=%d%n",
                baselineStatDailyTotal, baselineTodayRow, baselineConversationTotal, baselineMessageTotal);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (sqlSession == null) {
            return;
        }
        // 收尾兜底：即使某用例断言失败也回滚，杜绝测试数据提交
        sqlSession.rollback();
        sqlSession.clearCache();

        long total = count("SELECT COUNT(*) FROM stat_daily");
        String todayRow = todayRowSnapshot();
        long conversations = count("SELECT COUNT(*) FROM chat_conversation");
        long messages = count("SELECT COUNT(*) FROM chat_message");
        System.out.printf("[baseline-after ] stat_daily=%d, 今日行=[%s], chat_conversation=%d, chat_message=%d%n",
                total, todayRow, conversations, messages);

        assertThat(total).as("stat_daily 总行数必须回到测试前").isEqualTo(baselineStatDailyTotal);
        assertThat(todayRow).as("今日行（含各计数器）必须回到测试前").isEqualTo(baselineTodayRow);
        assertThat(conversations).as("chat_conversation 总行数必须回到测试前").isEqualTo(baselineConversationTotal);
        assertThat(messages).as("chat_message 总行数必须回到测试前").isEqualTo(baselineMessageTotal);

        sqlSession.close();
        verifyConnection.close();
    }

    @BeforeEach
    void beginTransactionScope() {
        sqlSession.clearCache();
    }

    @AfterEach
    void rollbackTestWrites() {
        sqlSession.rollback();
        sqlSession.clearCache();
    }

    // ==================== ★ R53 主用例：今天无统计行 ====================

    @Test
    @DisplayName("★ R53①：当天无 stat_daily 行时点赞 → 建行，positive_count=1 正确落库（修复前静默丢失）")
    void submitFeedback_shouldPersistCount_whenNoStatsRowForToday() {
        Fixture fixture = conversationWithAssistantMessage(OWNER_ID);
        deleteTodayStatsRow();
        assertThat(dailyStatsMapper.selectByDate(TODAY)).as("前置：今天确无统计行").isNull();

        service.submitFeedback(fixture.messageId(), "positive", "有帮助", OWNER_ID);

        DailyStats row = dailyStatsMapper.selectByDate(TODAY);
        assertThat(row)
                .as("修复前此行为 null：UPDATE … WHERE stat_date=? 影响 0 行且不报错，计数静默丢失")
                .isNotNull();
        assertThat(row.getStatDate()).isEqualTo(TODAY);
        assertThat(row.getPositiveCount()).isEqualTo(1);
        assertThat(row.getNegativeCount()).isZero();

        Message message = messageMapper.selectById(fixture.messageId());
        assertThat(message.getFeedback()).isEqualTo("positive");
        assertThat(message.getFeedbackReason()).isEqualTo("有帮助");
    }

    @Test
    @DisplayName("★ R53①（点踩同形）：当天无行时点踩 → 建行，negative_count=1 正确落库")
    void submitFeedback_shouldPersistNegativeCount_whenNoStatsRowForToday() {
        Fixture fixture = conversationWithAssistantMessage(OWNER_ID);
        deleteTodayStatsRow();

        service.submitFeedback(fixture.messageId(), "negative", "答非所问", OWNER_ID);

        DailyStats row = dailyStatsMapper.selectByDate(TODAY);
        assertThat(row).isNotNull();
        assertThat(row.getNegativeCount()).isEqualTo(1);
        assertThat(row.getPositiveCount()).isZero();
    }

    @Test
    @DisplayName("★ R53：建行后当日后续反馈走累加（建行 → 累加衔接正确，不重复建行）")
    void subsequentFeedback_shouldAccumulateOnCreatedRow() {
        Fixture first = conversationWithAssistantMessage(OWNER_ID);
        Fixture second = conversationWithAssistantMessage(OWNER_ID);
        deleteTodayStatsRow();

        service.submitFeedback(first.messageId(), "positive", null, OWNER_ID);
        service.submitFeedback(second.messageId(), "negative", null, OWNER_ID);

        DailyStats row = dailyStatsMapper.selectByDate(TODAY);
        assertThat(row).as("当天只应有一行（stat_date 唯一键）").isNotNull();
        assertThat(row.getPositiveCount()).isEqualTo(1);
        assertThat(row.getNegativeCount()).isEqualTo(1);
    }

    // ==================== 回归：已有行累加 ====================

    @Test
    @DisplayName("★ R53②：当天已有统计行 → 只累加对应计数器，其余指标不被重置（回归）")
    void submitFeedback_shouldIncrementExistingRow_withoutResettingOtherCounters() {
        Fixture fixture = conversationWithAssistantMessage(OWNER_ID);
        replaceTodayStatsRowWith(5, 2, 1, 3);
        assertThat(dailyStatsMapper.selectByDate(TODAY).getNegativeCount()).isEqualTo(1);

        service.submitFeedback(fixture.messageId(), "negative", "答非所问", OWNER_ID);

        DailyStats row = dailyStatsMapper.selectByDate(TODAY);
        assertThat(row.getNegativeCount()).as("已有行必须累加").isEqualTo(2);
        assertThat(row.getPositiveCount()).as("对向计数器不受影响").isEqualTo(2);
        assertThat(row.getQaCount()).as("已有行的其它指标不得被重置").isEqualTo(5);
        assertThat(row.getDocUploadCount()).isEqualTo(3);
        assertThat(row.getUserQuestionCount()).isEqualTo(5);
        assertThat(row.getActiveUserCount()).isEqualTo(5);
    }

    // ==================== 回归：被拒反馈不写计数（R48） ====================

    @Test
    @DisplayName("★ R53③（R48 回归）：越权反馈被拒（403）→ 当天无行时也不得建行、消息不得被写")
    void rejectedCrossUserFeedback_shouldWriteNothing_whenNoStatsRowForToday() {
        Fixture othersMessage = conversationWithAssistantMessage(OTHER_USER_ID);
        deleteTodayStatsRow();

        BusinessException rejected = catchThrowableOfType(
                BusinessException.class,
                () -> service.submitFeedback(othersMessage.messageId(), "positive", "刷赞", OWNER_ID));

        assertThat(rejected).isNotNull();
        assertThat(rejected.getCode()).isEqualTo(403);
        assertThat(dailyStatsMapper.selectByDate(TODAY))
                .as("被拒的反馈不得建行（R48 的 fail-closed 不能被 R53 的建行分支绕过）")
                .isNull();
        assertThat(messageMapper.selectById(othersMessage.messageId()).getFeedback()).isNull();
    }

    @Test
    @DisplayName("★ R53③（R48 回归）：越权反馈被拒（403）→ 当天已有行时计数不得变动")
    void rejectedCrossUserFeedback_shouldNotTouchCounters_whenStatsRowExists() {
        Fixture othersMessage = conversationWithAssistantMessage(OTHER_USER_ID);
        replaceTodayStatsRowWith(5, 2, 1, 3);

        BusinessException rejected = catchThrowableOfType(
                BusinessException.class,
                () -> service.submitFeedback(othersMessage.messageId(), "negative", "刷差评", OWNER_ID));

        assertThat(rejected).isNotNull();
        assertThat(rejected.getCode()).isEqualTo(403);

        DailyStats row = dailyStatsMapper.selectByDate(TODAY);
        assertThat(row.getPositiveCount()).isEqualTo(2);
        assertThat(row.getNegativeCount()).isEqualTo(1);
        assertThat(messageMapper.selectById(othersMessage.messageId()).getFeedback()).isNull();
    }

    // ==================== 夹具与辅助 ====================

    /** 一条"会话 + AI 回答"夹具（写入当前事务，随 rollback 消失） */
    private record Fixture(Long conversationId, Long messageId) {
    }

    private Fixture conversationWithAssistantMessage(long ownerId) {
        Conversation conversation = new Conversation();
        conversation.setUserId(ownerId);
        conversation.setTitle("ZZ_R53_实库测试会话");
        conversation.setMessageCount(0);
        conversationMapper.insert(conversation);

        Message message = new Message();
        message.setConversationId(conversation.getId());
        message.setRole("assistant");
        message.setContent("ZZ_R53_这是 AI 回答");
        messageMapper.insert(message);
        return new Fixture(conversation.getId(), message.getId());
    }

    /** 删除"今天"的统计行（模拟"当天尚无任何问答"），DELETE 走 MyBatis 以同步清理本地缓存 */
    private static void deleteTodayStatsRow() {
        dailyStatsMapper.delete(Wrappers.<DailyStats>lambdaQuery().eq(DailyStats::getStatDate, TODAY));
        sqlSession.clearCache();
    }

    /** 收掉可能存在的今日行，重建一条计数已知的今日行 */
    private static void replaceTodayStatsRowWith(int qaCount, int positive, int negative, int docUpload) {
        deleteTodayStatsRow();
        DailyStats stats = new DailyStats();
        stats.setStatDate(TODAY);
        stats.setQaCount(qaCount);
        stats.setUserQuestionCount(qaCount);
        stats.setActiveUserCount(qaCount);
        stats.setDocUploadCount(docUpload);
        stats.setPositiveCount(positive);
        stats.setNegativeCount(negative);
        dailyStatsMapper.insert(stats);
        sqlSession.clearCache();
    }

    /** 已提交状态下的今日行快照（计数器逐项展开，用于基线前后逐字比对） */
    private static String todayRowSnapshot() throws Exception {
        try (Statement statement = verifyConnection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT id, qa_count, user_question_count, active_user_count, doc_upload_count,"
                             + " create_time, positive_count, negative_count"
                             + " FROM stat_daily WHERE stat_date = CURDATE()")) {
            if (!rs.next()) {
                return "无今日行";
            }
            StringBuilder snapshot = new StringBuilder("id=").append(rs.getLong("id"));
            for (String column : new String[]{"qa_count", "user_question_count", "active_user_count",
                    "doc_upload_count", "create_time", "positive_count", "negative_count"}) {
                snapshot.append(",").append(column).append("=").append(rs.getString(column));
            }
            return snapshot.toString();
        }
    }

    private static long count(String sql) throws Exception {
        try (Statement statement = verifyConnection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 从仓库 application.yml 读数据源配置（测试源码中不出现任何凭据原文） */
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
