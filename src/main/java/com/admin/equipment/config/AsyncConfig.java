package com.admin.equipment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** 异步执行配置：趋势预警评估在记录提交后于独立线程池执行，不阻塞巡检写入。 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "trendEvaluationExecutor")
    public ThreadPoolTaskExecutor trendEvaluationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("trend-eval-");
        // 队列满时由调用线程执行，避免评估任务被丢弃
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
