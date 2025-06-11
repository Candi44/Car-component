package ncepu;
import org.apache.activemq.command.JournalQueueAck;
import redis.clients.jedis.*;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 小车控制类（线程安全）
 * 功能：处理移动逻辑，保证Redis操作原子性
 */
class Car {
    private final String carId;
    int mapWidth;
    int mapLength;
    private Position currentPos;
    private static JedisPool jedisPool;
    private static final ExecutorService threadPool =
            Executors.newFixedThreadPool(10);  // 根据需求配置线程数

    public static void cleanupThreadPool() {
        if (!threadPool.isShutdown()) {
            threadPool.shutdownNow();
        }
    }

    // 添加关闭钩子（避免内存泄漏）
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(threadPool::shutdownNow));
    }

    public Car(String carId) {
        this.carId = carId;
    }//Car属性Id

    public static void setJedisPool(JedisPool pool) {//初始化连接池
        if (jedisPool == null) {
            jedisPool = pool;
        }
    }

    public void initialize() {//初始化
        long time = System.currentTimeMillis();
        try (Jedis jedis = jedisPool.getResource()) {
            String mapWidthStr = jedis.get("mapWidth");
            String mapLengthStr = jedis.get("mapLength");
            //设置默认值
            this.mapWidth = (mapWidthStr != null) ? Integer.parseInt(mapWidthStr) : 10;
            this.mapLength = (mapLengthStr != null) ? Integer.parseInt(mapLengthStr) : 10;
            //获取小车位置
            String posKey = positionKey();
            String posStr = jedis.get(posKey);
            if (posStr == null) {
                System.out.println("[" + carId + "]< 未找到小车坐标 >");
            }

        }
    }


    public synchronized void moveStep() {
        threadPool.execute(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                System.out.println("[ " + Thread.currentThread().getName() + " ][ 开始处理小车" + carId + " ]");
                //获取下一步
                String nextPosStr = jedis.lpop(routeKey());
                if (nextPosStr == null) return;
                //解析下一步坐标
                Position target = parsePosition(nextPosStr);
                if (/*CheckTask(jedis) &&*/ tryMove(jedis, target)) {
                    updatePosition(jedis, target);
                    System.out.println("[" + carId + "]< 小车" + carId + "移动到" + nextPosStr + " >");
                }
            } catch (Exception e) {
                System.err.println("[" + carId + "]移动异常: " + e.getMessage());
            } finally {
                System.out.println("[ " + Thread.currentThread().getName() + " ][ 任务结束 ]");
            }
        });
    }

    //检查小车路径是否为全亮
    private boolean CheckTask(Jedis jedis)
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

    //障碍物检测
    private boolean tryMove(Jedis jedis, Position target) {
        boolean result = false;
        System.out.println("[" + carId + "]< 检查障碍: " + target + " >");
        jedis.watch(obstacleKey());
       try {
            if (isObstacle(jedis, target)) {
                handleObstacle(jedis);
            } else {
                result = true;
            }
        } finally {
            jedis.unwatch();
        }
        return result;
    }


    // 更新位置方法
    private void updatePosition(Jedis jedis, Position newPos) {
        Position currentPos = parsePosition(jedis.get(positionKey()));
        try (Transaction tx = jedis.multi()) {
            //清除旧位置的障碍标记
            tx.setbit(obstacleKey(), offset(currentPos), false);
            //记录小车路径
            tx.rpush("Car"+carId+"Path",newPos.x + ","+ newPos.y+"|"+System.currentTimeMillis());
            //更新小车位置
            tx.set(positionKey(), newPos.x + "," + newPos.y);
            //设置新位置的障碍标记
            tx.setbit(obstacleKey(), offset(newPos), true);
            //更新探索地图（位图操作）
            updateExploredMap(tx, newPos);
            tx.exec();
        } catch (Exception e) {
            System.err.println("["+carId+"]"+carId+"小车事务失败: " + e.getMessage());
        }
    }

    // 更新点亮地图
    private void updateExploredMap(Transaction tx, Position center) {
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
        Transaction tx = jedis.multi();
        tx.sadd("obstacle_events", carId);
        tx.del(routeKey());
        //tx.sadd(routeKey());
        tx.exec();
    }

    // 协助方法（解析位移量、解析坐标）
    private int offset(Position pos) {
        return pos.y * mapWidth + pos.x;
    }

    private int offset(int x, int y) {
        return y * mapWidth + x;
    }

    private Position parsePosition(String str) {
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