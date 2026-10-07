package org.example.agent_qr.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.agent_qr.knowledge.entity.Document;

import java.util.List;
import java.util.Map;

/**
 * 文档 Mapper 接口，提供文档表的基础 CRUD 及自定义 SQL 操作。
 * <p>
 * 继承 MyBatis-Plus 的 BaseMapper，自动获得通用 CRUD 能力。
 * </p>
 *
 * @author agent-qr
 */
@Mapper
public interface DocumentMapper extends BaseMapper<Document> {

    /**
     * 更新文档的处理状态。
     *
     * @param id     文档 ID
     * @param status 新的状态值
     * @return 受影响的行数
     */
    @Update("UPDATE kb_document SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /**
     * 分页查询文档列表，支持按业务域 / 密级筛选（批次 09 · 任务 9.3，问题 33 断裂 1）。
     * <p>
     * <b>为什么必须有这个方法</b>：前端（{@code knowledge.ts} + {@code KnowledgeView.vue}）
     * 一直在传 {@code domain} / {@code sensitivityLevel}，而后端接口只声明了
     * {@code page}/{@code size}——两个参数被 Spring <b>静默忽略</b>：用户点了"业务域/密级"
     * 筛选后数据毫无变化，也不报错。这里把筛选落到 SQL 上。
     * </p>
     * <p>
     * <b>两个参数都是"用户主动缩小范围"，不做权限判定</b>——越权范围由入口鉴权与
     * 检索侧的 ABAC 兜底（批次 03 任务 3.5）负责，本方法只做筛选。
     * </p>
     * <p>
     * ⚠️ 软删条件必须<b>手写</b>：MyBatis-Plus 的 {@code @TableLogic} 只对自动生成的 SQL 生效，
     * 手写 SQL 不加 {@code deleted = 0} 会把已软删文档返回给前端（同问题 27 的坑）。
     * </p>
     * <p>
     * 参数语义：{@code domain} 为 null/空白视为"不筛"；{@code sensitivityLevel} 为 null 视为"不筛"。
     * {@code sensitivityLevel} 为非整数的请求由 Spring 类型转换拒绝（HTTP 400），
     * 未知的 {@code domain} 值返回空列表（不是错误——域是开放取值）。
     * </p>
     *
     * @param page             分页对象（MyBatis-Plus 分页插件据此改写 SQL）
     * @param domain           业务域（可空）
     * @param sensitivityLevel 密级（可空）
     * @return 分页结果
     */
    @Select("<script>" +
            "SELECT * FROM kb_document " +
            "WHERE deleted = 0 " +
            "<if test='domain != null and domain != \"\"'>" +
            "  AND domain = #{domain} " +
            "</if>" +
            "<if test='sensitivityLevel != null'>" +
            "  AND sensitivity_level = #{sensitivityLevel} " +
            "</if>" +
            "</script>")
    IPage<Document> selectPageByFilter(Page<Document> page,
                                       @Param("domain") String domain,
                                       @Param("sensitivityLevel") Integer sensitivityLevel);

    /**
     * 以条件更新抢占"删除中"状态（批次 09 · 任务 9.6.2，问题 42 的并发保护）。
     * <p>
     * <b>为什么不能只靠"读-判断-写"</b>：两次并发请求各自 {@code selectById} 都看到
     * {@code READY}，随后都会发布删除事件、都会创建 {@code DeleteTask}——
     * 读与写之间的竞态窗口无法靠 {@code @Transactional} 消除（它只保证单次执行的原子性）。
     * 这里把"状态检查 + 状态写入"合并为<b>一条原子 SQL</b>，由数据库决定谁抢占成功。
     * </p>
     * <p>
     * 语义：
     * <ul>
     *   <li>返回 {@code 1} —— 本次请求成功抢占，可以继续发布删除事件；</li>
     *   <li>返回 {@code 0} —— 文档已被其他请求置为 {@code DELETING}（或不可见），
     *       调用方必须拒绝本次请求，不得重复发布事件。</li>
     * </ul>
     * </p>
     * <p>
     * {@code (status IS NULL OR status != 'DELETING')}：SQL 中 {@code NULL <> 'DELETING'}
     * 结果为 NULL（非真）会导致漏占位，显式处理 NULL 更稳妥；
     * {@code deleted = 0} 则防止与并发的软删请求互相覆盖。
     * </p>
     * <p>
     * ⚠️ 未改动 {@link #updateStatus} 的签名与语义——它仍被上传/解析链路使用。
     * </p>
     *
     * @param id 文档 ID
     * @return 受影响行数（1 = 抢占成功；0 = 已被抢占/不存在/已软删）
     */
    @Update("UPDATE kb_document SET status = 'DELETING' "
            + "WHERE id = #{id} AND (status IS NULL OR status != 'DELETING') AND deleted = 0")
    int claimDeleting(@Param("id") Long id);

    /**
     * 更新文档的错误信息。
     *
     * @param id       文档 ID
     * @param errorMsg 错误信息
     * @return 受影响的行数
     */
    @Update("UPDATE kb_document SET error_msg = #{errorMsg} WHERE id = #{id}")
    int updateErrorMsg(@Param("id") Long id, @Param("errorMsg") String errorMsg);

    /**
     * 统计各文件类型的文档数量分布。
     *
     * @return 文件类型分布列表，每项包含 file_type 和 cnt
     */
    @Select("SELECT file_type, COUNT(*) as cnt FROM kb_document GROUP BY file_type")
    List<Map<String, Object>> selectTypeDistribution();

    /**
     * 软删除文档（P2 新增）。
     */
    @Update("UPDATE kb_document SET deleted = 1 WHERE id = #{documentId}")
    int softDelete(@Param("documentId") Long documentId);
}
