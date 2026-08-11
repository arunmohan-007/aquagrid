package com.aquagrid.platform.common.config;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Asynchronous execution policy.
 *
 * <p>Pools are <b>bounded and named</b>. An unbounded queue converts a downstream stall into an
 * out-of-memory kill, and a named thread is what makes a production thread dump readable.
 *
 * <p>The audit pool uses {@code CallerRunsPolicy}: if its queue saturates, the submitting thread
 * writes the row itself. Auditing degrades to synchronous rather than being dropped — silently
 * losing security evidence under load is not an acceptable failure mode.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig implements AsyncConfigurer {

    /** General-purpose pool for {@code @Async} work with no dedicated executor. */
    @Bean("applicationExecutor")
    public Executor applicationExecutor() {
        return pool("aq-async-", 4, 16, 500, new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean("auditExecutor")
    public Executor auditExecutor() {
        return pool("aq-audit-", 2, 4, 5_000, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /** Reserved for the IoT ingestion path (Module 18); sized for burst uplink traffic. */
    @Bean("telemetryExecutor")
    public Executor telemetryExecutor() {
        return pool("aq-telemetry-", 4, 32, 10_000, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Override
    public Executor getAsyncExecutor() {
        return applicationExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new SimpleAsyncUncaughtExceptionHandler();
    }

    private ThreadPoolTaskExecutor pool(String prefix, int coreSize, int maxSize, int queueCapacity,
                                        RejectedExecutionHandler rejectionHandler) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(rejectionHandler);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
