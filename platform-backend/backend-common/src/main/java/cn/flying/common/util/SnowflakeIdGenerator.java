package cn.flying.common.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.InitializingBean;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 雪花算法ID生成器（自动注入数据中心/节点ID）
 */
@Slf4j
@Component
public class SnowflakeIdGenerator implements InitializingBean {

    private static final long DEFAULT_START_TIMESTAMP = 1691087910202L;
    private static final int DATA_CENTER_ID_BITS = 5;
    private static final int WORKER_ID_BITS = 5;
    private static final int SEQUENCE_BITS = 12;

    // 最大值计算
    private static final int MAX_DATA_CENTER_ID = ~(-1 << DATA_CENTER_ID_BITS);
    private static final int MAX_WORKER_ID = ~(-1 << WORKER_ID_BITS);
    private static final int MAX_SEQUENCE = ~(-1 << SEQUENCE_BITS);

    // 移位偏移量
    private static final int WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final int DATA_CENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final int TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATA_CENTER_ID_BITS;

    //配置参数
    @Value("${snowflake.data-center-id:-1}")
    private int dataCenterId;

    @Value("${snowflake.worker-id:-1}")
    private int workerId;

    // 无锁化设计
    private final AtomicLock atomicLock = new AtomicLock();

    // 时钟回拨容忍阈值（毫秒）
    private static final long CLOCK_BACKWARDS_THRESHOLD_MS = 5_000;

    @Override
    public void afterPropertiesSet() {
        log.info("Snowflake ID Generator initializing...");
        autoConfigureIds();
    }

    /** 自动配置数据中心ID和节点ID */
    private void autoConfigureIds() {
        if (dataCenterId < 0 || dataCenterId > MAX_DATA_CENTER_ID) {
            int originalId = dataCenterId;
            dataCenterId = resolveDataCenterId(); // 自动获取逻辑（如系统环境变量）
            log.info("数据中心ID {} 无效，自动调整为: {}", originalId, dataCenterId);
        }
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            int originalId = workerId;
            workerId = resolveWorkerId(); // 自动获取逻辑（如IP哈希）
            log.info("工作节点ID {} 无效，自动调整为: {}", originalId, workerId);
        }
        validateIds();

