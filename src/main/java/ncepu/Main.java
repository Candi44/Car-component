package ncepu;

import ncepu.CarMessageListener;
import ncepu.CarThreadPool;
import org.apache.activemq.ActiveMQConnectionFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import javax.jms.Connection;
import javax.jms.JMSException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 系统主入口
 * 功能：初始化监听器，注册关闭钩子
 */
public class Main {
    private static final String REDIS_HOST = "192.168.43.69";
    private static final int REDIS_PORT = 6379;
    private static final String CAR_STATUS_KEY = "IsCarOpen";
    static JedisPool jedisPool;

    public static void main(String[] args) {
        try {
            // 1. 初始化Redis连接池
            jedisPool = createJedisPool();
            setCarStatus(1); // 启动时设置标志位为1

            // 2. 注入连接池到小车组件
            Car.setJedisPool(jedisPool);

            // 3. 初始化消息监听器
            CarMessageListener listener = new CarMessageListener();
            listener.initConnection();
            listener.startListening();
            System.out.println("=== ActiveMQ连接成功 ===");
            // 4. 注册关闭钩子（Ctrl+C或系统关闭时触发）
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                shutdownProcedure(listener);
            }));

            System.out.println("=== 小车组件启动成功 ===");

        } catch (JMSException e) {
            System.err.println("初始化失败: " + e.getMessage());
            setCarStatus(0); // 异常时清理标志位
            System.exit(1);
        }
    }

    /**
     * 创建Redis连接池
     */
    static JedisPool createJedisPool() {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(20); // 最大连接数
        config.setMaxIdle(5);   // 空闲连接数
        return new JedisPool(config, REDIS_HOST, REDIS_PORT);
    }

    /**
     * 系统关闭时的清理操作
     */
    static void shutdownProcedure(CarMessageListener listener) {
        try {
            System.out.println("\n=== 正在关闭系统... ===");
            // 关闭MQ监听
            if (listener != null) {
                listener.closeConnection();
            }
            //关闭小车线程池
            Car.cleanupThreadPool();
            //更新状态标志
            setCarStatus(0);
            //释放Redis连接池
            if (jedisPool != null) {
                jedisPool.close();
            }
            System.out.println("=== 系统已安全关闭 ===");
        } catch (JMSException e) {
            System.err.println("关闭异常: " + e.getMessage());
        }
    }

    /**
     * 原子化设置小车状态标志位
     *
     * @param status 1-运行中, 0-已停止
     */
    static void setCarStatus(int status) {//修改IscaarOpen
        try (var jedis = jedisPool.getResource()) {
            jedis.set(CAR_STATUS_KEY, String.valueOf(status));
            System.out.println("更新状态: IsCarOpen=" + status);
        } catch (Exception e) {
            System.err.println("状态更新失败: " + e.getMessage());
        }
    }
}