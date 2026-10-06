package org.example.agent_qr.datasource.connector;

import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.datasource.dto.ConnectionTestResult;
import org.example.agent_qr.datasource.dto.SyncContext;
import org.example.agent_qr.datasource.dto.SyncResult;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API 数据源连接器。
 * <p>
 * 通过 REST API 获取外部系统数据，支持分页循环拉取和
 * 响应头 X-Next-Cursor 驱动的游标翻页。
 * </p>
 *
 * <h3>connectionConfig key 约定（批次 05 · 任务 5.1.8）</h3>
 * <table border="1">
 *   <caption>连接配置键</caption>
 *   <tr><th>键</th><th>必填</th><th>类型</th><th>说明</th></tr>
 *   <tr><td>{@code baseUrl}</td><td>是</td><td>String</td><td>服务基地址</td></tr>
 *   <tr><td>{@code endpoint}</td><td>否</td><td>String</td><td>数据端点，默认 {@code /data}</td></tr>
 *   <tr><td>{@code cursorParam}</td><td>否</td><td>String</td><td>增量起始游标的查询参数名，默认 {@code since}</td></tr>
 *   <tr><td>{@code maxPages}</td><td>否</td><td>Number</td><td>翻页安全上限，默认 {@code 100}；命中即标记截断</td></tr>
 * </table>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class RestApiConnector implements DataSourceConnector {

    /** 翻页安全上限的缺省值（缺省值之外允许通过 maxPages 配置覆盖） */
    static final int DEFAULT_MAX_PAGES = 100;

    /** 全量分页使用的游标查询参数名 */
    private static final String FULL_SYNC_CURSOR_PARAM = "cursor";

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String getType() {
        return "REST";
    }

    @Override
    public ConnectionTestResult testConnection(Map<String, Object> config) {
        String baseUrl = (String) config.get("baseUrl");
        long start = System.currentTimeMillis();
        try {
            ResponseEntity<Void> response = restTemplate.exchange(
                    baseUrl, HttpMethod.HEAD, null, Void.class);
            long latency = System.currentTimeMillis() - start;
            boolean success = response.getStatusCode().is2xxSuccessful();
            if (success) {
                return ConnectionTestResult.ok(latency, "REST API", null);
            }
            return ConnectionTestResult.fail("HTTP 状态码: " + response.getStatusCodeValue());
        } catch (Exception e) {
            log.error("REST API 连接测试失败: url={}, error={}", baseUrl, e.getMessage());
            return ConnectionTestResult.fail(e.getMessage());
        }
    }

    @Override
    public SyncResult fullSync(SyncContext context) {
        Map<String, Object> config = context.getConfig();
        String baseUrl = (String) config.get("baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            // 批次 05 · 任务 5.1.8：必填项显式校验（而非拼出 "null/data" 这种不可读的失败）
            log.warn("REST 全量同步：缺少必填配置 baseUrl");
            return SyncResult.failure("REST 全量同步：缺少必填配置 baseUrl", List.of(), null);
        }
        int maxPages = resolveMaxPages(config);

        try {
            PageOutcome outcome = fetchPages(config, null, FULL_SYNC_CURSOR_PARAM, true, maxPages);
            log.info("REST API 全量同步: 读取 {} 行, 共 {} 页, 尾游标={}",
                    outcome.rows.size(), outcome.pages, outcome.lastCursor);

            // 批次 05 · 任务 5.1.6：全量完成后返回真实游标，
            // 使下一次同步能进入增量模式（此前固定返回 null，增量路径实际不可达）
            //
            // ⚠️ 已知语义边界（返工评估项）：X-Next-Cursor 的语义是「取下一页所需的游标」，
            // 因此 outcome.lastCursor 是**请求最后一页时所用的游标**（即最后一页的起点），
            // 而不是末端游标——上游用「空游标头」表示流结束，连接器拿不到「末端游标」。
            // 后果：下一轮增量会重复拉取最后 1 页（而非整库）。
            // 取舍依据见 JdbcConnector/RestApiConnector 测试
            // RestApiConnectorTest#fullSyncThenIncremental_shouldReplayFinalPage_knownLimitation：
            //   · 备选方案「返回 null」= 回退到 5.1.6 修复前的缺陷（永远全量），严格更差；
            //   · 重复量为「至多 1 页」，且下游 ETL 已用 record_hash 指纹 + 去重规则
            //     + DuplicateCleanupScanner 兜底（跨批次去重正是为此设计）。
            SyncResult result = new SyncResult(outcome.rows.size(), outcome.rows, outcome.lastCursor);
            if (outcome.truncated) {
                String reason = String.format("REST 全量同步命中 maxPages=%d 上限，结果被截断", maxPages);
                log.warn("{}, baseUrl={}, 已读取 {} 行", reason, baseUrl, outcome.rows.size());
                result.markTruncated(reason);
            }
            return result;
        } catch (Exception e) {
            log.error("REST API 全量同步失败: baseUrl={}, error={}", baseUrl, e.getMessage(), e);
            return SyncResult.failure("REST API 全量同步失败: " + e.getMessage(), List.of(), null);
        }
    }

    @Override
    public SyncResult incrementalSync(SyncContext context, String lastCursor) {
        Map<String, Object> config = context.getConfig();
        String baseUrl = (String) config.get("baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("REST 增量同步：缺少必填配置 baseUrl");
            return SyncResult.failure("REST 增量同步：缺少必填配置 baseUrl", List.of(), lastCursor);
        }
        String cursorParam = (String) config.getOrDefault("cursorParam", "since");
        int maxPages = resolveMaxPages(config);

        try {
            // 批次 05 · 任务 5.1.5：增量复用全量的翻页循环（X-Next-Cursor 驱动的多页拉取）
            PageOutcome outcome = fetchPages(config, lastCursor, cursorParam, false, maxPages);
            String newCursor = outcome.lastCursor != null ? outcome.lastCursor : lastCursor;
            log.info("REST API 增量同步: 读取 {} 行, 共 {} 页, 新游标={}",
                    outcome.rows.size(), outcome.pages, newCursor);

            SyncResult result = new SyncResult(outcome.rows.size(), outcome.rows, newCursor);
            if (outcome.truncated) {
                String reason = String.format("REST 增量同步命中 maxPages=%d 上限，结果被截断", maxPages);
                log.warn("{}, baseUrl={}, 已读取 {} 行", reason, baseUrl, outcome.rows.size());
                result.markTruncated(reason);
            }
            return result;
        } catch (Exception e) {
            log.error("REST API 增量同步失败: baseUrl={}, error={}", baseUrl, e.getMessage(), e);
            return SyncResult.failure("REST API 增量同步失败: " + e.getMessage(), List.of(), lastCursor);
        }
    }

    /**
     * 分页拉取（全量与增量共用的翻页逻辑）。
     * <p>
     * 翻页终止条件与原点实现保持一致：
     * <ol>
     *   <li>响应头 {@code X-Next-Cursor} 存在且为空 → 结束；</li>
     *   <li>响应头缺失且响应体为空 → 结束；</li>
     *   <li>到达 {@code maxPages} → 结束并标记截断（非静默）。</li>
     * </ol>
     * </p>
     *
     * @param config        连接配置
     * @param initialCursor 起始游标（全量传 null）
     * @param cursorParam   游标查询参数名
     * @param pageFallback  起始游标为 null 时是否回退为 {@code ?page=N} 形式
     * @param maxPages      翻页上限
     * @return 分页结果
     */
    @SuppressWarnings("unchecked")
    private PageOutcome fetchPages(Map<String, Object> config, String initialCursor,
                                   String cursorParam, boolean pageFallback, int maxPages) {
        String baseUrl = (String) config.get("baseUrl");
        String endpoint = (String) config.getOrDefault("endpoint", "/data");

        PageOutcome outcome = new PageOutcome();
        String cursor = initialCursor;
        int page = 0;
        boolean stoppedCleanly = false;

        while (page < maxPages) {
            String url = baseUrl + endpoint;
            if (cursor != null) {
                url += "?" + cursorParam + "=" + cursor;
            } else if (pageFallback) {
                url += "?page=" + page;
            } else {
                url += "?" + cursorParam + "=";
            }

            ResponseEntity<List> response = restTemplate.getForEntity(url, List.class);
            if (response.getBody() != null) {
                for (Object item : response.getBody()) {
                    if (item instanceof Map) {
                        outcome.rows.add(new LinkedHashMap<>((Map<String, Object>) item));
                    }
                }
            }

            // 从响应头获取下一页游标
            List<String> cursorHeaders = response.getHeaders().get("X-Next-Cursor");
            if (cursorHeaders != null && !cursorHeaders.isEmpty()) {
                String next = cursorHeaders.get(0);
                if (next == null || next.isEmpty()) {
                    stoppedCleanly = true;
                    break;
                }
                cursor = next;
                outcome.lastCursor = next;
            } else {
                // 无游标头，检查返回数据量是否为空
                if (response.getBody() == null || response.getBody().isEmpty()) {
                    stoppedCleanly = true;
                    break;
                }
            }
            page++;
        }

        outcome.pages = page;
        outcome.truncated = !stoppedCleanly;
        return outcome;
    }

    /**
     * 解析翻页上限：{@code maxPages} 可配置（批次 05 · 任务 5.1.7）。
     *
     * @param config 连接配置
     * @return 翻页上限，非法值回退 {@link #DEFAULT_MAX_PAGES}
     */
    static int resolveMaxPages(Map<String, Object> config) {
        Object configured = config == null ? null : config.get("maxPages");
        if (configured instanceof Number number && number.intValue() > 0) {
            return number.intValue();
        }
        return DEFAULT_MAX_PAGES;
    }

    /**
     * 分页拉取的中间结果。
     */
    private static final class PageOutcome {
        /** 累计行 */
        private final List<Map<String, Object>> rows = new ArrayList<>();
        /** 最后一次从 X-Next-Cursor 读到的非空游标 */
        private String lastCursor;
        /** 实际执行的页数 */
        private int pages;
        /** 是否因命中 maxPages 而被截断 */
        private boolean truncated;
    }
}
