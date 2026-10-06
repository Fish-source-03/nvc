package org.example.agent_qr.user.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户实体类，对应数据库表 sys_user。
 * <p>
 * 字段包括用户基本信息、角色和状态，时间字段由 MyBatis-Plus 自动填充。
 * </p>
 *
 * @author agent-qr
 */
@Data
@TableName("sys_user")
public class SysUser {

    /**
     * 主键 ID，自增。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 用户名，用于登录。
     */
    private String username;

    /**
     * 密码，BCrypt 加密后的密文。
     * <p>
     * ★ 问题 06：禁止参与 JSON 序列化。本实体被 {@code /api/admin/users}、
     * {@code /api/auth/info} 等接口直接返回，若无此注解，口令哈希将随响应外泄
     * （可被离线爆破）。反序列化同样忽略该字段——本实体从不作为请求体接收。
     * </p>
     */
    @JsonIgnore
    private String password;

    /**
     * 真实姓名。
     */
    private String realName;

    /**
     * 电子邮箱。
     */
    private String email;

    /**
     * 电话号码。
     */
    private String phone;

    /**
     * 角色：admin 或 user。
     */
    private String role;

    /**
     * 状态：1-启用，0-禁用。
     */
    private Integer status;

    /**
     * 创建时间，插入时自动填充。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间，插入和更新时自动填充。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    // ==================== P2 ABAC 字段 ====================

    /**
     * 所属部门：HR / FINANCE / RD / SALES / COMMON。
     */
    @TableField
    private String department;

    /**
     * 数据密级：0=公开 / 1=内部 / 2=机密 / 3=绝密。
     */
    @TableField
    private Integer clearanceLevel;

    /**
     * 允许访问的业务域（逗号分隔）。
     */
    @TableField
    private String allowedDomains;

    /**
     * 职级：employee / manager / director。
     */
    @TableField
    private String title;
}
