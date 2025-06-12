package ncepu;

import org.apache.activemq.ActiveMQConnectionFactory;
import javax.jms.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ActiveMQ消息监听器
 * 功能：监听指定队列，接收小车启动指令，支持异常触发重连
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

    // 初始化MQ连接
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

        mqConnection.setExceptionListener(e -> {
            System.err.println("[MQ] 触发连接异常监听器: " + e.getMessage());
            reconnectFlag.set(true);
        });

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
                        System.err.println("--消息内容为空--");
                        return;
                    }
                    String carId = text.trim();
                    carId = carId.substring(1, carId.length() - 1);
                    if (carId.isEmpty()) {
                        System.err.println("--忽略空信息--");
                        return;
                    }

                    handleCommand(carId);
                }
            } catch (JMSException e) {
                System.err.println("--消息处理失败--: " + e.getMessage());

                // 关键修改：捕获消息处理异常时触发重连
                if (running.get()) {
                    System.err.println("--触发消息处理异常重连--");
                    reconnectFlag.set(true);
                }
            } catch (Exception e) {
                System.err.println("--未知处理异常--: " + e.getMessage());

                // 关键修改：捕获其他异常时也触发重连
                if (running.get()) {
                    System.err.println("--触发未知异常重连--");
                    reconnectFlag.set(true);
                }
            }
        });
    }

    // 连接监控线程
    private void monitorConnection() {
        int attempts = 0;
        while (running.get()) {
            try {
                TimeUnit.SECONDS.sleep(5); // 每5秒检查一次连接

                // 检查连接状态
                if (reconnectFlag.get()&&attempts<5) {
                    attempts++;
                    System.out.println("[MQ] 检测到重连标志，尝试重连...");
                    reconnect();
                }
                else {
                    reconnectFlag.set(false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // 重新连接逻辑
    private synchronized void reconnect() {
        // 防止重入：如果已经在重连中则返回
            if (reconnectFlag.compareAndSet(true, true)) {
                System.out.println("[MQ] 已在重连中，跳过本次请求");
                return;
            }
        else {
            reconnectFlag.set(false);
        }

        try {
            // 关闭旧连接
            closeResources();

            // 尝试建立新连接
            int attempts = 0;
            while (running.get()&&attempts<5) {
                attempts++;
                try {
                    establishConnection();
                    System.out.println("[MQ] 重连成功（尝试次数：" + attempts + ")");
                    reconnectFlag.set(false);  // 重置重连标志
                    return;
                } catch (JMSException e) {
                    System.err.println("[MQ] 重连失败（尝试 " + attempts + "）: " + e.getMessage());
                    TimeUnit.SECONDS.sleep(Math.min(30, attempts * 2)); // 指数退避
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (running.get()) {
                reconnectFlag.set(false);
            }
        }
    }

    // 处理小车指令 (保持不变)
    void handleCommand(String carId) {
        System.out.println("[MQ] 收到指令: " + carId);
        Car car = new Car(carId);
        if(car.initialize()) {
            car.moveStep();
        }
    }

    // 关闭连接 (保持不变)
    public void closeConnection() throws JMSException {
        running.set(false);
        closeResources();
        System.out.println("[MQ] 连接已安全关闭");
    }

    // 资源关闭方法 (保持不变)
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