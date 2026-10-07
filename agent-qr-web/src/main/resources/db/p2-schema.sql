
-- ============================================================
-- P2 阶段数据库变更脚本
-- 汇聚全部 P2 DDL (6 CREATE TABLE + 5 ALTER TABLE)
-- ============================================================

-- ── 1. 死信队列表 ──
CREATE TABLE IF NOT EXISTS dlq_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型：PARSE/CHUNK/EMBED/DELETE',
    document_id BIGINT COMMENT '关联文档ID',
    payload TEXT COMMENT '原始负载(JSON)',
    error_msg TEXT COMMENT '错误信息',
    retry_count INT DEFAULT 0 COMMENT '重试次数',
    next_retry_at DATETIME COMMENT '下次重试时间',
    status VARCHAR(16) DEFAULT 'PENDING' COMMENT 'PENDING/DEAD',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_status_next_retry (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='死信队列表';

-- ── 2. Refresh Token 表 ──
CREATE TABLE IF NOT EXISTS token_refresh (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    token TEXT NOT NULL COMMENT 'Refresh Token',
    revoked TINYINT(1) DEFAULT 0 COMMENT '是否已撤销',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    expire_time DATETIME NOT NULL COMMENT '过期时间',
    INDEX idx_token (token(255)),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Refresh Token表';

-- ── 3. 删除任务表 ──
CREATE TABLE IF NOT EXISTS delete_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL COMMENT '文档ID',
    chroma_ids TEXT COMMENT 'ChromaDB向量ID列表(JSON数组)',
    status VARCHAR(16) DEFAULT 'PENDING' COMMENT 'PENDING/DONE/FAILED',
    retry_count INT DEFAULT 0 COMMENT '重试次数',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_document_id (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='删除任务表';

-- ── 4. 数据源配置表 ──
CREATE TABLE IF NOT EXISTS data_source_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_name VARCHAR(128) NOT NULL COMMENT '数据源名称',
    source_type VARCHAR(16) NOT NULL COMMENT '数据源类型：JDBC/REST/S3',
    domain VARCHAR(32) COMMENT '所属业务域',
    sync_strategy VARCHAR(16) DEFAULT 'FULL' COMMENT 'FULL/INCREMENTAL',
    cursor_field VARCHAR(64) COMMENT '增量游标字段',
    last_cursor VARCHAR(255) COMMENT '上次游标值',
    connection_config TEXT COMMENT '连接配置(JSON)',
    field_mapping TEXT COMMENT '字段映射配置(JSON)',
    status VARCHAR(16) DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE/ERROR',
    total_synced INT DEFAULT 0 COMMENT '累计同步数',
    last_sync_at DATETIME COMMENT '上次同步时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_domain (domain),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据源配置表';

-- ── 4b. 数据源同步历史记录表 ★ ──
CREATE TABLE IF NOT EXISTS sync_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    datasource_id BIGINT NOT NULL COMMENT '数据源配置 ID',
    sync_strategy VARCHAR(20) NOT NULL DEFAULT 'FULL' COMMENT '同步策略：FULL/INCREMENTAL',
    total_rows INT NOT NULL DEFAULT 0 COMMENT '本次同步行数',
    next_cursor VARCHAR(255) COMMENT '增量同步游标（下次同步起点）',
    status VARCHAR(20) NOT NULL DEFAULT 'SUCCESS' COMMENT '同步状态：SUCCESS/FAILED/PARTIAL',
    error_msg TEXT COMMENT '失败错误信息',
    sync_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '同步完成时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    INDEX idx_datasource_id (datasource_id),
    INDEX idx_sync_time (sync_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据源同步历史记录';

-- ── 5. 切片结构化字段表 ──
CREATE TABLE IF NOT EXISTS kb_chunk_structured (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    chunk_id BIGINT NOT NULL COMMENT '切片ID',
    domain VARCHAR(32) COMMENT '业务域',
    field_name VARCHAR(64) COMMENT '字段名',
    field_value VARCHAR(255) COMMENT '字段值',
    numeric_value DECIMAL(18,4) COMMENT '数值型值',
    date_value DATE COMMENT '日期型值',
    field_type VARCHAR(16) COMMENT 'NUMBER/DATE/ENUM/STRING',
    INDEX idx_domain_field_number (domain, field_name, numeric_value),
    INDEX idx_domain_field_date (domain, field_name, date_value),
    INDEX idx_chunk_id (chunk_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='切片结构化字段表';

-- ── ALTER 1: sys_user 新增 ABAC 字段 ──
-- 使用存储过程实现 IF NOT EXISTS 的幂等性

DELIMITER $$

DROP PROCEDURE IF EXISTS p2_add_column$$
CREATE PROCEDURE p2_add_column(
    IN tbl VARCHAR(128), IN col VARCHAR(128), IN col_def TEXT
)
BEGIN
    DECLARE cnt INT DEFAULT 0;
    SELECT COUNT(*) INTO cnt FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = tbl
       AND COLUMN_NAME = col;
    IF cnt = 0 THEN
        SET @ddl = CONCAT('ALTER TABLE ', tbl, ' ADD COLUMN ', col, ' ', col_def);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DROP PROCEDURE IF EXISTS p2_add_index$$
CREATE PROCEDURE p2_add_index(
    IN tbl VARCHAR(128), IN idx VARCHAR(128), IN idx_def TEXT
)
BEGIN
    DECLARE cnt INT DEFAULT 0;
    SELECT COUNT(*) INTO cnt FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = tbl
       AND INDEX_NAME = idx;
    IF cnt = 0 THEN
        SET @ddl = CONCAT('ALTER TABLE ', tbl, ' ADD INDEX ', idx, ' ', idx_def);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

DELIMITER ;

-- sys_user
CALL p2_add_column('sys_user', 'department',       "VARCHAR(32) COMMENT '所属部门'");
CALL p2_add_column('sys_user', 'clearance_level',   "INT DEFAULT 0 COMMENT '数据密级'");
CALL p2_add_column('sys_user', 'allowed_domains',   "VARCHAR(512) COMMENT '允许访问的业务域(逗号分隔)'");
CALL p2_add_column('sys_user', 'title',             "VARCHAR(32) COMMENT '职级'");

-- kb_document
CALL p2_add_column('kb_document', 'domain',             "VARCHAR(32) COMMENT '所属业务域'");
CALL p2_add_column('kb_document', 'sensitivity_level',  "INT DEFAULT 0 COMMENT '敏感级别'");
CALL p2_add_column('kb_document', 'sensitivity_label',  "VARCHAR(16) DEFAULT '公开' COMMENT '敏感级别标签'");
CALL p2_add_column('kb_document', 'deleted',            "INT DEFAULT 0 COMMENT '软删除标记'");
CALL p2_add_index('kb_document',  'idx_domain',         '(domain)');
CALL p2_add_index('kb_document',  'idx_deleted',        '(deleted)');

-- kb_chunk
-- 批次 07 · 任务 7.0.3：切片写入时"已入库"应表达为 INDEXED（BM25 可搜、向量未写），
-- 而非 READY（READY 语义收紧为"向量已写入 ChromaDB"）。
CALL p2_add_column('kb_chunk', 'status',  "VARCHAR(16) DEFAULT 'INDEXED' COMMENT '切片状态：PENDING/INDEXED/READY'");
CALL p2_add_column('kb_chunk', 'deleted', "INT DEFAULT 0 COMMENT '软删除标记'");
CALL p2_add_index('kb_chunk',  'idx_deleted', '(deleted)');
CALL p2_add_index('kb_chunk',  'idx_status',  '(status)');

-- kb_chunk: 表格结构化切片元数据（批次 06 · 任务 6.1.4，问题 10）
-- ⚠️ 当前为"预留字段"：**无写入方、无读取方**（批次 11 收尾清单 R39 的明确标注，不留含糊状态）——
--    运行库实测 content_type 全为默认值 'TEXT'、table_caption 全为 NULL；
--    闭环需要改 ChunkEmbeddingBatchListener / DataSyncEtlListener / ChunkMapper#insertBatch（列清单）
--    与检索侧消费（详见 Chunk 实体中 contentType / tableCaption 的 javadoc）。
-- content_type：TEXT / TABLE / TABLE_FRAGMENT / MIXED
CALL p2_add_column('kb_chunk', 'content_type',  "VARCHAR(16) DEFAULT 'TEXT' COMMENT '切片内容类型：TEXT/TABLE/TABLE_FRAGMENT/MIXED（预留字段：当前无写入方/读取方，见 R39）'");
CALL p2_add_column('kb_chunk', 'table_caption', "VARCHAR(512) COMMENT '表格标题/上下文描述（预留字段：当前无写入方/读取方，见 R39）'");

-- kb_chunk.status 默认值修正（对已存在的库生效；p2_add_column 对已存在的列直接跳过）
SET @sql = (SELECT IF(COUNT(*) > 0,
    'ALTER TABLE kb_chunk MODIFY COLUMN status VARCHAR(16) DEFAULT ''INDEXED'' COMMENT ''切片状态：PENDING/INDEXED/READY''',
    'SELECT 1')
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'kb_chunk'
  AND COLUMN_NAME = 'status');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- kb_chunk: datasource_id（幂等，列存在时跳过）
SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE kb_chunk ADD COLUMN datasource_id BIGINT COMMENT ''所属数据源ID（数据同步管线）''',
    'SELECT 1')
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'kb_chunk'
  AND COLUMN_NAME = 'datasource_id');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- kb_chunk: 允许 document_id 为 NULL（数据同步管线产生的切片不关联文档）
-- 分两步查询避免 only_full_group_by 错误
SET @nullable = (SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_chunk' AND COLUMN_NAME = 'document_id');
SET @sql = IF(@nullable = 'NO',
    'ALTER TABLE kb_chunk MODIFY COLUMN document_id BIGINT NULL COMMENT ''所属文档ID''',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- chat_message
CALL p2_add_column('chat_message', 'feedback',        "VARCHAR(16) COMMENT 'positive/negative'");
CALL p2_add_column('chat_message', 'feedback_reason', "VARCHAR(512) COMMENT '反馈原因'");

-- stat_daily
CALL p2_add_column('stat_daily', 'positive_count', "INT DEFAULT 0 COMMENT '点赞数'");
CALL p2_add_column('stat_daily', 'negative_count', "INT DEFAULT 0 COMMENT '点踩数'");

-- data_source_config: 新增 total_passed 列（幂等，列存在时跳过 ALTER）
SET @sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE data_source_config ADD COLUMN total_passed INT COMMENT ''累计质量通过数（NULL=从未质检，回退使用total_synced）''',
    'SELECT 1')
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'data_source_config'
  AND COLUMN_NAME = 'total_passed');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- data_source_config: 新增 content_fields 列（幂等，列存在时跳过）
CALL p2_add_column('data_source_config', 'content_fields',
    "VARCHAR(512) COMMENT '完整性检查字段列表（逗号分隔），为空则使用全局默认值 content,text,_content'");

-- data_source_config: 定时同步字段（批次 05 · 任务 5.3.1，幂等，列存在时跳过）
-- 问题 22：此前实体与建表脚本均无调度字段，导致"定时同步"无存储位置
CALL p2_add_column('data_source_config', 'sync_cron',
    "VARCHAR(64) COMMENT '定时同步 Cron 表达式（空=不定时）'");
CALL p2_add_column('data_source_config', 'sync_enabled',
    "TINYINT(1) DEFAULT 1 COMMENT '是否启用定时同步：1=启用 0=停用'");

-- kb_chunk: record_hash（幂等，列存在时跳过）— 用于跨批次去重
CALL p2_add_column('kb_chunk', 'record_hash',
    "VARCHAR(64) COMMENT '原始记录的MD5指纹，用于跨批次去重'");
CALL p2_add_index('kb_chunk',  'idx_datasource_hash', '(datasource_id, record_hash)');

-- ── 6. 质检报告表（方案 B：failures 使用 JSON 列）★ ──
CREATE TABLE IF NOT EXISTS quality_report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    batch_id VARCHAR(64) NOT NULL COMMENT '同步批次 ID',
    datasource_id BIGINT COMMENT '数据源配置 ID',
    source_name VARCHAR(128) COMMENT '数据源名称',
    total INT NOT NULL DEFAULT 0 COMMENT '总记录数',
    pass INT NOT NULL DEFAULT 0 COMMENT '通过数',
    fail INT NOT NULL DEFAULT 0 COMMENT '失败数',
    rate DOUBLE NOT NULL DEFAULT 0 COMMENT '通过率（pass/total）',
    blocked TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否阻断',
    failures JSON COMMENT '失败明细列表（JSON 数组）',
    check_time DATETIME COMMENT '检查时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE INDEX uk_batch_id (batch_id),
    INDEX idx_blocked (blocked),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据质检报告表';

-- ── 7. 质检规则配置表（批次 10 · 任务 10.1，问题 35）★ ──
-- 规则只存"配置"，判定逻辑仍由 agent-qr-data-quality 的 QualityRule 实现类提供，
-- 由 DataQualityChecker 按 rule_type 分派执行（不引入脚本引擎）。
CREATE TABLE IF NOT EXISTS quality_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    rule_name VARCHAR(128) NOT NULL COMMENT '规则名称（展示用）',
    rule_type VARCHAR(32) NOT NULL COMMENT '规则类型编码：completeness/uniqueness/format/encoding/length',
    target_fields VARCHAR(512) COMMENT '目标字段列表（逗号分隔），为空表示由规则实现决定默认范围',
    params JSON COMMENT '校验参数（JSON 对象，如 {"pattern":"^\\d+$"} / {"minLength":1,"maxLength":64}）',
    enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用：1=启用 0=停用（停用规则不参与质检）',
    priority INT NOT NULL DEFAULT 100 COMMENT '优先级（升序执行，数值小者先执行）',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_enabled_priority (enabled, priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据质检规则配置表';

-- 内置默认规则（幂等：同名同类型已存在时跳过）
-- 这四条与改造前的编译期规则链（CompletenessRule/EncodingRule/FormatRule/DeduplicationRule）一一对应，
-- 保证改造后对同一数据得出相同结论；之后可在「规则管理」页增删改。
INSERT INTO quality_rule (rule_name, rule_type, target_fields, params, enabled, priority)
SELECT '完整性检查', 'completeness', NULL, NULL, 1, 10
 WHERE NOT EXISTS (SELECT 1 FROM quality_rule WHERE rule_type = 'completeness' AND rule_name = '完整性检查');

INSERT INTO quality_rule (rule_name, rule_type, target_fields, params, enabled, priority)
SELECT '编码检查', 'encoding', NULL, NULL, 1, 20
 WHERE NOT EXISTS (SELECT 1 FROM quality_rule WHERE rule_type = 'encoding' AND rule_name = '编码检查');

INSERT INTO quality_rule (rule_name, rule_type, target_fields, params, enabled, priority)
SELECT '格式检查', 'format', NULL, NULL, 1, 30
 WHERE NOT EXISTS (SELECT 1 FROM quality_rule WHERE rule_type = 'format' AND rule_name = '格式检查');

INSERT INTO quality_rule (rule_name, rule_type, target_fields, params, enabled, priority)
SELECT '重复检测', 'uniqueness', NULL, NULL, 1, 40
 WHERE NOT EXISTS (SELECT 1 FROM quality_rule WHERE rule_type = 'uniqueness' AND rule_name = '重复检测');

-- 清理存储过程
DROP PROCEDURE IF EXISTS p2_add_column;
DROP PROCEDURE IF EXISTS p2_add_index;


-- ============================================================
-- P2 阶段 sys_user 测试数据
-- 密码明文 → BCrypt 哈希（strength=12）
--   admin123 → $2a$12$LLSCrAn6V1JowW6SZ1Efc.tlLubbIAQeI0ZqzUdgRPdbS.jUIF7Ri
--   user123  → $2a$12$MDdLXy9CeR9qKScwYWn3xOgYzPaAcpNIQx5ZY6n87/y3E.ImWz22O
--   123456   → $2a$12$Yb.RjYQnXbhCscmv/7C/dOCm5fz5TdoDkGfB0j79BAz/T/Q7Cn1WG
-- ============================================================

INSERT INTO sys_user (username, password, real_name, email, phone, role, status, department, clearance_level, allowed_domains, title) VALUES

                                                                                                                                          -- ── 管理员 (1个) ──
                                                                                                                                          ('admin',     '$2a$12$LLSCrAn6V1JowW6SZ1Efc.tlLubbIAQeI0ZqzUdgRPdbS.jUIF7Ri', '系统管理员', 'admin@agent-qr.com',    '13800000001', 'admin', 1, 'COMMON',  3, 'HR,FINANCE,RD,SALES,COMMON',
                                                                                                                                           'director'),

                                                                                                                                          -- ── 经理级 (4个，每部门一个) ──
                                                                                                                                          ('zhangliu',  '$2a$12$MDdLXy9CeR9qKScwYWn3xOgYzPaAcpNIQx5ZY6n87/y3E.ImWz22O', '张六',       'zhangliu@agent-qr.com',  '13800000002', 'user',  1, 'HR',      2, 'HR,COMMON',                'manager'),
                                                                                                                                          ('wangqi',    '$2a$12$MDdLXy9CeR9qKScwYWn3xOgYzPaAcpNIQx5ZY6n87/y3E.ImWz22O', '王七',       'wangqi@agent-qr.com',    '13800000003', 'user',  1, 'FINANCE', 2, 'FINANCE,COMMON',           'manager'),
                                                                                                                                          ('zhaoba',    '$2a$12$MDdLXy9CeR9qKScwYWn3xOgYzPaAcpNIQx5ZY6n87/y3E.ImWz22O', '赵八',       'zhaoba@agent-qr.com',    '13800000004', 'user',  1, 'RD',      2, 'RD,COMMON',                'manager'),
                                                                                                                                          ('sunjiu',    '$2a$12$MDdLXy9CeR9qKScwYWn3xOgYzPaAcpNIQx5ZY6n87/y3E.ImWz22O', '孙九',       'sunjiu@agent-qr.com',    '13800000005', 'user',  1, 'SALES',   2, 'SALES,COMMON',             'manager'),

                                                                                                                                          -- ── 员工级 (4个，覆盖全部密级) ──
                                                                                                                                          ('lijuan',    '$2a$12$MDdLXy9CeR9qKScwYWn3xOgYzPaAcpNIQx5ZY6n87/y3E.ImWz22O', '李娟',       'lijuan@agent-qr.com',    '13800000006', 'user',  1, 'HR',      1, 'HR',                       'employee'),
                                                                                                                                          ('chenming',  '$2a$12$MDdLXy9CeR9qKScwYWn3xOgYzPaAcpNIQx5ZY6n87/y3E.ImWz22O', '陈明',       'chenming@agent-qr.com',  '13800000007', 'user',  1, 'FINANCE', 0, 'FINANCE',                  'employee'),
                                                                                                                                          ('liuyang',   '$2a$12$MDdLXy9CeR9qKScwYWn3xOgYzPaAcpNIQx5ZY6n87/y3E.ImWz22O', '刘洋',       'liuyang@agent-qr.com',   '13800000008', 'user',  1, 'RD',      1, 'RD',                       'employee'),
                                                                                                                                          ('huanglei',  '$2a$12$MDdLXy9CeR9qKScwYWn3xOgYzPaAcpNIQx5ZY6n87/y3E.ImWz22O', '黄磊',       'huanglei@agent-qr.com',  '13800000009', 'user',  1, 'SALES',   3, 'SALES,RD,COMMON',          'director'),

                                                                                                                                          -- ── 禁用用户 (1个) ──
                                                                                                                                          ('zhouwei',   '$2a$12$MDdLXy9CeR9qKScwYWn3xOgYzPaAcpNIQx5ZY6n87/y3E.ImWz22O', '周伟',       'zhouwei@agent-qr.com',   '13800000010', 'user',  0, 'COMMON',  0, 'COMMON',                   'employee');

