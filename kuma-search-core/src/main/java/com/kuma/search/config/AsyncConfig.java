package com.kuma.search.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步与定时任务配置。
 *
 * <p>刻意<b>不使用</b> {@code @Async} 的默认 SimpleAsyncTaskExecutor：
 * 它为每个任务新建线程且不复用，在索引事件突发时会瞬间创建大量线程。
 *
 * <h3>队列满了怎么办</h3>
 * 这里选 {@link ThreadPoolExecutor.CallerRunsPolicy}，即由提交任务的线程自己执行。
 * 这是一个有意的<b>反压（back pressure）</b>选择：索引事件的源头是 MySQL 写入，
 * 让业务线程被拖慢，好过静默丢弃事件导致 MySQL 与 ES 数据不一致。
 * 注意这会让管理后台的录入接口在高负载下变慢 —— 这是可接受的，因为后台是低频路径。
 */
@Slf4j
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /** 索引事件处理线程池：负责消费 Outbox 事件并写入 ES。 */
    @Bean("indexEventExecutor")
    public Executor indexEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int processors = Runtime.getRuntime().availableProcessors();
        executor.setCorePoolSize(Math.max(4, processors));
        executor.setMaxPoolSize(Math.max(8, processors * 2));
        executor.setQueueCapacity(2000);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("kuma-index-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅停机：等待正在写入 ES 的任务结束，避免任务被中断造成事件状态不一致。
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();

        // JDK 21 起可改为虚拟线程（executor.setVirtualThreads(true)）以获得更高的并发承载能力，
        // 但前提是任务本身不是 CPU 密集型。当前项目基线为 JDK 17，见 docs/07-deployment.md 的升级路径。
        log.info("索引事件线程池初始化完成，corePoolSize={}，maxPoolSize={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize());
        return executor;
    }

    /** 搜索日志落库线程池：与检索主链路隔离，避免日志写库拖慢查询。 */
    @Bean("searchLogExecutor")
    public Executor searchLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(5000);
        executor.setThreadNamePrefix("kuma-log-");
        // 日志可以丢：队列满时直接丢弃，绝不反压到检索链路。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
