package org.example.agent_qr.rag.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChromaConfig} 单元测试。
 * <p>
 * 拦截的缺陷：collection 检查/创建用 {@code /api/v2/collections}（**缺少 tenant/database 段**），
 * 在 ChromaDB 1.0.0 上该路径返回 404/400，导致"确保 cosine"的防护静默失效，
 * 且 400 被误判为"collection 不存在"。
 * </p>
 *
 * @author agent-qr
 */
class ChromaConfigTest {

    @Test
    @DisplayName("collection 资源路径必须包含 tenant/database 段（ChromaDB v2 API 要求）")
    void buildCollectionsPath_shouldIncludeTenantAndDatabase() {
        String path = ChromaConfig.buildCollectionsPath("default", "default");

        assertThat(path).isEqualTo("/api/v2/tenants/default/databases/default/collections");
    }

    @Test
    @DisplayName("路径不得回退为缺失 tenant/database 的旧形态（该形态在 ChromaDB 1.0.0 上返回 404/400）")
    void buildCollectionsPath_shouldNotUseLegacyPath() {
        String path = ChromaConfig.buildCollectionsPath("default", "default");

        assertThat(path).doesNotStartWith("/api/v2/collections");
        assertThat(path).contains("/tenants/").contains("/databases/");
    }

    @Test
    @DisplayName("tenant/database 应可配置且正确嵌入路径")
    void buildCollectionsPath_shouldHonorConfiguredNamespace() {
        assertThat(ChromaConfig.buildCollectionsPath("tenant-a", "db-b"))
                .isEqualTo("/api/v2/tenants/tenant-a/databases/db-b/collections");
    }
}
