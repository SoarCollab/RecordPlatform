package cn.flying.monitor.server.utils;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 雪花算法ID生成器
 */
@Component
public class SnowflakeIdGenerator {
    private static final long START_TIMESTAMP = 1691087910202L;

    private static final long DATA_CENTER_ID_BITS = 5L;
    private static final long WORKER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_DATA_CENTER_ID = ~(-1L << DATA_CENTER_ID_BITS);
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATA_CENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATA_CENTER_ID_BITS;

    private long dataCenterId;
    private long workerId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    @Value("${id.snowflake.data-center:1}")
    private long configuredDataCenterId;

    @Value("${id.snowflake.worker:1}")
    private long configuredWorkerId;

    public SnowflakeIdGenerator() {
        this(1, 1);
    }

    private SnowflakeIdGenerator(long dataCenterId, long workerId) {
        if (dataCenterId > MAX_DATA_CENTER_ID || dataCenterId < 0) {
            throw new IllegalArgumentException("Data center ID can't be greater than " + MAX_DATA_CENTER_ID + " or less than 0");
        }
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException("Worker ID can't be greater than " + MAX_WORKER_ID + " or less than 0");
        }
        this.dataCenterId = dataCenterId;
        this.workerId = workerId;
    }

    /**
     * 从Spring配置中加载dataCenterId与workerId，应用并校验范围
     */
    @PostConstruct
    public void initFromConfig() {
        long dc = this.configuredDataCenterId;
        long wk = this.configuredWorkerId;
        if (dc > MAX_DATA_CENTER_ID || dc < 0) {
            throw new IllegalArgumentException("Data center ID out of range, must be 0-" + MAX_DATA_CENTER_ID);
        }
        if (wk > MAX_WORKER_ID || wk < 0) {
            throw new IllegalArgumentException("Worker ID out of range, must be 0-" + MAX_WORKER_ID);
        }
        this.dataCenterId = dc;
        this.workerId = wk;
    }

    /**
     * 生成一个新的雪花算法ID加锁
     *
     * @return 雪花ID
     */
    public synchronized long nextId() {
        long timestamp = getCurrentTimestamp();
        if (timestamp < lastTimestamp) {
            throw new IllegalStateException("Clock moved backwards. Refusing to generate ID.");
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                timestamp = getNextTimestamp(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - START_TIMESTAMP) << TIMESTAMP_SHIFT) |
                (dataCenterId << DATA_CENTER_ID_SHIFT) |
                (workerId << WORKER_ID_SHIFT) |
                sequence;
    }

    private long getCurrentTimestamp() {
        return System.currentTimeMillis();
    }

    private long getNextTimestamp(long lastTimestamp) {
        long timestamp = getCurrentTimestamp();
        while (timestamp <= lastTimestamp) {
            timestamp = getCurrentTimestamp();
        }
        return timestamp;
    }
}
