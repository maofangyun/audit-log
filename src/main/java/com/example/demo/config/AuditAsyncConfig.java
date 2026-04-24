package com.example.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 审计日志专用异步线程池配置
 *
 * <p>设计原则：
 * <ul>
 *   <li>核心线程 4：应对常规并发</li>
 *   <li>最大线程 10：应对突发流量</li>
 *   <li>队列容量 2000：大缓冲防止任务拒绝，同时控制内存占用</li>
 *   <li>CallerRunsPolicy：队列满时由调用方线程兜底执行，确保审计事件不丢失</li>
 *   <li>优雅关闭：等待已提交任务完成（最长 30 秒）后再关闭</li>
 * </ul>
 */
@Configuration
public class AuditAsyncConfig {

    @Bean(name = "auditAsyncPool")
    public Executor auditAsyncPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 针对 10MB 大对象压测优化
        executor.setCorePoolSize(20); // 与压测并发数对齐
        executor.setMaxPoolSize(40);
        executor.setQueueCapacity(500); // 减小队列，防止过多大对象在内存积压
        executor.setThreadNamePrefix("audit-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 专用存储线程池：负责耗时的压缩与上传 I/O
     */
    @Bean(name = "auditStoragePool")
    public Executor auditStoragePool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100); // 严格限制在内存中排队的 10MB 任务数
        executor.setThreadNamePrefix("audit-storage-");
        // 关键：队列满时由调用者执行（即退化为同步），起到背压作用，保护内存不 OOM
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
    }
