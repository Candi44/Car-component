# 智能小车控制系统
这是一个基于Redis的分布式小车控制系统，支持多小车实时路径规划、障碍检测和地图探索功能。

功能特性

🚗 小车移动控制与状态管理

🗺️ 实时地图探索与点亮机制

⚠️ 障碍物检测与事件上报

🔐 Redis操作的原子性保证

⚡ 多线程任务处理

📊 路径记录与追踪

技术栈

核心语言: Java 17

数据处理: Redis

连接管理: Jedis

依赖管理: Maven

并发处理: Java线程池

单元测试: JUnit 5 + Mockito

快速开始

前置要求

JDK 17+

Maven 3.8+

Redis 6.0+


# 克隆项目
git clone https://github.com/Candi44/Car-component



# 项目亮点
checkTaskList方法通过检测路径是否全亮以及终点附近3*3格子的亮灭来重置路径，保证探索的高效率

通过调用watch方法实现乐观锁，在小车读取obstaclemap时如果有其他小车实施了移动导致更改了障碍物地图，小车会立即取消读取，通过这种方法在不影响并发执行的情况下实现了并发读写不冲突

在redis中实施健康检测，保证在网络不稳定的情况下能够稳定链接不报错

在activeMQ的操作类中使用了failover协议，保证运输层的错误能够被捕获并且能重连
同时在重连类中也有心跳检测类捕获JMS消息异常，能够双重保证activeMQ的重连

Redis事务保证位置更新、路径记录、地图点亮操作的原子性
### 安装依赖
mvn clean install
配置Redis
在Redis中创建必要的基础数据：

### 设置地图尺寸
SET mapWidth 10
SET mapLength 10

### 设置小车初始位置
SET Car001 "0,0"

### 创建任务队列
LPUSH Car001TaskList "1,0"
运行系统
public class Main {
public static void main(String[] args) {
// 设置Redis连接提供器
Car.setJedisProvider(() -> new Jedis("localhost"));

        // 创建小车
        Car car = new Car("001");
        
        if (car.initialize()) {
            car.moveStep(); // 移动一步
        }
    }
}
# 关键类


### 小车移动控制（Car类）：

原子性操作：通过Redis事务（MULTI/EXEC）确保位置更新、路径记录、障碍物标记的原子性

路径感知：动态检查路径光照状态（CheckTask()），自动清除已完全点亮区域的任务

障碍物避障：实时检测目标位置障碍（tryMove()），发现障碍时清空任务队列并上报

地图更新：移动时点亮周围3×3区域（updateExploredMap()）

并发控制：固定线程池（10线程）管理多车移动任务

### 消息驱动架构（CarMessageListener类）

断线重连：指数退避策略（reconnect()），最大重连间隔30秒

异常监听：MQ连接异常监听器触发自动重连

指令处理：异步消息处理线程池，MQ消息解析后驱动小车移动

连接监控：独立线程每5秒检测连接状态

### Redis连接管理（JedisPoolUtil类）

健康检查：定时（5秒）执行PING命令检测连接健康状态

智能重连：3秒超时保护，重连失败时采用指数退避策略（最大重试5次）

资源回收：空闲连接自动回收（60秒），JVM关闭时安全释放资源

### 线程资源管理（CarThreadPool类）

全局线程池：最大20线程（核心10线程），队列容量100

拒绝策略：超任务时直接丢弃新任务（AbortPolicy）

# API设计

Redis数据结构

Key	类型	描述

Car<ID>	String	小车当前位置 (x,y)

Car<ID>TaskList	List	小车任务队列

obstacle_map	Bitmap	障碍物地图

map	Bitmap	探索地图 (已点亮区域)

obstacle_events	Set	障碍事件记录

# 事务操作
移动操作时执行的事务：
清除旧位置障碍标记
记录路径信息
更新小车位置
标记新位置障碍
更新探索地图
运行测试
mvn test
# 测试覆盖点:

小车初始化逻辑

正常移动流程

障碍检测处理

路径全亮判断

位置计算方法

# 部署建议

Redis集群：使用Redis Cluster提高可用性

连接池：使用JedisPool管理连接

监控：集成Redis监控工具

负载均衡：多实例部署小车服务

容器化：使用Docker部署


