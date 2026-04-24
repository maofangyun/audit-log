package com.example.demo.util;

import org.springframework.stereotype.Component;

/**
 * 雪花算法 ID 生成器
 *
 * <p>基于 Twitter Snowflake 算法生成全局唯一的 64 位长整型 ID，结构如下：
 * <pre>
 * | 1位符号位 | 41位时间戳（ms） | 5位数据中心ID | 5位工作节点ID | 12位序列号 |
 * </pre>
 *
 * <p>使用方式：注入 Spring Bean 后直接调用 {@link #nextId()}。
 */
@Component
public class SnowflakeIdWorker {

    private static final long EPOCH = 1288834974657L;

    private static final long WORKER_ID_BITS      = 5L;
    private static final long DATACENTER_ID_BITS  = 5L;
    private static final long SEQUENCE_BITS       = 12L;

    private static final long MAX_WORKER_ID      = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_DATACENTER_ID  = ~(-1L << DATACENTER_ID_BITS);

    private static final long WORKER_ID_SHIFT      = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT  = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;
    private static final long SEQUENCE_MASK        = ~(-1L << SEQUENCE_BITS);

    private final long workerId;
    private final long datacenterId;

    private long sequence    = 0L;
    private long lastTimestamp = -1L;

    /**
     * 默认构造器：使用 workerId=1, datacenterId=1（适合单节点部署）。
     * 多节点部署时请通过配置注入 workerId 和 datacenterId。
     */
    public SnowflakeIdWorker() {
        this(1L, 1L);
    }

    public SnowflakeIdWorker(long workerId, long datacenterId) {
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException(
                    String.format("workerId 不能大于 %d 或小于 0", MAX_WORKER_ID));
        }
        if (datacenterId > MAX_DATACENTER_ID || datacenterId < 0) {
            throw new IllegalArgumentException(
                    String.format("datacenterId 不能大于 %d 或小于 0", MAX_DATACENTER_ID));
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    /**
     * 生成下一个全局唯一 ID（线程安全）。
     *
     * @return 64 位雪花 ID
     * @throws RuntimeException 发生时钟回拨时抛出
     */
    public synchronized long nextId() {
        long timestamp = currentTimeMillis();

        if (timestamp < lastTimestamp) {
            throw new RuntimeException(
                    String.format("时钟回拨，拒绝生成 ID，回拨时长 %d ms", lastTimestamp - timestamp));
        }

        if (lastTimestamp == timestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - EPOCH) << TIMESTAMP_LEFT_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    /**
     * 自旋等待至下一毫秒。
     */
    private long waitNextMillis(long lastTs) {
        long timestamp = currentTimeMillis();
        while (timestamp <= lastTs) {
            timestamp = currentTimeMillis();
        }
        return timestamp;
    }

    private long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
