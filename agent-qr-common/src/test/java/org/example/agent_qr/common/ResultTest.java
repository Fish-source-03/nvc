package org.example.agent_qr.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测试基础设施冒烟测试（批次 01 · 任务 1.0.2）。
 * <p>
 * 用于验证 {@code mvn test} 构建链路通畅：若本类可被编译并执行，
 * 说明 JUnit 5 + AssertJ 的依赖与 Surefire 插件配置正确。
 * </p>
 *
 * @author agent-qr
 */
class ResultTest {

    @Test
    @DisplayName("success() 应返回 code=200 且 data 为空")
    void success_shouldReturnCode200AndNullData() {
        Result<Void> result = Result.success();

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getMessage()).isEqualTo("操作成功");
        assertThat(result.getData()).isNull();
        assertThat(result.getTimestamp()).isNotNull();
    }

    @Test
    @DisplayName("success(data) 应携带数据并返回 code=200")
    void success_withData_shouldCarryPayload() {
        Result<Integer> result = Result.success(42);

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isEqualTo(42);
    }

    @Test
    @DisplayName("success(String) 命中 message 重载：data 为空、message 为传入值（既有重载歧义，锁定行为防回归）")
    void success_withString_shouldMatchMessageOverload() {
        Result<String> result = Result.success("payload");

        assertThat(result.getCode()).isEqualTo(200);
        // 注意：T=String 时编译器选择 success(String message) 而非 success(T data)，
        // 因此 data 为 null、payload 落在 message。构造带 String 数据的 Result 需改用
        // Result.success(null, data)（四参构造）或显式转型。
        assertThat(result.getMessage()).isEqualTo("payload");
        assertThat(result.getData()).isNull();
    }

    @Test
    @DisplayName("error() 应返回指定错误码与消息")
    void error_shouldReturnGivenCodeAndMessage() {
        Result<Void> result = Result.error(403, "无权访问");

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(result.getMessage()).isEqualTo("无权访问");
        assertThat(result.getData()).isNull();
    }
}
