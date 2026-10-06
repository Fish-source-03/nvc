# 26 · quality_failure 表与 QualityFailureMapper 整体缺失

> **严重程度**：🟠 中
> **所属模块**：agent-qr-data-quality、数据库 schema
> **设计依据**：《系统详细设计说明书》§8.8.3、§8.8.5、§8.8.6、§8.8.7、§12.0、§12.5（共 6 处要求）
> **核查日期**：2026-10-06
> **核查方式**：静态代码分析 + DDL 核对（未连接数据库）

---

## 一、问题描述

设计文档在 6 处明确要求 `quality_failure` 表与 `QualityFailureMapper`：质检不通过的明细应逐条持久化，供前端 drill-down 查看"哪几条记录、哪个字段、什么原因"。

实际实现改为"方案 B"：

1. **`quality_failure` 表未建**——`db/` 下无该 DDL；
2. **`QualityFailureMapper` 类不存在**；
3. **`QualityFailure` 实体退化为 JSON DTO**：无主键、无 `reportId`、无 `@TableName`，仅作为 `quality_report.failures` JSON 列的元素类型；
4. **明细信息在去重后丢失 recordIndex**：失败项按 `MD5(ruleName + reason)` 跨记录去重，1 万条"内容为空"只留 1 条，其余记录的 `record_index` 永久丢失。

**关键问题**：设计文档全文**没有**"方案 B"的任何记载（`grep "方案 B" 系统详细设计说明书.md` 零命中），属未回填文档的架构级偏差——后续维护者按文档找不到对应的表与类。

---

## 二、推断依据

### 依据 1：表与 Mapper 均不存在

```bash
grep -rn "quality_failure" . --include=*.java --include=*.sql
# 仅命中 doc/系统详细设计说明书.md（6 处文档描述）
# 无 Java 类、无 DDL
```

设计文档中的要求位置：`系统详细设计说明书.md:4100`、`:4124`、`:4137`、`:4144`、`:4203`、`:5973`、`:6019`。

### 依据 2：改用 JSON 列

`agent-qr-web/src/main/resources/db/p2-schema.sql:209-227` 的 `quality_report` 表含 `failures` JSON 列（比设计多出 `datasource_id` / `source_name` / `failures` / `check_time`），但**没有** `quality_failure` 表。

### 依据 3：`QualityFailure` 退化为 DTO

`agent-qr-data-quality/.../entity/QualityFailure.java:15-25`

```java
public class QualityFailure {
    private String ruleName;
    private Integer recordIndex;
    private String reason;
    // 无 @TableName、无主键、无 reportId
}
```

设计 §8.8.7 要求 `QualityFailure` 含 `id` 与 `reportId`（作为独立表的实体）。

### 依据 4：`DataQualityService` 未注入 `QualityFailureMapper`

`agent-qr-data-quality/.../service/DataQualityService.java:44-47` 的依赖列表中无 `QualityFailureMapper`，与设计 §8.8.3 的"注入 `QualityFailureMapper` 并逐条落库 failure 明细"不符。

### 依据 5：失败明细被去重，recordIndex 丢失

`agent-qr-data-quality/.../checker/DataQualityChecker.java:101,134-144`

```java
// 按 MD5(ruleName + "|" + reason) 跨记录去重
```

后果：1 万条记录因同一规则失败时，`failures` 数组中只有 1 条，其 `recordIndex` 仅代表首次出现的位置。落库后**无法回答"具体哪几条失败了"**。

补充：质检的**过滤环节**使用的是另一份未去重的 `failedIndices`（`DataQualityService.java:166-177`），因此"阻断/过滤"行为是正确的——**只有报告本身失真**。这也意味着该缺陷不会立刻暴露，属于"报告不可用但不影响主流程"的类型。

### 依据 6：无测试

`agent-qr-data-quality/src/test` 不存在，无法通过测试佐证。

---

## 三、影响范围

1. **运维无法定位问题数据**：质检报告显示"通过率 62%"，但查不到是哪 38% 有问题——用户只能全量重跑或人工排查。
2. **前端无法做明细下钻**：前端 `QualityReportView` 即使想展示失败明细，后端也拿不到完整的 recordIndex 列表。
3. **文档与实现脱节**：后续维护者按设计文档找不到 `QualityFailureMapper`，会浪费排查时间（这正是复盘报告 §3 问题 1 描述的"需求在任务化过程中被稀释"）。
4. **趋势分析受限**：无法按字段/规则统计失败分布。

---

## 四、修复方向

1. **先做决策：是补表还是改文档**。
   - **方案 A（对齐设计）**：建 `quality_failure` 表 + `QualityFailureMapper`，恢复逐条落库（需先去掉依据 5 的去重，或改为"去重后仍保留 recordIndex 列表"）；
   - **方案 B（对齐实现，成本更低）**：保留 JSON 列方案，但**必须**：
     - 把"方案 B"写回设计文档（§8.8.3/8.8.5/8.8.6/8.8.7/§12 共 6 处）；
     - 让 `QualityFailure` 成为正式的 DTO（加 `@JsonInclude` 等注解、明确字段契约）。
2. **无论哪个方案，都要修依据 5 的去重**：建议改为"按 ruleName 聚合，但保留 recordIndex 列表（可设长度上限，如最多 100 个 + 总数）"，既控制体积又保留可定位性。
3. **补 `@JsonIgnore` 检查**：`QualityReport.java:71-72` 的 `failedIndices` 未加 `@JsonIgnore`，会随详情接口暴露内部索引集合，建议评估是否需要暴露。
4. **补测试**：至少覆盖"多条记录因同一规则失败时，报告能定位到具体记录"。

---

## 五、核查边界

- 静态分析，未连接数据库确认 `quality_report` 表的实际数据与 `failures` JSON 的体积分布。
- 未确认"方案 B"是有意的设计变更还是实现阶段的临时决策——代码注释（`QualityReport.java:20-25`、`DataQualityService.java:31-34`）自称"方案 B"，但设计文档无对应记载，需向作者确认。
- 未评估 `failures` JSON 列在超大记录数下的体积风险（`FormatRule` 会把具体值拼进 reason，高基数字段可能让明细接近记录数，触达 MySQL `max_allowed_packet`）。
