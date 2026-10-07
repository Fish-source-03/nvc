package org.example.agent_qr.dataquality.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.common.Result;
import org.example.agent_qr.dataquality.entity.QualityReport;
import org.example.agent_qr.dataquality.entity.QualityRuleConfig;
import org.example.agent_qr.dataquality.service.DataQualityService;
import org.example.agent_qr.dataquality.service.QualityRuleService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据质量 REST 控制器。
 * <p>
 * 提供两类接口：
 * </p>
 * <ol>
 *   <li>质检报告的分页列表与详情查询（供「质量报告」页面调用）；</li>
 *   <li><b>质检规则 CRUD</b>（批次 10 · 任务 10.1，问题 35）——供「规则管理」页面调用，
 *       规则落库后由 {@code DataQualityChecker} 动态加载并参与真实质检。</li>
 * </ol>
 * <p>
 * 权限：规则管理端点与数据源管理同口径（仅 admin，ABAC {@code canManageDatasource}），
 * 对应前端路由 {@code /admin/quality/rules} 的 {@code requiresAdmin}。
 * 报告查询端点维持既有行为不变。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@RestController
@RequestMapping("/api/dataquality")
@RequiredArgsConstructor
public class DataQualityController {

    private final DataQualityService dataQualityService;
    private final QualityRuleService qualityRuleService;

    /**
     * 分页查询质检报告列表。
     * <p>
     * 返回 MyBatis-Plus IPage 对象，Jackson 序列化后
     * 包含 records/total/size/current/pages 字段，
     * 与前端的 PageResult&lt;QualityReport&gt; 类型对齐。
     * </p>
     *
     * @param page    页码（默认 1）
     * @param size    每页条数（默认 10）
     * @param blocked 阻断状态筛选（可选）
     * @return 分页报告列表
     */
    @GetMapping("/reports")
    public Result<IPage<QualityReport>> listReports(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Boolean blocked) {
        log.info("查询质检报告列表: page={}, size={}, blocked={}", page, size, blocked);
        IPage<QualityReport> result = dataQualityService.listReports(page, size, blocked);
        return Result.success(result);
    }

    /**
     * 根据批次 ID 查询质检报告详情（含失败明细）。
     *
     * @param batchId 同步批次 ID
     * @return 质检报告详情（含失败明细列表）
     */
    @GetMapping("/reports/{batchId}")
    public Result<QualityReport> getReport(@PathVariable String batchId) {
        log.info("查询质检报告详情: batchId={}", batchId);
        QualityReport report = dataQualityService.getReportByBatchId(batchId);
        return Result.success(report);
    }

    // ==================== 质检规则 CRUD（批次 10 · 任务 10.1） ====================

    /**
     * 查询规则列表（含禁用规则，按优先级升序）。
     *
     * @return 规则配置列表
     */
    @GetMapping("/rules")
    @PreAuthorize("@abac.canManageDatasource(principal)")
    public Result<List<QualityRuleConfig>> listRules() {
        log.info("查询质检规则列表");
        return Result.success(qualityRuleService.listRules());
    }

    /**
     * 查询单条规则。
     *
     * @param id 规则 ID
     * @return 规则配置
     */
    @GetMapping("/rules/{id}")
    @PreAuthorize("@abac.canManageDatasource(principal)")
    public Result<QualityRuleConfig> getRule(@PathVariable Long id) {
        return Result.success(qualityRuleService.getRule(id));
    }

    /**
     * 新建规则。
     *
     * @param config 规则配置（参数非法时由 Service 抛出业务异常，返回统一错误结构）
     * @return 创建后的规则
     */
    @PostMapping("/rules")
    @PreAuthorize("@abac.canManageDatasource(principal)")
    public Result<QualityRuleConfig> createRule(@RequestBody QualityRuleConfig config) {
        QualityRuleConfig created = qualityRuleService.createRule(config);
        log.info("质检规则已创建: id={}, name={}, type={}", created.getId(),
                created.getRuleName(), created.getRuleType());
        return Result.success("质检规则创建成功", created);
    }

    /**
     * 更新规则（含整体启停状态）。
     *
     * @param id     规则 ID
     * @param config 新配置
     * @return 更新后的规则
     */
    @PutMapping("/rules/{id}")
    @PreAuthorize("@abac.canManageDatasource(principal)")
    public Result<QualityRuleConfig> updateRule(@PathVariable Long id,
                                                @RequestBody QualityRuleConfig config) {
        QualityRuleConfig updated = qualityRuleService.updateRule(id, config);
        log.info("质检规则已更新: id={}, name={}", id, updated.getRuleName());
        return Result.success("质检规则更新成功", updated);
    }

    /**
     * 启停规则（规则管理页的开关，避免整条回填）。
     *
     * @param id      规则 ID
     * @param enabled 是否启用
     * @return 更新后的规则
     */
    @PutMapping("/rules/{id}/enabled")
    @PreAuthorize("@abac.canManageDatasource(principal)")
    public Result<QualityRuleConfig> setRuleEnabled(@PathVariable Long id,
                                                    @RequestParam boolean enabled) {
        QualityRuleConfig updated = qualityRuleService.setEnabled(id, enabled);
        return Result.success(enabled ? "规则已启用" : "规则已停用", updated);
    }

    /**
     * 删除规则。
     *
     * @param id 规则 ID
     * @return 操作结果
     */
    @DeleteMapping("/rules/{id}")
    @PreAuthorize("@abac.canManageDatasource(principal)")
    public Result<Void> deleteRule(@PathVariable Long id) {
        qualityRuleService.deleteRule(id);
        log.info("质检规则已删除: id={}", id);
        return Result.success("质检规则删除成功");
    }
}
