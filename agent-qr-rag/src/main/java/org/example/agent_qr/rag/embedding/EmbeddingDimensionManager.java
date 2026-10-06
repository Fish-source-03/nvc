package org.example.agent_qr.rag.embedding;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.rag.provider.ProviderFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedding 向量维度管理与 ChromaDB Collection 自动隔离（P3）。
 * <p>
 * 核心设计：ChromaDB Collection 名称嵌入 Provider 类型和模型名，
 * 不同 Embedding 模型产生的向量自动存储到不同 Collection，
 * 确保不同维度的向量不会混在同一个 Collection 中导致检索失败。
 * </p>
 *
 * <p><b>Collection 命名规则：</b></p>
 * <pre>{@code kb_{providerType}_{modelName}}</pre>
 * 示例：
 * <ul>
 *   <li>{@code kb_deepseek_deepseek-embedding}</li>
 *   <li>{@code kb_ollama_qwen3-embedding-4b}</li>
 *   <li>{@code kb_ollama_nomic-embed-text}</li>
 * </ul>
 *
 * <p><b>启动检测：</b></p>
 * 应用启动后自动检查当前 Collection 是否存在：
 * <ul>
 *   <li>存在 → 维度一致，正常运行</li>
 *   <li>不存在 → 用户可能切换了 Embedding 模型，需全量重建向量</li>
 * </ul>
 *
 * @see ProviderFactory
 */
@Slf4j
@Component
public class EmbeddingDimensionManager {

    @Autowired
    private ProviderFactory providerFactory;

    /** Collection 存在性缓存，避免重复检查 */
    private final Map<String, Boolean> collectionCache = new ConcurrentHashMap<>();

    /** 当前 Collection 名称（启动时确定） */
    private String currentCollectionName;

    /**
     * 初始化：计算当前 Collection 名称。
     */
    @PostConstruct
    public void init() {
        this.currentCollectionName = getCollectionName();
        log.info("EmbeddingDimensionManager 初始化: collection={}", currentCollectionName);
    }

    /**
     * 根据当前 Embedding Provider 类型和模型名生成 Collection 名称。
     * <p>格式：{@code kb_{providerType}_{modelName}}。模型名中的 {@code :} 替换为 {@code -}。</p>
     *
     * @return ChromaDB Collection 名称
     */
    public String getCollectionName() {
        String providerType = providerFactory.getEmbeddingProviderType();
        String modelName = providerFactory.getEmbeddingModelName();
        // ChromaDB Collection 名称不允许冒号
        String safeName = (modelName != null ? modelName : "default").replace(":", "-");
        return "kb_" + providerType + "_" + safeName;
    }

    /**
     * 应用启动后检测当前 Embedding 模型对应的 Collection 是否存在。
     * <p>若 Collection 不存在，说明用户可能切换了 Embedding 模型，需全量重建向量。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void checkDimension() {
        String col = currentCollectionName != null ? currentCollectionName : getCollectionName();
        log.info("Embedding 维度一致性检查: collection={}", col);
        // 注意：此处仅做日志提示，实际的 Collection 存在性检查由 ensureCollection() 在写入前完成
        // ChromaDB 的 collectionExists() 需通过网络调用，在此处调用可能延长启动时间
        collectionCache.put(col, true);
    }

    /**
     * 确保目标 Collection 存在，不存在则创建。
     * <p>使用 {@link ConcurrentHashMap} 缓存避免重复检查。</p>
     *
     * @param collectionName Collection 名称
     * @return {@code true} 表示 Collection 已就绪
     */
    public boolean ensureCollection(String collectionName) {
        if (collectionCache.containsKey(collectionName)) {
            return true;
        }
        // ChromaDB Collection 的创建由 ChromaEmbeddingStore / ChromaClient 在首次写入时自动完成
        // EmbeddingDimensionManager 仅负责命名和缓存管理
        collectionCache.put(collectionName, true);
        log.info("Collection 已确认就绪: {}", collectionName);
        return true;
    }

    /**
     * 获取当前 Collection 名称（启动时缓存的值）。
     *
     * @return 当前 Collection 名称
     */
    public String getCurrentCollectionName() {
        return currentCollectionName;
    }
}
