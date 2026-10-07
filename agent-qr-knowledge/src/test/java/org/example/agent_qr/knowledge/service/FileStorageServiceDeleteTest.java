package org.example.agent_qr.knowledge.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link FileStorageService#delete(String)} 语义测试（批次 08 · 任务 8.4.2 / 8.4.3）。
 * <p>
 * 删除链路需要区分"文件已不存在"（幂等成功）与"删除失败"（应入 DLQ），
 * 因此返回值由 void 改为 boolean。本测试用真实文件系统固化这一契约；
 * <b>存储路径规则（{@code yyyy/MM} 分目录）保持不变</b>。
 * </p>
 *
 * @author agent-qr
 */
class FileStorageServiceDeleteTest {

    @TempDir
    Path uploadDir;

    private FileStorageService service;

    @BeforeEach
    void setUp() {
        service = new FileStorageService();
        ReflectionTestUtils.setField(service, "uploadDir", uploadDir.toString());
    }

    @Test
    @DisplayName("★ 删除存在的文件：返回 true 且文件确实消失")
    void delete_shouldRemoveExistingFile_andReturnTrue() throws Exception {
        Path file = Files.createDirectories(uploadDir.resolve("2026/10")).resolve("resume.pdf");
        Files.writeString(file, "内容");

        assertThat(service.delete("2026/10/resume.pdf")).isTrue();
        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("★ 幂等：文件本就不存在时返回 true（目标状态已达成，不是失败）")
    void delete_shouldReturnTrue_whenFileAlreadyMissing() {
        assertThat(service.delete("2026/10/never-existed.pdf")).isTrue();
    }

    @Test
    @DisplayName("★ 删除失败（目录非空）时返回 false，供调用方入 DLQ")
    void delete_shouldReturnFalse_whenDeletionFails() throws Exception {
        Path dir = Files.createDirectories(uploadDir.resolve("2026/11"));
        Files.writeString(dir.resolve("inner.txt"), "x");

        assertThat(service.delete("2026/11")).isFalse();
        assertThat(dir).exists();
    }

    @Test
    @DisplayName("★ 空路径不抛异常且视为无需删除")
    void delete_shouldHandleBlankPath() {
        assertThatCode(() -> {
            assertThat(service.delete(null)).isTrue();
            assertThat(service.delete("")).isTrue();
            assertThat(service.delete("   ")).isTrue();
        }).doesNotThrowAnyException();
    }
}
