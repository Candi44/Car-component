package ncepu;

import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisConnectionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class JedisPoolUtil {
    private static final String REDIS_HOST = "192.168.43.69";
    private static final int REDIS_PORT = 6379;
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_RETRY_DELAY = 10000;
    private static final long HEALTH_CHECK_INTERVAL = 5; // 健康检查间隔（秒）

    private static volatile JedisPool jedisPool;
    private static final AtomicBoolean isHealthy = new AtomicBoolean(false);
    private static final ScheduledExecutorService healthChecker = Executors.newSingleThreadScheduledExecutor();

    // 私有构造器防止实例化
    private JedisPoolUtil() {}

    // 初始化连接池（添加健康检查）
    public static synchronized void initialize() {
        if (jedisPool == null || jedisPool.isClosed()) {
            JedisPoolConfig config = new JedisPoolConfig();
            config.setMaxTotal(100);
            config.setMaxIdle(20);
            config.setMinIdle(5);
            config.setTestOnBorrow(true);
            config.setTestWhileIdle(true);
            config.setTimeBetweenEvictionRunsMillis(30000);

            jedisPool = new JedisPool(config, REDIS_HOST, REDIS_PORT);
            System.out.println("Redis连接池初始化完成");

            // 启动健康检查
            startHealthCheck();
        }
    }

    // 启动健康检查任务
    private static void startHealthCheck() {
        healthChecker.scheduleAtFixedRate(() -> {
            checkConnectionHealth();
        }, HEALTH_CHECK_INTERVAL, HEALTH_CHECK_INTERVAL, TimeUnit.SECONDS);

        // 添加JVM关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            healthChecker.shutdown();
            try {
                if (!healthChecker.awaitTermination(5, TimeUnit.SECONDS)) {
                    healthChecker.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }

    // 检查连接健康状况
    private static void checkConnectionHealth() {
        if (jedisPool == null || jedisPool.isClosed()) {
            isHealthy.set(false);
            System.err.println("[健康检查] 连接池未初始化或已关闭");
            return;
        }

        // 尝试使用新的直接连接测试Redis服务是否可用
        if (!isRedisServerAvailable()) {
            isHealthy.set(false);
            System.err.println("[健康检查] Redis服务不可用");
            closePoolQuietly();
            return;
        }

        // 当Redis服务可用但连接池有问题时进行重建
        try {
            // 获取资源时可能失败
            try (Jedis jedis = jedisPool.getResource()) {
                if ("PONG".equals(jedis.ping())) {
                    isHealthy.set(true);
                    System.out.println("[健康检查] Redis连接正常");
                } else {
                    isHealthy.set(false);
                    System.err.println("[健康检查] Ping测试失败 - 重建连接池");
                    reconnect();
                }
            }
        } catch (JedisConnectionException | NullPointerException e) {
            System.err.println("[健康检查] 获取连接资源失败: " + e.getMessage());
            handleFailedPool();
        }
    }

    // 直接测试Redis服务器是否可用（不使用连接池）
    private static boolean isRedisServerAvailable() {
        // 创建自定义连接配置
        DefaultJedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(2000)  // 连接超时设置
                .socketTimeoutMillis(2000)      // 读写超时设置
                .build();

        try (Jedis directJedis = new Jedis(new HostAndPort(REDIS_HOST, REDIS_PORT), clientConfig)) {
            return "PONG".equals(directJedis.ping());
        } catch (Exception e) {
            return false;
        }
    }
    private static synchronized void handleFailedPool() {
        isHealthy.set(false);
        closePoolQuietly();

        // 重建连接池
        try {
            initialize();
            System.out.println("[健康检查] 重建连接池完成");
        } catch (Exception e) {
            System.err.println("[健康检查] 重建连接池失败: " + e.getMessage());
        }

    }
    private static void closePoolQuietly() {
        try {
            if (jedisPool != null && !jedisPool.isClosed()) {
                jedisPool.close();
                System.out.println("[健康检查] 连接池已关闭");
            }
        } catch (Exception e) {
            System.err.println("关闭连接池时出错: " + e.getMessage());
        }
    }


    // 获取Redis连接（封装重连）- 原有方法保持不变
    public static Jedis getConnection() {
        if (!isRedisServerAvailable()) {
            throw new JedisConnectionException("Redis服务不可用");
        }

        initialize(); // 确保连接池已初始化


        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try (Jedis jedis = jedisPool.getResource()) {
                if ("PONG".equals(jedis.ping())) {
                    return jedisPool.getResource(); // 返回新连接
                }
                throw new JedisConnectionException("Ping测试失败");
            } catch (JedisConnectionException | NullPointerException e) {
                handleConnectionFailure(e, attempt);
                reconnect();
            }
        }

        throw new JedisConnectionException("无法连接到Redis服务端");
    }

    // 获取连接状态
    public static boolean isConnectionHealthy() {
        return isHealthy.get();
    }

    // 设置状态值（业务层只需调用此方法，无需关心连接）- 原有方法保持不变
    public static void setCarStatus(int status) {
        executeWithRetry(jedis -> {
            jedis.set("IsCarOpen", String.valueOf(status));
            System.out.println("更新状态: IsCarOpen=" + status);
            return null;
        }, "setCarStatus");
    }

    // 通用重试执行方法（业务层API）- 原有方法保持不变
    public static <T> T executeWithRetry(RedisOperation<T> operation, String operationName) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try (Jedis jedis = getConnection()) {
                return operation.execute(jedis);
            } catch (JedisConnectionException e) {
                handleConnectionFailure(e, attempt);
                reconnect();
            } catch (Exception e) {
                System.err.println("操作[" + operationName + "]失败: " + e.getMessage());
                return null;
            }
        }
        throw new JedisConnectionException("操作[" + operationName + "]失败，重试" + MAX_RETRIES + "次");
    }

    // 关闭连接池 - 原有方法保持不变
    public static void shutdown() {
        synchronized (JedisPoolUtil.class) {
            if (jedisPool != null && !jedisPool.isClosed()) {
                jedisPool.close();
                System.out.println("Redis连接池已安全关闭");
            }
        }
    }

    // ======== 私有方法 ========

    // 处理连接失败 - 原有方法保持不变
    private static void handleConnectionFailure(Exception e, int attempt) {
        System.err.println("Redis连接失败 (" + (attempt + 1) + "/" + MAX_RETRIES + "): " + e.getMessage());

        if (attempt < MAX_RETRIES - 1) {
            try {
                long waitTime = (long) (INITIAL_RETRY_DELAY * Math.pow(2, attempt));
                System.out.println("等待 " + waitTime + "毫秒后重试...");
                Thread.sleep(waitTime);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // 重新连接 - 原有方法保持不变
    private static synchronized void reconnect() {
        System.out.println("尝试重建Redis连接池...");
        if (jedisPool != null) {
            jedisPool.close();
        }
        initialize();
    }

    // 操作接口 - 原有接口保持不变
    @FunctionalInterface
    public interface RedisOperation<T> {
        T execute(Jedis jedis) throws Exception;
    }
}