package ncepu;

import ncepu.CarMessageListener;

import javax.jms.JMSException;

public class Main {
    public static void main(String[] args) {
        try {
            // 初始化Redis连接池
            JedisPoolUtil.initialize();

            // 设置初始状态
            JedisPoolUtil.setCarStatus(1);

            // 初始化小车连接器
            Car.setJedisProvider(JedisPoolUtil::getConnection);

            // 初始化消息监听器
            CarMessageListener listener = new CarMessageListener();
            listener.initConnection();
            listener.startListening();
            System.out.println("==activeMQ连接成功==");
            // 注册关闭钩子
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                shutdownProcedure(listener);
            }));
            System.out.println("====系统启动成功====");
        } catch (JMSException e) {
            System.err.println("启动失败: " + e.getMessage());
            JedisPoolUtil.setCarStatus(0);
            System.exit(1);
        }
    }

    private static void shutdownProcedure(CarMessageListener listener) {
        try {
            // 关闭MQ监听
            if (listener != null) listener.closeConnection();

            // 关闭小车资源
            Car.cleanup();

            // 更新状态
            JedisPoolUtil.setCarStatus(0);

            // 关闭连接池
            JedisPoolUtil.shutdown();
            System.out.println("====系统关闭成功====");
        } catch (JMSException e) {
            System.err.println("==关闭异常: " + e.getMessage()+"==");
        }
    }
}