package com.example.seckillsystem.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.*;

@Configuration
public class ThreadPoolConfig {

    @Bean
    public ExecutorService seckillExecutorService() {
        ThreadFactory namedThreadFactory = r -> {
            Thread t = new Thread(r);
            // 【关键改动】将线程设置为非守护线程
            t.setDaemon(false);
            t.setName("seckill-thread-" + t.hashCode());
            return t;
        };

        // 创建线程池的其余代码保持不变
        ExecutorService pool = new ThreadPoolExecutor(
                10,
                20,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                namedThreadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
        return pool;
    }
}