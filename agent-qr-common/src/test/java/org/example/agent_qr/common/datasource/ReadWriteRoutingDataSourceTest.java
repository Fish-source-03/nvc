package org.example.agent_qr.common.datasource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ReadWriteRoutingDataSource} 路由键测试（批次 02 · 任务 2.3，问题 05）。
 * <p>
 * 拦截的核心缺陷：全仓库无任何方法把只读标志写入 ThreadLocal，
 * 导致 {@code determineCurrentLookupKey()} 恒返回 {@code "write"}，读库永远不被命中。
 * </p>
 *
 * @author agent-qr
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReadWriteRoutingDataSourceTest {

    private final ReadWriteRoutingDataSource routingDataSource =
            new ReadWriteRoutingDataSource(mock(DataSource.class), mock(DataSource.class));

    @AfterEach
    void clearThreadLocal() {
        // ThreadLocal 是静态的，用例之间必须清理，避免相互污染
        ReadWriteRoutingDataSource.clear();
    }

    @Test
    @DisplayName("readOnly=true 时路由键应为 read")
    void determineCurrentLookupKey_shouldReturnRead_whenReadOnlyFlagSet() {
        ReadWriteRoutingDataSource.setReadOnly(true);

        assertThat(routingDataSource.determineCurrentLookupKey()).isEqualTo("read");
    }

    @Test
    @DisplayName("readOnly=false 时路由键应为 write")
    void determineCurrentLookupKey_shouldReturnWrite_whenReadOnlyFlagSetFalse() {
        ReadWriteRoutingDataSource.setReadOnly(false);

        assertThat(routingDataSource.determineCurrentLookupKey()).isEqualTo("write");
    }

    @Test
    @DisplayName("未设置标志（未标注 @Transactional 的方法）默认路由到 write")
    void determineCurrentLookupKey_shouldReturnWrite_whenFlagUnset() {
        assertThat(routingDataSource.determineCurrentLookupKey()).isEqualTo("write");
    }

    @Test
    @DisplayName("clear() 后应回落到 write，证明 ThreadLocal 被真正清理")
    void determineCurrentLookupKey_shouldReturnWrite_afterClear() {
        ReadWriteRoutingDataSource.setReadOnly(true);
        assertThat(routingDataSource.determineCurrentLookupKey()).isEqualTo("read");

        ReadWriteRoutingDataSource.clear();

        assertThat(routingDataSource.determineCurrentLookupKey()).isEqualTo("write");
    }

    @Test
    @DisplayName("只读标志为真时，取连接应真的落到读库数据源（路由键与 targetDataSources 的 key 必须对齐）")
    void getConnection_shouldDelegateToReadDataSource_whenReadOnly() throws Exception {
        DataSource write = mock(DataSource.class);
        DataSource read = mock(DataSource.class);
        ReadWriteRoutingDataSource routing = new ReadWriteRoutingDataSource(write, read);
        routing.initDataSources();

        ReadWriteRoutingDataSource.setReadOnly(true);
        routing.getConnection();

        verify(read).getConnection();
        verify(write, never()).getConnection();
    }

    @Test
    @DisplayName("未设置只读标志时，取连接应落到写库数据源")
    void getConnection_shouldDelegateToWriteDataSource_whenNotReadOnly() throws Exception {
        DataSource write = mock(DataSource.class);
        DataSource read = mock(DataSource.class);
        ReadWriteRoutingDataSource routing = new ReadWriteRoutingDataSource(write, read);
        routing.initDataSources();

        routing.getConnection();

        verify(write).getConnection();
        verify(read, never()).getConnection();
    }
}
