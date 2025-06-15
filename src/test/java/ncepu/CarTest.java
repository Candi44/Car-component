package ncepu;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CarTest {
    private Jedis jedisMock;
    private Car.Position startPosition;
    private Car.Position targetPosition;

    @BeforeEach
    void setUp() {
        jedisMock = mock(Jedis.class);
        Car.setJedisProvider(() -> jedisMock);

        // 初始化位置
        startPosition = new Car.Position(0, 0);
        targetPosition = new Car.Position(1, 0);

        // 设置地图尺寸
        when(jedisMock.get("mapWidth")).thenReturn("10");
        when(jedisMock.get("mapLength")).thenReturn("10");
    }

    @AfterEach
    void tearDown() {
        Car.cleanup();
    }

    @Test
    void initialize_Success() {
        try (MockedStatic<JedisPoolUtil> utilities = mockStatic(JedisPoolUtil.class)) {
            utilities.when(JedisPoolUtil::getConnection).thenReturn(jedisMock);

            // 模拟位置存在
            when(jedisMock.exists(CarTestUtils.CAR_KEY)).thenReturn(true);
            when(jedisMock.get(CarTestUtils.CAR_KEY)).thenReturn(startPosition.toString());

            Car car = new Car(CarTestUtils.CAR_ID);
            assertTrue(car.initialize());
            assertEquals(10, car.mapWidth);
            assertEquals(10, car.mapLength);
        }
    }

    @Test
    void moveStep_NormalMovement() {
        try (MockedStatic<JedisPoolUtil> utilities = mockStatic(JedisPoolUtil.class)) {
            utilities.when(JedisPoolUtil::getConnection).thenReturn(jedisMock);

            // 1. 确保位置存在且正确
            when(jedisMock.exists(CarTestUtils.CAR_KEY)).thenReturn(true);
            when(jedisMock.get(CarTestUtils.CAR_KEY)).thenReturn(startPosition.toString());

            // 2. 设置任务队列
            List<String> taskList = Collections.singletonList(targetPosition.toString());
            when(jedisMock.lrange(CarTestUtils.TASK_KEY, 0, -1)).thenReturn(taskList);
            when(jedisMock.lpop(CarTestUtils.TASK_KEY)).thenReturn(targetPosition.toString());

            // 3. 精确的位图模拟
            Map<Long, Boolean> mapStatus = new ConcurrentHashMap<>();

            // 当前位置点亮
            long startOffset = CarTestUtils.positionToOffset(startPosition, 10);
            mapStatus.put(startOffset, true);

            // 目标位置未点亮
            long targetOffset = CarTestUtils.positionToOffset(targetPosition, 10);
            mapStatus.put(targetOffset, false);

            // 目标位置周围区域全部未点亮
            List<Car.Position> surroundingPositions =
                    CarTestUtils.getSurroundingPositions(targetPosition, 10, 10);

            surroundingPositions.forEach(pos -> {
                long offset = CarTestUtils.positionToOffset(pos, 10);
                mapStatus.put(offset, false);
            });

            when(jedisMock.getbit(eq("map"), anyLong())).thenAnswer(invocation -> {
                long offset = invocation.getArgument(1);
                return mapStatus.getOrDefault(offset, false);
            });

            // 4. 障碍检查
            when(jedisMock.getbit(CarTestUtils.OBSTACLE_KEY, targetOffset))
                    .thenReturn(false);

            // 5. 模拟事务
            Transaction txMock = mock(Transaction.class);
            when(jedisMock.multi()).thenReturn(txMock);

            Car car = new Car(CarTestUtils.CAR_ID);
            assertTrue(car.initialize());
            car.moveStep();

            // 6. 等待异步任务执行
            CarTestUtils.sleep(1000);

            // 7. 验证关键操作
            verify(jedisMock).lpop(CarTestUtils.TASK_KEY);
            verify(jedisMock).multi();
            verify(txMock).setbit(CarTestUtils.OBSTACLE_KEY, startOffset, false);
            verify(txMock).set(CarTestUtils.CAR_KEY, targetPosition.toString());
            verify(txMock).rpush(eq(CarTestUtils.PATH_KEY), anyString());
        }
    }


    @Test
    void positionOperations() {
        // 测试坐标解析和偏移量计算
        Car.Position pos = new Car.Position(3, 4);
        assertEquals("3,4", pos.toString());

        assertEquals(43, CarTestUtils.positionToOffset(pos, 10));

        Car car = new Car("001");
        car.mapWidth = 10;
        assertEquals(15, car.offset(new Car.Position(5, 1)));
    }
}

class CarTestUtils {
    static final String CAR_ID = "001";
    static final String CAR_KEY = "Car001";
    static final String TASK_KEY = "Car001TaskList";
    static final String OBSTACLE_KEY = "obstacle_map";
    static final String PATH_KEY = "Car001Path";
    static final String MAP_KEY = "map";

    static int positionToOffset(Car.Position pos, int width) {
        return pos.y * width + pos.x;
    }

    static List<Car.Position> getSurroundingPositions(Car.Position center, int mapWidth, int mapHeight) {
        List<Car.Position> positions = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                int x = center.x + dx;
                int y = center.y + dy;
                if (x >= 0 && x < mapWidth && y >= 0 && y < mapHeight) {
                    positions.add(new Car.Position(x, y));
                }
            }
        }
        return positions;
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}