        log.info("Snowflake IDs Initialization completed: dataCenterId={}, workerId={}", dataCenterId, workerId);
    }

    /** 生成ID（无锁化） */
    public long nextId() {
        long currentTimestamp = getCurrentTimestamp();
        long lastTs = atomicLock.getLastTimestamp();

        // 提前检测严重的时钟回拨（超过阈值直接失败）
        if (lastTs > 0 && currentTimestamp < lastTs) {
            long offset = lastTs - currentTimestamp;
            if (offset > CLOCK_BACKWARDS_THRESHOLD_MS) {
                throw new IllegalStateException(
                    "Clock moved backwards by " + offset + "ms, exceeds threshold " + CLOCK_BACKWARDS_THRESHOLD_MS + "ms");
            }
            log.warn("Clock moved backwards by {}ms, will wait in CAS loop", offset);
        }

        // CAS自旋更新序列（内部处理时钟回拨）
        return atomicLock.update(currentTimestamp, (ts, seq) -> {
            long timestampDelta = ts - DEFAULT_START_TIMESTAMP;
            return (timestampDelta << TIMESTAMP_SHIFT) |
                    ((long) dataCenterId << DATA_CENTER_ID_SHIFT) |
                    ((long) workerId << WORKER_ID_SHIFT) |
                    seq;
        });
    }
    
    /**
     * 解析雪花ID中的时间戳
     * @param id 雪花ID
     * @return 时间戳（毫秒）
     */
    public long extractTimestamp(long id) {
        return ((id >> TIMESTAMP_SHIFT) + DEFAULT_START_TIMESTAMP);
    }
    
    /**
     * 解析雪花ID中的数据中心ID
     * @param id 雪花ID
     * @return 数据中心ID
     */
    public int extractDataCenterId(long id) {
        return (int) ((id >> DATA_CENTER_ID_SHIFT) & MAX_DATA_CENTER_ID);
    }
    
    /**
     * 解析雪花ID中的工作节点ID
     * @param id 雪花ID
     * @return 工作节点ID
     */
    public int extractWorkerId(long id) {
        return (int) ((id >> WORKER_ID_SHIFT) & MAX_WORKER_ID);
    }
    
    /**
     * 解析雪花ID中的序列号
     * @param id 雪花ID
     * @return 序列号
     */
    public int extractSequence(long id) {
        return (int) (id & MAX_SEQUENCE);
    }

    private long getCurrentTimestamp() {
        return System.currentTimeMillis();
    }

    // ------------------- 辅助类与工具方法 -------------------
    /** 无锁更新器 */
    private class AtomicLock {
        private final AtomicLong state = new AtomicLong(0); // [timestamp:52 | sequence:12]

        /** 获取上次使用的时间戳 */
        public long getLastTimestamp() {
            return state.get() >>> SEQUENCE_BITS;
        }

        public long update(long currentTimestamp, IdGeneratorFunction function) {
            long prev, next;
            do {
                prev = state.get();
                long lastTs = prev >>> SEQUENCE_BITS;
                long sequence = prev & MAX_SEQUENCE;

                if (currentTimestamp > lastTs) {
                    // 正常情况：时间前进，重置序列
                    sequence = 0;
                    lastTs = currentTimestamp;
                } else if (currentTimestamp == lastTs) {
                    // 同一毫秒：递增序列
                    if (++sequence > MAX_SEQUENCE) {
                        currentTimestamp = waitNextMillis(lastTs);
                        sequence = 0;
                        lastTs = currentTimestamp;
                    }
                } else {
                    // 时钟回拨：currentTimestamp < lastTs
                    long offset = lastTs - currentTimestamp;
                    if (offset <= CLOCK_BACKWARDS_THRESHOLD_MS) {
                        // 短暂回拨：等待时钟追上
                        currentTimestamp = waitNextMillis(lastTs);
                        sequence = 0;
                        lastTs = currentTimestamp;
                    } else {
                        // 严重回拨：抛出异常（理论上不会到达这里，已在 nextId() 中检测）
                        throw new IllegalStateException(
                            "Clock moved backwards by " + offset + "ms in CAS loop");
                    }
                }

                next = (lastTs << SEQUENCE_BITS) | (sequence & MAX_SEQUENCE);
            } while (!state.compareAndSet(prev, next));

            return function.apply(currentTimestamp, (int) (next & MAX_SEQUENCE));
        }

        private long waitNextMillis(long lastTimestamp) {
            long timestamp = System.currentTimeMillis();
            while (timestamp <= lastTimestamp) {
                Thread.yield();
                timestamp = System.currentTimeMillis();
            }
            return timestamp;
        }
    }

    private int resolveDataCenterId() {
        // 优先级1: 从环境变量读取
        String dcIdEnv = System.getenv("SNOWFLAKE_DATACENTER_ID");
        if (dcIdEnv != null) {
            try {
                return Integer.parseInt(dcIdEnv) & MAX_DATA_CENTER_ID;
            } catch (NumberFormatException e) {
                // 环境变量格式错误时降级到自动生成
                log.info("Invalid SNOWFLAKE_DATACENTER_ID environment variable, using IP");
            }
        }

        // 优先级2: 根据主机IP自动生成（示例：取IPv4最后一段的模）
        try {
            String ip = getLocalHostIp();
            String[] ipSegments = ip.split("\\.");
            int lastSegment = Integer.parseInt(ipSegments[ipSegments.length - 1]);
            return lastSegment % (MAX_DATA_CENTER_ID + 1);
        } catch (Exception e) {
            // 降级到默认值
            log.warn("Failed to resolve data center ID, using default value", e);
        }

        // 优先级3: 返回默认值
        return 0;
    }

    /** 获取本机非回环IPv4地址 */
    private String getLocalHostIp() throws SocketException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface iface = interfaces.nextElement();
            if (iface.isLoopback() || !iface.isUp()) continue;
            Enumeration<InetAddress> addresses = iface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress addr = addresses.nextElement();
                if (addr instanceof Inet4Address) {
                    return addr.getHostAddress();
                }
            }
        }
        throw new SocketException("No non-loopback IPv4 address found");
    }

    private int resolveWorkerId() {
        // 优先级1: 从环境变量读取
        String workerIdEnv = System.getenv("SNOWFLAKE_WORKER_ID");
        if (workerIdEnv != null) {
            try {
                return Integer.parseInt(workerIdEnv) & MAX_WORKER_ID;
            } catch (NumberFormatException e) {
                // 降级到自动生成
                log.info("Invalid SNOWFLAKE_WORKER_ID environment variable, using IP + PID");
            }
        }

        // 优先级2: 根据IP + PID生成唯一值（示例）
        try {
            String ip = getLocalHostIp();
            int pid = getProcessId();
            int hash = (ip.hashCode() ^ pid) & 0xFFFF;
            return hash % (MAX_WORKER_ID + 1);
        } catch (Exception e) {
            // 降级到默认值
            log.warn("Failed to resolve worker ID, using Random value", e);
        }

        // 优先级3: 返回随机值（确保同一数据中心内不同）
        return ThreadLocalRandom.current().nextInt(MAX_WORKER_ID + 1);
    }

    /** 获取进程ID（兼容不同JDK版本） */
    private static int getProcessId() {
        try {
            String processName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            return Integer.parseInt(processName.split("@")[0]);
        } catch (Exception e) {
            return new Random().nextInt(1000);
        }
    }

    private void validateIds() {
        if (dataCenterId < 0 || dataCenterId > MAX_DATA_CENTER_ID) {
            throw new IllegalStateException(
                    String.format("DataCenterId must be in [0, %d], but got %d",
                            MAX_DATA_CENTER_ID, dataCenterId)
            );
        }
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalStateException(
                    String.format("WorkerId must be in [0, %d], but got %d",
                            MAX_WORKER_ID, workerId)
            );
        }
    }

    @FunctionalInterface
    private interface IdGeneratorFunction {
        long apply(long timestamp, int sequence);
    }
}