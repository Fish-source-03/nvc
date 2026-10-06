package org.example.agent_qr.compensation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.agent_qr.compensation.entity.DeleteTask;

import java.util.List;

/**
 * 删除任务 Mapper。
 *
 * @author agent-qr
 */
@Mapper
public interface DeleteTaskMapper extends BaseMapper<DeleteTask> {

    /**
     * 更新任务状态。
     */
    @Update("UPDATE delete_task SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /**
     * 递增重试次数。
     */
    @Update("UPDATE delete_task SET retry_count = retry_count + 1 WHERE id = #{id}")
    int incrementRetryCount(@Param("id") Long id);

    /**
     * 按状态查询删除任务（供运维排查与 DLQ 重试定位待补偿任务）。
     *
     * @param status 任务状态（PENDING / DONE / FAILED）
     * @return 该状态下的任务列表，按 ID 倒序
     */
    @Select("SELECT * FROM delete_task WHERE status = #{status} ORDER BY id DESC")
    List<DeleteTask> selectByStatus(@Param("status") String status);

    /**
     * 按文档 ID 查询删除任务（供 DLQ 重试做幂等判断）。
     *
     * @param documentId 文档 ID
     * @return 该文档的删除任务列表，按 ID 倒序（最新在前）
     */
    @Select("SELECT * FROM delete_task WHERE document_id = #{documentId} ORDER BY id DESC")
    List<DeleteTask> selectByDocumentId(@Param("documentId") Long documentId);
}
