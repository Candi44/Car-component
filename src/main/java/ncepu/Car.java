package ncepu;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
/**
 * 小车控制类（线程安全）
 * 功能：处理移动逻辑，保证Redis操作原子性
 */
public class Car {
    private final String carId;
    int mapWidth;
    int mapLength;

    private static Supplier<Jedis> jedisProvider;
    private static final ExecutorService threadPool = Executors.newFixedThreadPool(10);

    public Car(String carId) {
        this.carId = carId;
    }

    public static void setJedisProvider(Supplier<Jedis> provider) {
        if (jedisProvider == null) {
            jedisProvider = provider;
        }
    }

    public static void cleanup() {
        if (!threadPool.isShutdown()) {
            threadPool.shutdownNow();
        }
    }

    public void initialize() {
        if (jedisProvider == null) {
            throw new IllegalStateException("Jedis provider not set");
        }

        Jedis jedis = jedisProvider.get();
        try {
            this.mapWidth = getIntegerConfig(jedis, "mapWidth", 10);
            this.mapLength = getIntegerConfig(jedis, "mapLength", 10);

            if (!hasPosition(jedis)) {
                System.out.println("[" + carId + "]< 未找到小车坐标 >");
            }
        } finally {
            if (jedis != null) jedis.close();
        }
    }

    // 移动方法
    public synchronized void moveStep() {
        threadPool.execute(() -> {
            Jedis jedis = null;
            try {
                System.out.println("["+carId+"][" + Thread.currentThread().getName() + " Start]");
                jedis = jedisProvider.get();
                Position target = getNextPosition(jedis);

                if(target == null) {
                    System.out.println("["+carId+"]未收到小车坐标");
                    return;
                }
                if (tryMove(jedis, target)) {
                    updatePosition(jedis, target);
                    System.out.println("[" + carId + "]< 小车" + carId + "移动到" + target + " >");
                }


            } catch (Exception e) {
                System.err.println("[" + carId + "]移动异常: " + e.getMessage());
            } finally {
                System.out.println("["+carId+"][ " + Thread.currentThread().getName() + " Finnish ]");
                if (jedis != null) jedis.close();
            }
        });
    }



    //检查小车路径是否为全亮
    boolean CheckTask(Jedis jedis)
    {
        List<String> task = jedis.lrange(routeKey(), 0, -1);
        boolean result = false;
        for(int i = 0; i<task.size(); i++)
        {
            if(!jedis.getbit("map",offset(parsePosition(task.get(i)))))//小车路径有未点亮区域
                result = true;
        }
        if(!result) {
            System.out.println("[" + carId + "]< 小车"+carId+"路径全亮 >");
            System.out.println("[" + carId + "]< 删除任务队列"+carId+" >");
            Transaction tx = jedis.multi();
            tx.del(routeKey());
            //tx.sadd(routeKey());
            tx.exec();

        }
        return result;
    }



    private boolean tryMove(Jedis jedis, Position target) {
        jedis.watch(obstacleKey());
        try {

            if (!isObstacle(jedis, target)) {
                return true;
            }
            handleObstacle(jedis);
            return false;
        } finally {
            jedis.unwatch();
        }
    }

    private void updatePosition(Jedis jedis, Position newPos) {
        Position currentPos = parsePosition(jedis.get(positionKey()));
        try {
            Transaction tx = jedis.multi();
            tx.setbit(obstacleKey(), offset(currentPos), false);
            tx.rpush("Car" + carId + "Path", newPos.toString() + "|" + System.currentTimeMillis());
            tx.set(positionKey(), newPos.toString());
            tx.setbit(obstacleKey(), offset(newPos), true);
            updateExploredMap(tx, newPos);
            tx.exec();
        }

        catch (Exception e) {
            System.err.println("["+carId+"]"+carId+"小车移动失败: " + e.getMessage());
            throw new RuntimeException(e);
        }

    }

    // 更新点亮地图
    void updateExploredMap(Transaction tx, Position center) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                int x = center.x + dx;
                int y = center.y + dy;

                // X方向边界：0到mapWidth-1
                // Y方向边界：0到mapLength-1
                if (x >= 0 && x < mapWidth &&
                        y >= 0 && y < mapLength) {

                    int offset = offset(x, y);
                    tx.setbit(mapKey(), offset, true);
                }
            }
        }
    }

    //检查面前是否为障碍物
    private boolean isObstacle(Jedis jedis, Position pos) {
        int offset = offset(pos);
        return jedis.getbit(obstacleKey(), offset);
    }

    //如果是障碍物清空队列并上报
    private void handleObstacle(Jedis jedis) {
        System.out.println("["+carId+"]< 检测到障碍，清空队列 >");
        try {
            Transaction tx = jedis.multi();
            tx.sadd("obstacle_events", carId);
            tx.del(routeKey());
            //tx.sadd(routeKey());
            tx.exec();
        }catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // 协助方法（解析位移量、解析坐标等等）
    private int getIntegerConfig(Jedis jedis, String key, int defaultValue) {
        String valueStr = jedis.get(key);
        return (valueStr != null) ? Integer.parseInt(valueStr) : defaultValue;
    }

    private boolean hasPosition(Jedis jedis) {
        return jedis.exists(positionKey());
    }

    private Position getNextPosition(Jedis jedis) {
        String nextPosStr = jedis.lpop(routeKey());
        return (nextPosStr != null) ? parsePosition(nextPosStr) : null;
    }

    int offset(Position pos) {
        return pos.y * mapWidth + pos.x;
    }

    private int offset(int x, int y) {
        return y * mapWidth + x;
    }

    Position parsePosition(String str) {
        String[] parts = str.split(",");
        return new Position(
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1])
        );
    }

    // Redis key generators

    private String positionKey() {
        return "Car" + carId; // Car001
    }

    private String routeKey() {
        return "Car" + carId + "TaskList";
    }

    private String obstacleKey() {
        return "obstacle_map";
    }

    private String mapKey() {
        return "map";
    }

    static class Position {
        final int x;
        final int y;

        Position(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public String toString() {
            return x + "," + y;
        }
    }
}