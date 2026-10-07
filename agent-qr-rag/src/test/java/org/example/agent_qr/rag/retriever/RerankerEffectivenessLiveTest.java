package org.example.agent_qr.rag.retriever;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.agent_qr.rag.entity.RetrievedDocument;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reranker 效果评估（批次 10 · 任务 10.3.5，问题 14）—— <b>实库 + 真实模型的活体评测</b>。
 * <p>
 * 目的：给"接入真实交叉编码器"这件事提供<b>可比数据</b>，而不是"编译通过"式验证。
 * </p>
 * <h3>评测设计</h3>
 * <ol>
 *   <li><b>语料</b>：直接取运行库中 {@code kb_chunk.deleted = 0} 的有效切片（当前基线 19 条）；</li>
 *   <li><b>评测集</b>：{@value #EVAL_CASES} 条"查询 + 期望命中的切片 ID"（见
 *       {@link #evaluateQuerySet()} 的静态表）；含字面匹配型与"同义不同词"型两类；</li>
 *   <li><b>粗排</b>：Ollama 向量余弦相似度（模拟真实检索链路的粗排候选），取 top-{@value #CANDIDATE_SIZE}；</li>
 *   <li><b>对比三种排序</b>：纯粗排（baseline）/ 旧实现（字符 n-gram + Jaccard 启发式，即降级路径）
 *       / 新实现（bge-reranker-v2-m3 交叉编码器）；<br>
 *       三种排序<b>共用同一候选集与同一粗排分</b>，唯一变量是精排打分函数；</li>
 *   <li><b>指标</b>：Hit@1 / Hit@3（期望切片出现在 Top1 / Top3 的查询占比）。</li>
 * </ol>
 * <p>
 * <b>依赖</b>：MySQL(3308) + Ollama(11434) + 本地 reranker(8080)；任一不可达即跳过（Assumptions），
 * 不产生任何写操作（只读 MySQL、只调推理接口），无需清理。
 * </p>
 * <p>
 * ⚠️ 本类会真实调用模型：{@value #EVAL_CASES} 条查询 × 约 1.5–4 秒/条（CPU 版 TEI），
 * 单次运行通常 1–2 分钟。断言只做"评测确实跑通"的结构性校验，命中率差异以<b>打印结果</b>为准
 * （命中率受语料变化影响，不宜写成硬断言）。
 * </p>
 *
 * @author agent-qr
 */
class RerankerEffectivenessLiveTest {

    /** 参与精排的候选数（先按粗排取 top-N；CPU 版 TEI 的时延随条数非线性上升） */
    private static final int CANDIDATE_SIZE = 8;

    /** 评测返回的 Top-K */
    private static final int TOP_K = 3;

    /** 评测集规模（固化在 {@link #evaluateQuerySet()} 中） */
    private static final int EVAL_CASES = 20;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static Connection connection;
    private static String ollamaBaseUrl;
    private static String embeddingModel;
    private static String rerankerBaseUrl;

    private static List<Long> chunkIds;
    private static List<String> chunkContents;
    private static List<float[]> chunkEmbeddings;

    @BeforeAll
    static void setUp() throws Exception {
        ollamaBaseUrl = yamlValue("ollama.embedding.base-url", "http://localhost:11434");
        embeddingModel = yamlValue("ollama.embedding.model", "qwen3-embedding:4b");
        rerankerBaseUrl = yamlValue("agent-qr.reranker.base-url", "http://localhost:8080");

        String url = yamlValue("spring.datasource.url", null);
        Assumptions.assumeTrue(url != null, "未找到数据源配置，跳过活体评测");
        try {
            connection = DriverManager.getConnection(url,
                    yamlValue("spring.datasource.username", "root"),
                    yamlValue("spring.datasource.password", "root"));
        } catch (Exception e) {
            Assumptions.abort("MySQL 不可达（" + e.getClass().getSimpleName() + "），跳过活体评测");
            return;
        }

        Assumptions.assumeTrue(isHealthy(rerankerBaseUrl + "/health"),
                "本地 reranker 服务不可达（" + rerankerBaseUrl + "），跳过活体评测");
        Assumptions.assumeTrue(isHealthy(ollamaBaseUrl + "/api/tags"),
                "Ollama 不可达（" + ollamaBaseUrl + "），跳过活体评测");

        loadCorpus();
        Assumptions.assumeTrue(chunkContents.size() >= 5,
                "有效切片不足（" + chunkContents.size() + " 条），跳过活体评测");
        embedCorpus();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    @DisplayName("★ 新旧 Reranker 命中率对比：粗排 baseline / 启发式（旧）/ 交叉编码器（新）")
    void compareHeuristicAndCrossEncoderHitRates() throws Exception {
        RerankerService heuristicReranker = new RerankerService(failingProvider());
        RerankerService crossEncoderReranker = crossEncoderReranker();

        Map<String, int[]> stats = new LinkedHashMap<>();
        stats.put("coarse-baseline", new int[2]);
        stats.put("heuristic(old)", new int[2]);
        stats.put("cross-encoder(new)", new int[2]);

        List<String> rows = new ArrayList<>();
        int cases = 0;
        for (QueryCase queryCase : evaluateQuerySet()) {
            List<RetrievedDocument> candidates = coarseCandidates(queryCase.query());
            Assumptions.assumeTrue(!candidates.isEmpty(), "粗排无候选，跳过");
            if (!candidates.stream().anyMatch(doc -> queryCase.expected().contains(Long.valueOf(doc.getDocumentId())))) {
                rows.add(String.format("%-28s | 期望切片未进入粗排 top-%d（三种排序同此约束）", queryCase.query(), CANDIDATE_SIZE));
                continue;
            }
            cases++;

            List<RetrievedDocument> baseline = topK(copyOf(candidates), TOP_K);
            List<RetrievedDocument> oldResult = heuristicReranker.rerank(queryCase.query(), copyOf(candidates), TOP_K);
            List<RetrievedDocument> newResult = crossEncoderReranker.rerank(queryCase.query(), copyOf(candidates), TOP_K);

            boolean baselineHit = hit(baseline, queryCase.expected());
            boolean oldHit = hit(oldResult, queryCase.expected());
            boolean newHit = hit(newResult, queryCase.expected());
            boolean baselineHit1 = hit(topK(copyOf(candidates), 1), queryCase.expected());
            boolean oldHit1 = hit(topK(copyOf(oldResult), 1), queryCase.expected());
            boolean newHit1 = hit(topK(copyOf(newResult), 1), queryCase.expected());

            tally(stats.get("coarse-baseline"), baselineHit1, baselineHit);
            tally(stats.get("heuristic(old)"), oldHit1, oldHit);
            tally(stats.get("cross-encoder(new)"), newHit1, newHit);

            rows.add(String.format("%-28s | baseline=%s%s  heuristic=%s%s  cross-encoder=%s%s  | top3=%s",
                    queryCase.query(),
                    baselineHit1 ? "1" : "0", baselineHit ? "(3)" : "(-)",
                    oldHit1 ? "1" : "0", oldHit ? "(3)" : "(-)",
                    newHit1 ? "1" : "0", newHit ? "(3)" : "(-)",
                    describe(newResult)));
        }

        final int totalCases = cases;
        System.out.println("================ Reranker 效果对比（语料 " + chunkContents.size()
                + " 条切片，候选 top-" + CANDIDATE_SIZE + "，返回 top-" + TOP_K + "） ================");
        rows.forEach(System.out::println);
        System.out.printf("--------------- 命中率（有效查询 %d 条）----------------%n", totalCases);
        stats.forEach((name, hit) -> System.out.printf("%-20s Hit@1 = %2d/%d (%.0f%%)   Hit@3 = %2d/%d (%.0f%%)%n",
                name, hit[0], totalCases, 100.0 * hit[0] / totalCases,
                hit[1], totalCases, 100.0 * hit[1] / totalCases));
        System.out.println("================================================================");

        assertThat(cases).as("评测集应有足够的可评测查询").isGreaterThanOrEqualTo(15);
        assertThat(stats.get("heuristic(old)")[1]).as("启发式至少应命中一部分（否则评测集或语料异常）").isGreaterThan(0);
        assertThat(stats.get("cross-encoder(new)")[1]).as("交叉编码器至少应命中一部分").isGreaterThan(0);
    }

    // ==================== 评测集 ====================

    /**
     * 20 条评测用例：查询 + 期望命中的切片 ID 集合（同一人的重复切片视为同一答案）。
     * <p>用例覆盖两类：字面重叠型（用户名/邮箱/部门等实体检索）与语义型（同义不同词）。</p>
     *
     * @return 评测用例
     */
    private static List<QueryCase> evaluateQuerySet() {
        return List.of(
                new QueryCase("张六的部门是哪个", Set.of(7390L, 11779L, 11791L)),
                new QueryCase("黄磊的职级是什么", Set.of(7397L)),
                new QueryCase("谁的密级是3", Set.of(7389L, 7397L)),
                new QueryCase("李娟属于哪个部门", Set.of(7394L)),
                new QueryCase("陈明的邮箱是多少", Set.of(7395L)),
                new QueryCase("刘洋的密级是多少", Set.of(7396L)),
                new QueryCase("王七的电话号码", Set.of(7391L)),
                new QueryCase("赵八的职级title", Set.of(7392L)),
                new QueryCase("孙九在哪个部门", Set.of(7393L)),
                new QueryCase("testuser999的角色", Set.of(7386L)),
                new QueryCase("usertest的department", Set.of(7387L)),
                new QueryCase("usertest2的真实姓名", Set.of(7388L)),
                new QueryCase("系统管理员这个账号的职级", Set.of(7389L)),
                new QueryCase("求职者想找什么岗位", Set.of(11771L)),
                new QueryCase("谁负责开发实时舆情情感分析平台", Set.of(11772L)),
                new QueryCase("客户分群项目用了哪些数据处理库", Set.of(11773L)),
                new QueryCase("UML建模相关的项目经历", Set.of(11774L)),
                new QueryCase("Kafka消费组的负载均衡是怎么做的", Set.of(11773L, 11774L)),
                new QueryCase("Redis缓存让接口响应时间降低了多少", Set.of(11772L)),
                new QueryCase("求职者掌握哪些编程语言", Set.of(11771L)));
    }

    // ==================== 评测构件 ====================

    /**
     * 粗排候选：按 Ollama 向量余弦相似度取 top-{@value #CANDIDATE_SIZE}。
     *
     * @param query 查询
     * @return 候选文档（粗排分降序）
     */
    private List<RetrievedDocument> coarseCandidates(String query) throws Exception {
        float[] queryVector = embed(List.of(query)).get(0);
        List<RetrievedDocument> candidates = new ArrayList<>();
        for (int i = 0; i < chunkContents.size(); i++) {
            RetrievedDocument document = new RetrievedDocument();
            document.setDocumentId(String.valueOf(chunkIds.get(i)));
            document.setContent(chunkContents.get(i));
            document.setSimilarity((double) cosine(queryVector, chunkEmbeddings.get(i)));
            candidates.add(document);
        }
        candidates.sort(Comparator.comparing(RetrievedDocument::getSimilarity).reversed());
        return new ArrayList<>(candidates.subList(0, Math.min(CANDIDATE_SIZE, candidates.size())));
    }

    /**
     * 每次都直接抛异常的提供者 —— 用于恒定地走"启发式降级路径"（旧实现）。
     *
     * @return 提供者
     */
    private static RerankerProvider failingProvider() {
        return new RerankerProvider() {
            @Override
            public List<Score> rerank(String query, List<String> documents) {
                throw new RerankerException("评测：强制走启发式降级路径");
            }

            @Override
            public String name() {
                return "heuristic-only(eval)";
            }
        };
    }

    /**
     * 真实交叉编码器精排器（指向本地 TEI 服务）。
     *
     * @return RerankerService
     */
    private static RerankerService crossEncoderReranker() {
        BgeRerankerProvider provider = new BgeRerankerProvider();
        ReflectionTestUtils.setField(provider, "baseUrl", rerankerBaseUrl);
        ReflectionTestUtils.setField(provider, "model", "bge-reranker-v2-m3");
        ReflectionTestUtils.setField(provider, "connectTimeoutMs", 5000);
        ReflectionTestUtils.setField(provider, "readTimeoutMs", 30000);
        ReflectionTestUtils.setField(provider, "maxDocChars", 1024);
        return new RerankerService(provider);
    }

    /**
     * 加载有效切片（只读）。
     *
     * @throws Exception SQL 异常
     */
    private static void loadCorpus() throws Exception {
        chunkIds = new ArrayList<>();
        chunkContents = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT id, content FROM kb_chunk WHERE deleted = 0 ORDER BY id")) {
            while (rs.next()) {
                chunkIds.add(rs.getLong(1));
                chunkContents.add(rs.getString(2) == null ? "" : rs.getString(2));
            }
        }
    }

    /**
     * 预计算语料向量（一次批量调用）。
     *
     * @throws Exception HTTP 异常
     */
    private static void embedCorpus() throws Exception {
        chunkEmbeddings = embed(chunkContents);
    }

    /**
     * 调 Ollama {@code /api/embed} 批量取向量。
     *
     * @param texts 文本列表
     * @return 向量列表
     * @throws Exception HTTP 异常
     */
    private static List<float[]> embed(List<String> texts) throws Exception {
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("model", embeddingModel);
        ArrayNode input = body.putArray("input");
        texts.forEach(input::add);
        HttpRequest request = HttpRequest.newBuilder(URI.create(ollamaBaseUrl + "/api/embed"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Ollama /api/embed 返回 HTTP " + response.statusCode());
        }
        JsonNode root = OBJECT_MAPPER.readTree(response.body());
        List<float[]> vectors = new ArrayList<>();
        for (JsonNode vectorNode : root.get("embeddings")) {
            float[] vector = new float[vectorNode.size()];
            for (int i = 0; i < vectorNode.size(); i++) {
                vector[i] = (float) vectorNode.get(i).asDouble();
            }
            vectors.add(vector);
        }
        return vectors;
    }

    /**
     * 余弦相似度。
     *
     * @param a 向量 a
     * @param b 向量 b
     * @return 相似度
     */
    private static float cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
    }

    private static List<RetrievedDocument> topK(List<RetrievedDocument> documents, int k) {
        documents.sort(Comparator.comparing(RetrievedDocument::getSimilarity).reversed());
        return new ArrayList<>(documents.subList(0, Math.min(k, documents.size())));
    }

    private static List<RetrievedDocument> copyOf(List<RetrievedDocument> source) {
        List<RetrievedDocument> copy = new ArrayList<>();
        for (RetrievedDocument document : source) {
            RetrievedDocument item = new RetrievedDocument();
            item.setDocumentId(document.getDocumentId());
            item.setChunkId(document.getChunkId());
            item.setDocumentTitle(document.getDocumentTitle());
            item.setContent(document.getContent());
            item.setSimilarity(document.getSimilarity());
            copy.add(item);
        }
        return copy;
    }

    private static boolean hit(List<RetrievedDocument> documents, Set<Long> expected) {
        return documents.stream()
                .anyMatch(document -> expected.contains(Long.valueOf(document.getDocumentId())));
    }

    private static void tally(int[] counter, boolean hitAt1, boolean hitAtK) {
        if (hitAt1) {
            counter[0]++;
        }
        if (hitAtK) {
            counter[1]++;
        }
    }

    private static String describe(List<RetrievedDocument> documents) {
        return documents.stream().map(RetrievedDocument::getDocumentId).toList().toString();
    }

    /**
     * 探测服务可用性。
     *
     * @param url 健康检查地址
     * @return 200 表示可用
     */
    private static boolean isHealthy(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从 application*.yml 读取配置值（点分键精确匹配，避免误取同名的其它键）。
     *
     * @param key      形如 {@code agent-qr.reranker.base-url} 的点分键
     * @param fallback 缺省值
     * @return 配置值
     */
    private static String yamlValue(String key, String fallback) {
        if (yamlValues == null) {
            yamlValues = new LinkedHashMap<>();
            for (String fileName : List.of("application.yml", "application-p1.yml",
                    "application-p2.yml", "application-p3.yml")) {
                Path path = locateResource(fileName);
                if (path != null) {
                    yamlValues.putAll(parseYaml(path));
                }
            }
        }
        String raw = yamlValues.get(key);
        if (raw == null) {
            return fallback;
        }
        Matcher placeholder = Pattern.compile("\\$\\{([A-Za-z0-9_]+):([^}]*)}").matcher(raw);
        if (placeholder.matches()) {
            String fromEnv = System.getenv(placeholder.group(1));
            return fromEnv != null ? fromEnv : placeholder.group(2);
        }
        return raw.replace("\"", "");
    }

    /** 缓存的 yml 点分键值（首次访问时解析） */
    private static Map<String, String> yamlValues;

    /**
     * 极简 yml 解析：按缩进维护父级路径，收集"叶子键 = 值"的点分键值对。
     *
     * @param path yml 文件
     * @return 点分键 → 原始值
     */
    private static Map<String, String> parseYaml(Path path) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        try {
            for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String line = rawLine;
                int hash = line.indexOf('#');
                if (hash >= 0) {
                    line = line.substring(0, hash);
                }
                if (line.isBlank()) {
                    continue;
                }
                int indent = line.indexOf(line.trim());
                String text = line.trim();
                int colon = text.indexOf(':');
                if (colon < 0 || text.startsWith("-")) {
                    continue;
                }
                String name = text.substring(0, colon).trim();
                String value = text.substring(colon + 1).trim();
                while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                    indents.remove(indents.size() - 1);
                    names.remove(names.size() - 1);
                }
                names.add(name);
                indents.add(indent);
                if (!value.isEmpty()) {
                    values.put(String.join(".", names), value);
                }
            }
        } catch (Exception e) {
            // 解析失败按"配置缺失"处理（走 fallback）
        }
        return values;
    }

    /**
     * 定位 resources 下的 yml 文件。
     *
     * @param fileName 文件名
     * @return 路径；未找到返回 null
     */
    private static Path locateResource(String fileName) {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++) {
            Path candidate = dir.resolve(Path.of("agent-qr-web", "src", "main", "resources", fileName));
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    /**
     * 评测用例。
     *
     * @param query    查询
     * @param expected 期望命中的切片 ID 集合
     */
    private record QueryCase(String query, Set<Long> expected) {
    }
}
