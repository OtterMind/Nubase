package ai.nubase.cron;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(value = "nubase.cron.enabled", havingValue = "true", matchIfMissing = true)
public class CronExecutorConfig {

    private final CronProperties properties;

    @Bean(name = "cronJobExecutor")
    public Executor cronJobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("nubase-cron-job-");
        executor.setCorePoolSize(Math.max(1, properties.getMaxConcurrentJobs()));
        executor.setMaxPoolSize(Math.max(1, properties.getMaxConcurrentJobs()));
        executor.setQueueCapacity(Math.max(0, properties.getExecutionQueueCapacity()));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
