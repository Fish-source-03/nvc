package org.example.agent_qr.web.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 运维/告警通知推送器（批次 10 · 任务 10.2.4，问题 34 场景②）。
 * <p>
 * 把数据源同步失败、质检阻断、DLQ 积压等运维事件推送到<b>运维频道</b>
 * {@value #OPS_TOPIC}。频道为广播目的地，但只承载<b>运维元信息</b>
 * （批次/数据源/数量/原因），不含任何用户数据；订阅侧由
 * {@code StompAuthChannelInterceptor} 强制校验 <b>ROLE_ADMIN</b>。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpsAlertNotifier {

    /** 运维告警频道（与 StompAuthChannelInterceptor.OPS_TOPIC 一致） */
    public static final String OPS_TOPIC = "/topic/ops.alerts";

    /** 消息类型标识 */
    public static final String TYPE_OPS_ALERT = "OPS_ALERT";

    /** 告警级别：需要立即处理 */
    public static final String LEVEL_CRITICAL = "CRITICAL";

    /** 告警级别：需要关注 */
    public static final String LEVEL_WARNING = "WARNING";

    /** 告警类型：数据源同步失败 */
    public static final String ALERT_SYNC_FAILED = "DATASOURCE_SYNC_FAILED";

    /** 告警类型：质检阻断 */
    public static final String ALERT_QUALITY_BLOCKED = "QUALITY_BLOCKED";

    /** 告警类型：DLQ 积压 */
    public static final String ALERT_DLQ_BACKLOG = "DLQ_BACKLOG";

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 推送一条运维告警。
     *
     * @param level   级别（{@link #LEVEL_CRITICAL} / {@link #LEVEL_WARNING}）
     * @param type    类型（{@link #ALERT_SYNC_FAILED} 等）
     * @param message 人可读描述
     * @param details 附加信息（可空；仅放运维元信息，禁止放入用户数据）
     */
    public void notifyAlert(String level, String type, String message, Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", TYPE_OPS_ALERT);
        payload.put("level", level);
        payload.put("alertType", type);
        payload.put("message", message);
        if (details != null && !details.isEmpty()) {
            payload.put("details", details);
        }
        payload.put("timestamp", LocalDateTime.now().toString());

        messagingTemplate.convertAndSend(OPS_TOPIC, payload);
        log.warn("运维告警已推送: level={}, alertType={}, message={}", level, type, message);
    }
}
