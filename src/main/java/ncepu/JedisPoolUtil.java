package ncepu;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisConnectionException;

public class JedisPoolUtil {
    private static final String REDIS_HOST = "192.168.43.69";
    private static final int REDIS_PORT = 6379;
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_RETRY_DELAY = 10000;

    private static volatile JedisPool jedisPool;

    // 私有构造器防止实例化
    private JedisPoolUtil() {}

    // 初始化连接池
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
        }
    }

    // 获取Redis连接（封装重连）
    public static Jedis getConnection() {
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

    // 设置状态值（业务层只需调用此方法，无需关心连接）
    public static void setCarStatus(int status) {
        executeWithRetry(jedis -> {
            jedis.set("IsCarOpen", String.valueOf(status));
            System.out.println("更新状态: IsCarOpen=" + status);
            return null;
        }, "setCarStatus");
    }

    // 通用重试执行方法（业务层API）
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

    // 关闭连接池
    public static void shutdown() {
        synchronized (JedisPoolUtil.class) {
            if (jedisPool != null && !jedisPool.isClosed()) {
                jedisPool.close();
                System.out.println("Redis连接池已安全关闭");
            }
        }
    }

    // ======== 私有方法 ========

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

    private static synchronized void reconnect() {
        System.out.println("尝试重建Redis连接池...");
        if (jedisPool != null) {
            jedisPool.close();
        }
        initialize();
    }

    // 操作接口
    @FunctionalInterface
    public interface RedisOperation<T> {
        T execute(Jedis jedis) throws Exception;
    }
}