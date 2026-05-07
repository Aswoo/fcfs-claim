package com.example.fcfsclaim.common.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT2S")
public class SchedulerConfig {

    @Bean
    public LockProvider lockProvider(RedisConnectionFactory factory) {
        return new RedisLockProvider(factory);
    }

    // @Scheduled 전용 스레드풀과 분리해서, 이벤트 종료 등 일회성 예약 작업에 사용
    // ThreadPoolTaskScheduler: 정해진 시각에 Runnable을 실행하는 스프링 스케줄러
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);           // 동시 이벤트 종료/시작 처리용
        scheduler.setThreadNamePrefix("event-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        return scheduler;
    }
}
