package org.example.agent_qr.knowledge.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.example.agent_qr.common.rag.IndexableText;
import org.example.agent_qr.datasource.entity.DataSourceConfig;
import org.example.agent_qr.datasource.mapper.DataSourceMapper;
import org.example.agent_qr.knowledge.entity.Chunk;
import org.example.agent_qr.knowledge.mapper.ChunkMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChunkIndexableTextProvider} 分页（keyset）取数测试（批次 07 · 任务 7.3.3）。
 * <p>
 * 拦截的缺陷：{@code findAllIndexable()} 一次性全量加载，几十万条切片时把全部内容读进内存。
 * 分页版必须：按键集（{@code id > afterId}）逐页读取、带 LIMIT，且与全量版<b>同过滤口径</b>
 * （软删除 + 活跃数据源）——此处用渲染后的 SQL 片段固化口径。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChunkIndexableTextProviderPagingTest {

    @Mock
    private ChunkMapper chunkMapper;

    @Mock
    private DataSourceMapper dataSourceMapper;

    private ChunkIndexableTextProvider provider;

    /**
     * 初始化实体表信息缓存（运行期由 MyBatis-Plus 的 Mapper 注册完成），
     * 使其后可以渲染出真实 SQL 片段做断言。
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Chunk.class);
    }

    @BeforeEach
    void setUp() {
        provider = new ChunkIndexableTextProvider(chunkMapper, dataSourceMapper);
        when(dataSourceMapper.selectAllActive()).thenReturn(List.of(activeDatasource(2L)));
    }

    @Test
    @DisplayName("★ 分页查询是键集分页（id > afterId + LIMIT），不是 OFFSET、不是全量")
    void findIndexablePage_shouldUseKeysetAndLimit() {
        when(chunkMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(chunk(100L, "内容A"), chunk(101L, "内容B")));

        List<IndexableText> page = provider.findIndexablePage(99L, 2);

        LambdaQueryWrapper<Chunk> wrapper = capturedWrapper();
        String sql = wrapper.getTargetSql();

        assertThat(sql)
                .as("必须是键集（id > 游标）而不是 OFFSET 分页")
                .contains("id >")
                .doesNotContain("OFFSET");
        assertThat(sql)
                .as("软删除过滤必须保留（否则已删切片会漏进索引，问题 27）")
                .contains("deleted");
        assertThat(sql)
                .as("非活跃数据源的切片必须仍然排除")
                .contains("datasource_id");
        assertThat(lastSqlOf(wrapper))
                .as("必须带 LIMIT（否则仍是全量加载）")
                .contains("LIMIT 2");
        assertThat(page).extracting(IndexableText::getId).containsExactly(100L, 101L);
        assertThat(page).extracting(IndexableText::getContent).containsExactly("内容A", "内容B");
    }

    @Test
    @DisplayName("limit 非法值被兜底为至少 1，避免 LIMIT 0（会导致构建循环空转）")
    void findIndexablePage_shouldGuardLimit() {
        when(chunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        provider.findIndexablePage(0L, 0);

        assertThat(lastSqlOf(capturedWrapper())).contains("LIMIT 1");
    }

    @Test
    @DisplayName("没有活跃数据源时仍可查询（仅保留 datasource_id IS NULL，不生成非法的空 IN 子句）")
    void findIndexablePage_shouldWorkWithoutActiveDatasource() {
        when(dataSourceMapper.selectAllActive()).thenReturn(List.of());
        when(chunkMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(chunk(1L, "A")));

        List<IndexableText> page = provider.findIndexablePage(0L, 10);

        String sql = capturedWrapper().getTargetSql();
        assertThat(sql).contains("datasource_id IS NULL");
        assertThat(sql).as("空集合会生成非法的 IN ()）。").doesNotContain("IN (");
        assertThat(page).hasSize(1);
    }

    @Test
    @DisplayName("全量路径（findAllIndexable）保持可用——两条路径共用同一过滤口径")
    void findAllIndexable_shouldKeepFullLoadPath() {
        when(chunkMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(chunk(1L, "A"), chunk(2L, "B")));

        List<IndexableText> all = provider.findAllIndexable();

        assertThat(all).hasSize(2);
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<Chunk> capturedWrapper() {
        ArgumentCaptor<LambdaQueryWrapper<Chunk>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(chunkMapper).selectList(captor.capture());
        return captor.getValue();
    }

    /**
     * 读取 wrapper 的 {@code lastSql}（MP 未公开该值的 getter，用反射读取）。
     *
     * @param wrapper 查询条件
     * @return lastSql 文本
     */
    private static String lastSqlOf(LambdaQueryWrapper<Chunk> wrapper) {
        Object sharedString = ReflectionTestUtils.getField(wrapper, "lastSql");
        return sharedString == null ? "" : sharedString.toString();
    }

    private static Chunk chunk(Long id, String content) {
        Chunk chunk = new Chunk();
        chunk.setId(id);
        chunk.setContent(content);
        chunk.setChunkIndex(0);
        chunk.setDeleted(0);
        return chunk;
    }

    private static DataSourceConfig activeDatasource(Long id) {
        DataSourceConfig config = new DataSourceConfig();
        config.setId(id);
        return config;
    }
}
