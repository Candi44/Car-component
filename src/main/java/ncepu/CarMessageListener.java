package ncepu;

import org.apache.activemq.ActiveMQConnectionFactory;
import javax.jms.*;

/**
 * ActiveMQ消息监听器
 * 功能：监听指定队列，接收小车启动指令
 */
public class CarMessageListener {
    private Connection mqConnection;
    private static final String BROKER_URL = "tcp://192.168.43.69:61616";

    // 初始化MQ连接
    public void initConnection() throws JMSException {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(BROKER_URL);
        this.mqConnection = factory.createConnection();
        this.mqConnection.start();
    }

    // 开始监听指令队列
    public void startListening() throws JMSException {
        Session session = mqConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Queue queue = session.createQueue("UpdateCar");
        MessageConsumer consumer = session.createConsumer(queue);

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

    // 处理小车指令（连接小车组件）
    void handleCommand(String carId) {
        System.out.println("[MQ][ 收到指令: " + carId+"]");
        Car car = new Car(carId);
        car.initialize();
        car.moveStep();
    }

    public void closeConnection() throws JMSException {
        if (mqConnection != null) {
            mqConnection.close();
            mqConnection = null; // 释放资源
        }
    }
}
