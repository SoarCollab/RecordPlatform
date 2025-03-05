package cn.flying.common.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 优化的雪花算法ID生成器（支持自动注入数据中心/节点ID）
 */
@Slf4j
@Component
public class SnowflakeIdGenerator {

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
    private final long lastTimestamp = -1L;

    public SnowflakeIdGenerator() {
        autoConfigureIds();
    }

    /** 自动配置数据中心ID和节点ID */
    private void autoConfigureIds() {
        if (dataCenterId < 0 || dataCenterId > MAX_DATA_CENTER_ID) {
            dataCenterId = resolveDataCenterId(); // 自动获取逻辑（如系统环境变量）
        }
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            workerId = resolveWorkerId(); // 自动获取逻辑（如IP哈希）
        }
        validateIds();

        log.info("Auto-configured Snowflake IDs: dataCenterId={}, workerId={}", dataCenterId, workerId);
    }

    /** 生成ID（无锁化） */
    public long nextId() {
        long currentTimestamp = getCurrentTimestamp();

        // 处理时钟回拨
        if (currentTimestamp < lastTimestamp) {
            handleClockBackwards(currentTimestamp);
        }

        // CAS自旋更新序列
        return atomicLock.update(currentTimestamp, (ts, seq) -> {
            long timestampDelta = ts - DEFAULT_START_TIMESTAMP;
            return (timestampDelta << TIMESTAMP_SHIFT) |
                    ((long) dataCenterId << DATA_CENTER_ID_SHIFT) |
                    ((long) workerId << WORKER_ID_SHIFT) |
                    seq;
        });
    }
    private long getCurrentTimestamp() {
        return System.currentTimeMillis();
    }

    /** 处理时钟回拨（示例：短暂等待） */
    private void handleClockBackwards(long currentTimestamp) {
        long offset = lastTimestamp - currentTimestamp;
        if (offset <= 5_000) { // 允许5秒内的回拨
            try {
                Thread.sleep(offset);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Clock adjustment interrupted", e);
            }
        } else {
            throw new IllegalStateException("Clock moved backwards beyond threshold");
        }
    }

    // ------------------- 辅助类与工具方法 -------------------
    /** 无锁更新器 */
    private static class AtomicLock {
        private final AtomicLong state = new AtomicLong(0); // [timestamp:54 | sequence:12]

        public long update(long currentTimestamp, IdGeneratorFunction function) {
            long prev, next;
            do {
                prev = state.get();
                long lastTs = prev >>> SEQUENCE_BITS;
                long sequence = prev & MAX_SEQUENCE;

                if (currentTimestamp > lastTs) {
                    sequence = 0;
                    lastTs = currentTimestamp;
                } else if (currentTimestamp == lastTs) {
                    if (++sequence > MAX_SEQUENCE) {
                        currentTimestamp = waitNextMillis(lastTs);
                        sequence = 0;
                        lastTs = currentTimestamp;
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

        // 优先级3: 返回默认值（需确保不同机器默认值不同，此处仅为示例）
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