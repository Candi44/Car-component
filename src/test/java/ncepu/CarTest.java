package ncepu;

import org.junit.jupiter.api.*;
import org.mockito.*;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import javax.jms.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CarMessageListenerTest {

    @Mock Connection mockConnection;
    @Mock Session mockSession;
    @Mock Queue mockQueue;
    @Mock MessageConsumer mockConsumer;
    @Mock TextMessage mockMessage;

    @InjectMocks CarMessageListener listener;

    @BeforeEach
    void setUp() throws JMSException {
        MockitoAnnotations.openMocks(this);

        // 模拟连接和会话创建
        when(mockConnection.createSession(anyBoolean(), anyInt())).thenReturn(mockSession);
        when(mockSession.createQueue(anyString())).thenReturn(mockQueue);
        when(mockSession.createConsumer(any(Queue.class))).thenReturn(mockConsumer);
    }

    @Test
    void testInitConnection() throws JMSException {
        // 执行初始化
        listener.initConnection();

        // 验证连接启动
        verify(mockConnection).start();
    }

    @Test
    void testMessageHandling_ValidCarId() throws JMSException {
        // 1. 设置模拟消息
        when(mockMessage.getText()).thenReturn("\"car001\"");

        // 2. 触发消息接收
        ArgumentCaptor<MessageListener> captor = ArgumentCaptor.forClass(MessageListener.class);

        // 3. 启动监听
        listener.startListening();

        // 4. 捕获注册的监听器并模拟消息接收
        verify(mockConsumer).setMessageListener(captor.capture());
        captor.getValue().onMessage(mockMessage);

        // 5. 验证消息处理
        verify(mockMessage).getText();
        // 这里可以添加对handleCommand行为的验证
    }

    @Test
    void testMessageHandling_EmptyMessage() throws JMSException {
        when(mockMessage.getText()).thenReturn("");

        ArgumentCaptor<MessageListener> captor = ArgumentCaptor.forClass(MessageListener.class);

        listener.startListening();
        verify(mockConsumer).setMessageListener(captor.capture());
        captor.getValue().onMessage(mockMessage);

        verify(mockMessage).getText();
    }

    @Test
    void testCloseConnection() throws JMSException {
        listener.closeConnection();
        verify(mockConnection).close();
    }
}