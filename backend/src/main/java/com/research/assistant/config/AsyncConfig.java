package com.research.assistant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Spring 异步线程池配置。
 * <p>
 * 替换项目中原有的裸 {@code new Thread()} 调用，使异步任务可监控、可复用、异常不吞没。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 通用任务执行器，供 @Async("taskExecutor") 使用。
     * <p>
     * 核心线程 4，最大 20，队列容量 200，拒绝策略 CallerRunsPolicy。
     * 线程名前缀 task-，便于日志排查。
     */
    @Bean(name = "taskExecutor")
    public TaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
