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
 * <p>封装 Kryo 序列化、反序列化及字节数组合并/拆分操作。
 * 使用 {@link ThreadLocal} 管理 Kryo 实例，保证线程安全且避免频繁创建开销。
 *
 * <p>字节合并格式（用于将 before/after 打包为单文件）：
 * <pre>
 * | 4字节（before 长度，大端序） | before 数据 | after 数据 |
 * </pre>
 *
 * <p>工具类，禁止实例化。
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
        if (obj == null) return;
        Kryo kryo = KRYO_THREAD_LOCAL.get();
        Output output = new Output(os);
        kryo.writeClassAndObject(output, obj);
        output.flush();
    }

    /**
     * 合并序列化：将 before 和 after 直接流式写入，减少内存拷贝
     */
    public static byte[] serializeCombined(Object before, Object after) {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(1024 * 1024); // 初始 1MB
        
        // 1. 预留 4 字节存 before 长度
        bos.write(0); bos.write(0); bos.write(0); bos.write(0);
        
        // 2. 序列化 before
        serializeToStream(before, bos);
        int beforeLen = bos.size() - 4;
        
        // 3. 序列化 after
        serializeToStream(after, bos);
        
        byte[] result = bos.toByteArray();
        
        // 4. 回填 before 长度（大端序）
        result[0] = (byte) (beforeLen >> 24);
        result[1] = (byte) (beforeLen >> 16);
        result[2] = (byte) (beforeLen >> 8);
        result[3] = (byte) beforeLen;
        
        return result;
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
        try (Input input = new Input(new ByteArrayInputStream(data))) {
            return kryo.readClassAndObject(input);
        } catch (Exception e) {
            log.warn("[KryoSerializer] 反序列化失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将两个字节数组合并为一个（已被 serializeCombined 取代，保留 split 用于解压）
     */
    public static byte[][] split(byte[] merged) {
        if (merged == null || merged.length < 4) {
            throw new IllegalArgumentException("非法的合并字节数组：长度不足 4 字节");
        }
        int firstLen = ((merged[0] & 0xFF) << 24)
                     | ((merged[1] & 0xFF) << 16)
                     | ((merged[2] & 0xFF) << 8)
                     |  (merged[3] & 0xFF);
        if (firstLen < 0 || firstLen > merged.length - 4) {
            throw new IllegalArgumentException("非法的合并字节数组：first 长度超出范围");
        }
        byte[] first  = new byte[firstLen];
        byte[] second = new byte[merged.length - 4 - firstLen];
        System.arraycopy(merged, 4,             first,  0, first.length);
        System.arraycopy(merged, 4 + firstLen,  second, 0, second.length);
        return new byte[][]{first, second};
    }
}
