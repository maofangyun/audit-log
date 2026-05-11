package com.example.demo.util;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Kryo 序列化工具类
 *
 * <p>
 * 封装 Kryo 序列化、反序列化及字节数组合并/拆分操作。
 * 使用 {@link ThreadLocal} 管理 Kryo 实例，保证线程安全且避免频繁创建开销。
 *
 * <p>
 * 字节合并格式（用于将 before/after 打包为单文件）：
 * 
 * <pre>
 * | 4字节（before 长度，大端序） | before 数据 | after 数据 |
 * </pre>
 *
 * <p>
 * 工具类，禁止实例化。
 */
public final class KryoSerializer {

    private static final Logger log = LoggerFactory.getLogger(KryoSerializer.class);

    /** ThreadLocal Kryo 池：每线程独享一个实例，避免并发竞争 */
    private static final ThreadLocal<Kryo> KRYO_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        Kryo kryo = new Kryo();
        // 关闭强制注册，允许序列化任意类（审计场景对象类型多样）
        kryo.setRegistrationRequired(false);
        // 允许序列化含循环引用的对象
        kryo.setReferences(true);
        // 使用标准实例化策略（支持无无参构造器的类）
        kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());
        return kryo;
    });

    private KryoSerializer() {
        // 工具类，禁止实例化
    }

    /**
     * 将对象直接序列化到输出流中（高性能流式写入）
     */
    public static void serializeToStream(Object obj, java.io.OutputStream os) {
        if (obj == null)
            return;
        Kryo kryo = KRYO_THREAD_LOCAL.get();
        Output output = new Output(os, 65536); // 使用 64KB 缓冲区（默认仅 4096），大幅减少大对象序列化时的 flush 频率
        kryo.writeClassAndObject(output, obj);
        output.flush();
    }

    /**
     * 将单个对象序列化为字节数组。
     *
     * <p>用途：
     * <ul>
     *   <li>判断单元素大小，决定是否触发 Claim Check</li>
     *   <li>作为 MinIO 上传的原始字节（大对象路径）</li>
     * </ul>
     *
     * @param obj 待序列化对象
     * @return 字节数组；obj 为 null 时返回空数组
     */
    public static byte[] serialize(Object obj) {
        if (obj == null) return new byte[0];
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(64 * 1024);
        serializeToStream(obj, bos);
        return bos.toByteArray();
    }

    /**
     * 将字节数组反序列化为对象。
     *
     * @param data 字节数组
     * @return 反序列化后的对象；data 为空或反序列化失败时返回 null
     */
    public static Object deserialize(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        Kryo kryo = KRYO_THREAD_LOCAL.get();
        try (Input input = new Input(new ByteArrayInputStream(data), 65536)) {
            return kryo.readClassAndObject(input);
        } catch (Exception e) {
            log.warn("[KryoSerializer] 反序列化失败: {}", e.getMessage());
            return null;
        }
    }
}
