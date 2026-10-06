package org.example.agent_qr.datasource.connector;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.datasource.dto.ConnectionTestResult;
import org.example.agent_qr.datasource.dto.SyncContext;
import org.example.agent_qr.datasource.dto.SyncResult;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * S3 对象存储数据源连接器。
 * <p>
 * 通过 AWS S3 SDK 连接对象存储，列出并下载支持格式的文件，
 * 支持基于文件修改时间的增量同步。
 * </p>
 *
 * @author agent-qr
 */
@Slf4j
@Component
public class S3Connector implements DataSourceConnector {

    /** 支持的文件格式 */
    private static final Set<String> SUPPORTED_FORMATS = Set.of(
            "pdf", "docx", "txt", "md", "csv", "json"
    );

    @Override
    public String getType() {
        return "S3";
    }

    /**
     * 根据配置创建 AmazonS3 客户端。
     */
    private AmazonS3 buildClient(Map<String, Object> config) {
        String endpoint = (String) config.get("endpoint");
        String accessKey = (String) config.get("accessKey");
        String secretKey = (String) config.get("secretKey");
        String region = (String) config.getOrDefault("region", "us-east-1");

        BasicAWSCredentials credentials = new BasicAWSCredentials(accessKey, secretKey);
        return AmazonS3ClientBuilder.standard()
                .withEndpointConfiguration(
                        new AwsClientBuilder.EndpointConfiguration(endpoint, region))
                .withCredentials(new AWSStaticCredentialsProvider(credentials))
                .withPathStyleAccessEnabled(true)
                .build();
    }

    @Override
    public ConnectionTestResult testConnection(Map<String, Object> config) {
        String bucketName = (String) config.get("bucketName");
        long start = System.currentTimeMillis();
        try {
            AmazonS3 client = buildClient(config);
            ListObjectsV2Request request = new ListObjectsV2Request()
                    .withBucketName(bucketName)
                    .withMaxKeys(1);
            client.listObjectsV2(request);
            long latency = System.currentTimeMillis() - start;
            client.shutdown();
            return ConnectionTestResult.ok(latency, "S3 Object Storage", null);
        } catch (Exception e) {
            log.error("S3 连接测试失败: bucket={}, error={}", bucketName, e.getMessage());
            return ConnectionTestResult.fail(e.getMessage());
        }
    }

    @Override
    public SyncResult fullSync(SyncContext context) {
        Map<String, Object> config = context.getConfig();
        String bucketName = (String) config.get("bucketName");
        String prefix = (String) config.getOrDefault("prefix", "");

        List<Map<String, Object>> allRows = new ArrayList<>();
        String latestTimestamp = null;

        try {
            AmazonS3 client = buildClient(config);

            ListObjectsV2Request request = new ListObjectsV2Request()
                    .withBucketName(bucketName)
                    .withPrefix(prefix)
                    .withMaxKeys(1000);

            ListObjectsV2Result result;
            do {
                result = client.listObjectsV2(request);
                for (S3ObjectSummary summary : result.getObjectSummaries()) {
                    String key = summary.getKey();
                    String ext = getFileExtension(key);
                    if (!SUPPORTED_FORMATS.contains(ext)) {
                        continue;
                    }

                    // 下载文件内容
                    try {
                        S3Object s3Object = client.getObject(bucketName, key);
                        String content = new BufferedReader(
                                new InputStreamReader(s3Object.getObjectContent(), StandardCharsets.UTF_8))
                                .lines()
                                .collect(Collectors.joining("\n"));

                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("_file_key", key);
                        row.put("_file_type", ext);
                        row.put("_content", content);
                        row.put("_size", summary.getSize());
                        row.put("_last_modified", summary.getLastModified().toInstant().toString());
                        allRows.add(row);

                        // 跟踪最新时间戳
                        Instant modTime = summary.getLastModified().toInstant();
                        if (latestTimestamp == null || modTime.toString().compareTo(latestTimestamp) > 0) {
                            latestTimestamp = modTime.toString();
                        }
                    } catch (Exception e) {
                        log.error("S3 文件下载失败: key={}, error={}", key, e.getMessage());
                    }
                }
                request.setContinuationToken(result.getNextContinuationToken());
            } while (result.isTruncated());

            client.shutdown();
            log.info("S3 全量同步: 读取 {} 个文件, 最新时间戳={}", allRows.size(), latestTimestamp);
        } catch (Exception e) {
            // 批次 05 · 任务 5.1.1：失败必须向上传递，否则调用方会把失败记为 SUCCESS
            log.error("S3 全量同步失败: {}", e.getMessage(), e);
            return SyncResult.failure("S3 全量同步失败: " + e.getMessage(), allRows, latestTimestamp);
        }

        return new SyncResult(allRows.size(), allRows, latestTimestamp);
    }

    @Override
    public SyncResult incrementalSync(SyncContext context, String lastCursor) {
        Map<String, Object> config = context.getConfig();
        String bucketName = (String) config.get("bucketName");
        String prefix = (String) config.getOrDefault("prefix", "");

        List<Map<String, Object>> allRows = new ArrayList<>();
        String newCursor = lastCursor;

        try {
            AmazonS3 client = buildClient(config);
            Instant cursorTime = lastCursor != null
                    ? Instant.parse(lastCursor)
                    : Instant.EPOCH;

            ListObjectsV2Request request = new ListObjectsV2Request()
                    .withBucketName(bucketName)
                    .withPrefix(prefix)
                    .withMaxKeys(1000);

            ListObjectsV2Result result;
            do {
                result = client.listObjectsV2(request);
                for (S3ObjectSummary summary : result.getObjectSummaries()) {
                    if (summary.getLastModified().toInstant().compareTo(cursorTime) <= 0) {
                        continue;
                    }

                    String key = summary.getKey();
                    String ext = getFileExtension(key);
                    if (!SUPPORTED_FORMATS.contains(ext)) {
                        continue;
                    }

                    try {
                        S3Object s3Object = client.getObject(bucketName, key);
                        String content = new BufferedReader(
                                new InputStreamReader(s3Object.getObjectContent(), StandardCharsets.UTF_8))
                                .lines()
                                .collect(Collectors.joining("\n"));

                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("_file_key", key);
                        row.put("_file_type", ext);
                        row.put("_content", content);
                        row.put("_size", summary.getSize());
                        row.put("_last_modified", summary.getLastModified().toInstant().toString());
                        allRows.add(row);

                        Instant modTime = summary.getLastModified().toInstant();
                        if (newCursor == null || modTime.toString().compareTo(newCursor) > 0) {
                            newCursor = modTime.toString();
                        }
                    } catch (Exception e) {
                        log.error("S3 增量文件下载失败: key={}, error={}", key, e.getMessage());
                    }
                }
                request.setContinuationToken(result.getNextContinuationToken());
            } while (result.isTruncated());

            client.shutdown();
            log.info("S3 增量同步: 读取 {} 个文件, 新游标={}", allRows.size(), newCursor);
        } catch (Exception e) {
            // 批次 05 · 任务 5.1.1：失败必须向上传递（增量过滤逻辑本身不变）
            log.error("S3 增量同步失败: {}", e.getMessage(), e);
            return SyncResult.failure("S3 增量同步失败: " + e.getMessage(), allRows, newCursor);
        }

        return new SyncResult(allRows.size(), allRows, newCursor);
    }

    /**
     * 从文件 Key 中提取扩展名（小写）。
     */
    private String getFileExtension(String key) {
        int dotIndex = key.lastIndexOf('.');
        if (dotIndex < 0) {
            return "";
        }
        return key.substring(dotIndex + 1).toLowerCase();
    }
}
