package org.example.agent_qr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Agent-QR 应用程序启动类。
 * <p>
 * 基于 LangChain4j 的 RAG 企业内部知识库问答 Agent 系统。
 * </p>
 * <p>
 * {@code @EnableScheduling} 为必需：注册全仓库 5 处 {@code @Scheduled} 定时任务
 * （DLQ 重试、孤儿向量扫描、重复数据清理、域描述/域向量刷新），
 * 它们是设计文档中"最终一致性兜底"的执行入口（问题 01）。
 * </p>
 */
@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = "org.example.agent_qr")
public class AgentQrApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentQrApplication.class, args);
    }
}

