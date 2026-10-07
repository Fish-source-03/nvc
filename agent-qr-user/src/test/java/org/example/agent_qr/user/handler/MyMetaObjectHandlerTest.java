package org.example.agent_qr.user.handler;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.example.agent_qr.user.entity.SysUser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MyMetaObjectHandler} 测试（批次 11 · 任务 11.2.2）。
 * <p>
 * 该处理器是全仓库所有 MyBatis-Plus 实体时间字段的唯一填充点，
 * 填充策略由实体上的 {@code @TableField(fill = ...)} 声明：
 * </p>
 * <ul>
 *   <li><b>插入</b>：{@code createTime} 与 {@code updateTime} 都必须有值，
 *       否则新记录的时间列落 NULL；</li>
 *   <li><b>更新</b>：只刷新 {@code updateTime}，**不得覆盖**已存在的 {@code createTime}——
 *       覆盖会永久丢失创建时间且无法恢复；</li>
 *   <li><b>不覆盖已有值</b>：调用方显式赋值时（如数据迁移），填充器不得改写。</li>
 * </ul>
 * <p>
 * 注意：{@code strictInsertFill/strictUpdateFill} 依赖 MyBatis-Plus 的
 * {@code TableInfo} 缓存（lambda 元数据）。本类显式初始化，避免依赖执行顺序或外部数据库。
 * </p>
 *
 * @author agent-qr
 */
class MyMetaObjectHandlerTest {

    private final MyMetaObjectHandler handler = new MyMetaObjectHandler();

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUser.class);
    }

    @Test
    @DisplayName("★ 插入时填充 createTime 与 updateTime")
    void insertFill_shouldFillBothTimestamps() {
        SysUser user = new SysUser();
        user.setUsername("alice");

        handler.insertFill(metaObjectOf(user));

        assertThat(user.getCreateTime()).isNotNull();
        assertThat(user.getUpdateTime()).isNotNull();
    }

    @Test
    @DisplayName("★ 更新时填充 updateTime，但不动 createTime（更新路径不得写创建时间）")
    void updateFill_shouldFillUpdateTimeOnly() {
        SysUser user = new SysUser();

        handler.updateFill(metaObjectOf(user));

        assertThat(user.getUpdateTime()).isNotNull();
        assertThat(user.getCreateTime()).isNull();
    }

    @Test
    @DisplayName("已有显式时间值不被覆盖（strict 语义：迁移/回填时不改写业务值）")
    void fill_shouldNotOverwriteExplicitValues() {
        LocalDateTime explicit = LocalDateTime.of(2020, 1, 2, 3, 4, 5);
        SysUser user = new SysUser();
        user.setCreateTime(explicit);
        user.setUpdateTime(explicit);

        handler.insertFill(metaObjectOf(user));
        handler.updateFill(metaObjectOf(user));

        assertThat(user.getCreateTime()).isEqualTo(explicit);
        assertThat(user.getUpdateTime()).isEqualTo(explicit);
    }

    private static MetaObject metaObjectOf(SysUser user) {
        return SystemMetaObject.forObject(user);
    }
}
