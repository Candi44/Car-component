package ncepu;

import java.util.concurrent.*;

/**
 * 线程池管理类（全局单例）
 * 功能：统一调度小车移动任务，控制并发量
 */
public class CarThreadPool {
    private static final int CORE_POOL_SIZE = 10;
    private static final int MAX_POOL_SIZE = 20;
    private static final long KEEP_ALIVE_TIME = 30L;
    private static final BlockingQueue<Runnable> WORK_QUEUE = new LinkedBlockingQueue<>(100);

    static final ExecutorService executor = new ThreadPoolExecutor(
            CORE_POOL_SIZE,
            MAX_POOL_SIZE,
            KEEP_ALIVE_TIME,
            TimeUnit.SECONDS,
            WORK_QUEUE,
            new ThreadPoolExecutor.AbortPolicy()
    );

    public static void submitTask(Runnable task) {
        executor.execute(task);
    }

    public static void shutdown() {
        executor.shutdown();
    }
}