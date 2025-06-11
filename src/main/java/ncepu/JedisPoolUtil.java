package ncepu;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Redis连接池工具类（单例模式）
 * 功能：统一管理Redis连接，优化资源使用
 */
public class JedisPoolUtil {
    private static final String REDIS_HOST = "192.168.43.69";
    private static final int REDIS_PORT = 6379;
    private static JedisPool jedisPool;

    static {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(100);      // 最大连接数
        config.setMaxIdle(20);       // 最大空闲连接
        config.setMinIdle(5);        // 最小空闲连接
        jedisPool = new JedisPool(config, REDIS_HOST, REDIS_PORT);
    }

    // 添加初始化方法
    public static void init(JedisPool pool) {
        if (jedisPool == null) {
            jedisPool = pool;
        }
    }

    // 提供全局访问点
    public static JedisPool getJedisPool() {
        if (jedisPool == null) {
            throw new IllegalStateException("连接池未初始化");
        }
        return jedisPool;
    }

}