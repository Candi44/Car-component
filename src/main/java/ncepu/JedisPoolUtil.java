package ncepu;

import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisConnectionException;
import java.net.SocketException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class JedisPoolUtil {
    private static final String REDIS_HOST = "192.168.43.69";
    private static final int REDIS_PORT = 6379;
    private static final int HEALTH_CHECK_INTERVAL = 5; // 健康检查间隔(秒)

    private static volatile JedisPool jedisPool;
    private static final AtomicBoolean isHealthy = new AtomicBoolean(true);
    private static final ScheduledExecutorService healthScheduler = Executors.newSingleThreadScheduledExecutor();

    // 私有构造器防止实例化
    private JedisPoolUtil() {}

    // 初始化连接池
    public static synchronized void initialize() {
        if (jedisPool == null || jedisPool.isClosed()) {
            JedisPoolConfig config = new JedisPoolConfig();
            config.setMaxTotal(100);
            config.setMaxIdle(20);
            config.setMinIdle(5);
            config.setTestWhileIdle(true);
            config.setMinEvictableIdleTime(Duration.ofSeconds(60)); //去除空闲60s的连接
            config.setTimeBetweenEvictionRuns(Duration.ofSeconds(30)); //30秒检测
            // 添加连接有效性检测
            config.setTestOnBorrow(true);
            config.setTestOnReturn(true);
            config.setTestWhileIdle(true);

            jedisPool = new JedisPool(config, REDIS_HOST, REDIS_PORT);
            System.out.println("Redis连接池初始化完成");

            // 启动健康检查
            startScheduledHealthCheck();
        }
    }

    // 启动定时健康检查
    private static void startScheduledHealthCheck() {
        // 每5秒执行一次健康检查
        healthScheduler.scheduleAtFixedRate(
                JedisPoolUtil::performHealthCheck,
                0, // 立即启动
                HEALTH_CHECK_INTERVAL,
                TimeUnit.SECONDS
        );

        // 注册JVM关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            healthScheduler.shutdown();
            try {
                if (!healthScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    healthScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                healthScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }));
    }

    // 执行健康检查
    private static void performHealthCheck() {
        if (jedisPool == null || jedisPool.isClosed()) {
            isHealthy.set(false);
            System.err.println("[健康检查] 连接池未初始化或已关闭");
            return;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String response = jedis.ping();
            isHealthy.set("PONG".equals(response));

            if (isHealthy.get()) {
                System.out.println("[健康检查] Redis连接正常");
            } else {
                System.err.println("[健康检查] Ping测试失败 - 响应: " + response);
            }
        } catch (JedisConnectionException | NullPointerException e) {
            isHealthy.set(false);

            if (e.getCause() instanceof SocketException) {
                System.err.println("[健康检查] 检测到Socket重置连接");
            } else {
                System.err.println("[健康检查] 连接检查异常: " + e.getMessage());
            }
        }
    }

    // 获取Redis连接
    public static Jedis getConnection() {
        if(isConnectionHealthy()) {
            initialize(); // 确保连接池已初始化
            return jedisPool.getResource();
        }
        return null;
    }

    // 获取连接状态
    public static boolean isConnectionHealthy() {
        return isHealthy.get();
    }

    // 设置状态值
    public static void setCarStatus(int status) {
        try (Jedis jedis = getConnection()) {
            jedis.set("IsCarOpen", String.valueOf(status));
            System.out.println("更新状态: IsCarOpen=" + status);
        } catch (Exception e) {
            System.err.println("设置状态失败: " + e.getMessage());
        }
    }

    // 关闭连接池
    public static void shutdown() {
        synchronized (JedisPoolUtil.class) {
            if (jedisPool != null && !jedisPool.isClosed()) {
                healthScheduler.shutdown();
                jedisPool.close();
                System.out.println("Redis连接池已安全关闭");
            }
        }
    }
}