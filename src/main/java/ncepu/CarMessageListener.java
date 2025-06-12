package ncepu;

import org.apache.activemq.ActiveMQConnectionFactory;
import javax.jms.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ActiveMQ消息监听器（带自动重连功能）
 * 功能：监听指定队列，接收小车启动指令，支持自动重连
 */
public class CarMessageListener {
    private static final String BROKER_URL = "failover:(tcp://192.168.43.69:61616)" +
            "?initialReconnectDelay=1000" +
            "&maxReconnectDelay=30000" +
            "&maxReconnectAttempts=-1" +  // 无限重试
            "&randomize=false";

    private Connection mqConnection;
    private Session session;
    private MessageConsumer consumer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean reconnectFlag = new AtomicBoolean(false);

    // 初始化MQ连接（带重连）
    public void initConnection() throws JMSException {
        running.set(true);
        establishConnection();
        System.out.println("[MQ] 连接初始化成功");
    }

    // 开始监听指令队列
    public void startListening() {
        reconnectFlag.set(false);
        new Thread(this::monitorConnection).start();
        System.out.println("[MQ] 监听线程已启动");
    }

    // 建立连接的核心方法
    private void establishConnection() throws JMSException {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(BROKER_URL);
        mqConnection = factory.createConnection();
        mqConnection.setExceptionListener(new CustomExceptionListener());
        mqConnection.start();

        session = mqConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Queue queue = session.createQueue("UpdateCar");
        consumer = session.createConsumer(queue);

        // 设置异步消息处理
        consumer.setMessageListener(message -> {
            try {
                if (message instanceof TextMessage) {
                    String text = ((TextMessage) message).getText();
                    if (text == null) {
                        System.err.println("消息内容为空");
                        return;
                    }
                    String carId = text.trim();
                    carId = carId.substring(1, carId.length() - 1);
                    if (carId.isEmpty()) {
                        System.err.println("收到空ID消息，已忽略");
                        return;
                    }

                    handleCommand(carId);
                }
            } catch (JMSException e) {
                System.err.println("消息处理失败: " + e.getMessage());
            }
        });
    }

    // 连接监控线程
    private void monitorConnection() {
        while (running.get()) {
            try {
                TimeUnit.SECONDS.sleep(5); // 每5秒检查一次连接

                // 检查连接状态
                if (reconnectFlag.get() || !isConnectionActive()) {
                    System.out.println("[MQ] 检测到连接异常，尝试重连...");
                    reconnect();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // 检查连接是否活跃
    private boolean isConnectionActive() {
        return mqConnection != null && !reconnectFlag.get();
    }

    // 重新连接逻辑
    private synchronized void reconnect() {
        if (!reconnectFlag.compareAndSet(true, true)) return; // 防止重入

        try {
            // 关闭旧连接
            closeResources();

            // 尝试建立新连接
            int attempts = 0;
            while (running.get()) {
                try {
                    attempts++;
                    establishConnection();
                    System.out.println("[MQ] 重连成功（尝试次数：" + attempts + ")");
                    reconnectFlag.set(false);
                    return;
                } catch (JMSException e) {
                    System.err.println("[MQ] 重连失败（尝试 " + attempts + "）: " + e.getMessage());
                    TimeUnit.SECONDS.sleep(Math.min(30, attempts * 2)); // 指数退避
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (running.get()) reconnectFlag.set(false);
        }
    }

    // 异常监听器
    private class CustomExceptionListener implements ExceptionListener {
        @Override
        public void onException(JMSException exception) {
            System.err.println("[MQ] 连接异常: " + exception.getMessage());
            reconnectFlag.set(true);
        }
    }

    // 处理小车指令
    void handleCommand(String carId) {
        System.out.println("[MQ] 收到指令: " + carId);
        Car car = new Car(carId);
        car.initialize();
        car.moveStep();
    }

    // 关闭连接
    public void closeConnection() throws JMSException {
        running.set(false);
        closeResources();
        System.out.println("[MQ] 连接已安全关闭");
    }

    // 资源关闭方法
    private void closeResources() {
        try {
            if (consumer != null) consumer.close();
        } catch (Exception e) {
            System.err.println("关闭消费者异常: " + e.getMessage());
        }
        try {
            if (session != null) session.close();
        } catch (Exception e) {
            System.err.println("关闭会话异常: " + e.getMessage());
        }
        try {
            if (mqConnection != null) mqConnection.close();
        } catch (Exception e) {
            System.err.println("关闭连接异常: " + e.getMessage());
        }
        consumer = null;
        session = null;
        mqConnection = null;
    }
}