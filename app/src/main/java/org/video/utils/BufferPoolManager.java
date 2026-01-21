package org.video.utils;

import android.media.MediaCodec;
import android.util.Log;
import android.util.SparseArray;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 缓冲区复用池管理器
 * 用于优化视频处理中的缓冲区分配，减少GC压力
 */
public class BufferPoolManager {
    private static final String TAG = "BufferPoolManager";

    // 缓冲区大小分类
    public static final int SIZE_64KB = 64 * 1024;
    public static final int SIZE_256KB = 256 * 1024;
    public static final int SIZE_1MB = 1024 * 1024;
    public static final int SIZE_4MB = 4 * 1024 * 1024;
    public static final int SIZE_16MB = 16 * 1024 * 1024;

    // 缓冲区池配置
    private static final int[] BUFFER_SIZES = {
            SIZE_64KB, SIZE_256KB, SIZE_1MB, SIZE_4MB, SIZE_16MB
    };
    private static final int[] MAX_POOL_SIZE_PER_TYPE = {10, 8, 6, 4, 2}; // 每个大小的最大缓存数量

    // 单例实例
    private static volatile BufferPoolManager instance;

    // 缓冲区池：按大小分类存储
    private final ConcurrentHashMap<Integer, BufferPool> bufferPools;

    // 统计信息
    private final AtomicInteger totalAllocations = new AtomicInteger(0);
    private final AtomicInteger totalReuses = new AtomicInteger(0);
    private final AtomicInteger totalReleases = new AtomicInteger(0);

    private BufferPoolManager() {
        bufferPools = new ConcurrentHashMap<>();

        // 初始化各个大小的缓冲区池
        for (int i = 0; i < BUFFER_SIZES.length; i++) {
            bufferPools.put(BUFFER_SIZES[i],
                    new BufferPool(BUFFER_SIZES[i], MAX_POOL_SIZE_PER_TYPE[i]));
        }

        Log.d(TAG, "BufferPoolManager initialized with sizes: " +
                "64KB, 256KB, 1MB, 4MB, 16MB");
    }

    public static BufferPoolManager getInstance() {
        if (instance == null) {
            synchronized (BufferPoolManager.class) {
                if (instance == null) {
                    instance = new BufferPoolManager();
                }
            }
        }
        return instance;
    }

    /**
     * 获取一个缓冲区
     * @param requiredSize 所需缓冲区大小
     * @return 缓冲区对象
     */
    public ByteBuffer allocateBuffer(int requiredSize) {
        totalAllocations.incrementAndGet();

        // 查找最接近的缓冲区大小类型
        int bufferSize = findBestBufferSize(requiredSize);
        BufferPool pool = bufferPools.get(bufferSize);

        if (pool != null) {
            ByteBuffer buffer = pool.acquire();
            if (buffer != null) {
                totalReuses.incrementAndGet();
                buffer.clear(); // 复用前清空

                // 如果复用的缓冲区比需要的容量小，需要扩展
                if (buffer.capacity() < requiredSize) {
                    Log.w(TAG, "Reused buffer too small: " + buffer.capacity() +
                            " < " + requiredSize + ", allocating new");
                    return ByteBuffer.allocateDirect(requiredSize);
                }

                // 设置合适的limit
                buffer.limit(requiredSize);
                return buffer;
            }
        }

        // 没有可复用的缓冲区，创建新的直接内存缓冲区
        Log.d(TAG, "Allocating new direct buffer of size: " + formatSize(requiredSize));
        return ByteBuffer.allocateDirect(requiredSize);
    }

    /**
     * 释放缓冲区回池中
     * @param buffer 要释放的缓冲区
     * @return 是否成功回收
     */
    public boolean releaseBuffer(ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect()) {
            // 非直接缓冲区不回收
            return false;
        }

        int bufferSize = buffer.capacity();
        totalReleases.incrementAndGet();

        // 查找对应的缓冲区池
        BufferPool pool = bufferPools.get(bufferSize);

        if (pool != null) {
            boolean success = pool.release(buffer);
            if (success) {
                return true;
            }
        }

        // 无法回收，让GC处理
        buffer = null;
        return false;
    }

    /**
     * 获取指定大小的缓冲区（如果不存在则创建并缓存）
     * @param requiredSize 所需大小
     * @param tag 使用标签（用于调试）
     * @return 缓冲区
     */
    public ByteBuffer getBuffer(int requiredSize, String tag) {
        ByteBuffer buffer = allocateBuffer(requiredSize);

        // 调试信息
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Get buffer: " + tag +
                    ", size=" + formatSize(requiredSize) +
                    ", capacity=" + formatSize(buffer.capacity()) +
                    ", direct=" + buffer.isDirect());
        }

        return buffer;
    }

    /**
     * 返回缓冲区到池中
     * @param buffer 缓冲区
     * @param tag 使用标签（用于调试）
     */
    public void returnBuffer(ByteBuffer buffer, String tag) {
        if (buffer == null) {
            return;
        }

        boolean recycled = releaseBuffer(buffer);

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Return buffer: " + tag +
                    ", size=" + formatSize(buffer.capacity()) +
                    ", recycled=" + recycled);
        }
    }

    /**
     * 清空所有缓冲区池
     */
    public void clearAll() {
        for (BufferPool pool : bufferPools.values()) {
            pool.clear();
        }

        Log.d(TAG, "All buffer pools cleared");
        printStats();
    }

    /**
     * 打印统计信息
     */
    public void printStats() {
        StringBuilder stats = new StringBuilder("Buffer Pool Stats:\n");

        for (Integer size : BUFFER_SIZES) {
            BufferPool pool = bufferPools.get(size);
            if (pool != null) {
                stats.append(formatSize(size))
                        .append(": ")
                        .append(pool.getCurrentSize())
                        .append("/")
                        .append(pool.getMaxSize())
                        .append("\n");
            }
        }

        stats.append("Total allocations: ").append(totalAllocations.get()).append("\n")
                .append("Total reuses: ").append(totalReuses.get()).append("\n")
                .append("Total releases: ").append(totalReleases.get()).append("\n")
                .append("Reuse rate: ")
                .append(String.format("%.1f%%", totalAllocations.get() > 0 ?
                        (totalReuses.get() * 100.0 / totalAllocations.get()) : 0));

        Log.d(TAG, stats.toString());
    }

    /**
     * 获取缓冲区池使用情况
     */
    public String getPoolStatus() {
        StringBuilder status = new StringBuilder();
        for (Integer size : BUFFER_SIZES) {
            BufferPool pool = bufferPools.get(size);
            if (pool != null) {
                status.append(formatSize(size))
                        .append(": ")
                        .append(pool.getCurrentSize())
                        .append("/")
                        .append(pool.getMaxSize())
                        .append(" | ");
            }
        }
        return status.toString();
    }

    /**
     * 查找最适合的缓冲区大小
     */
    private int findBestBufferSize(int requiredSize) {
        for (int size : BUFFER_SIZES) {
            if (size >= requiredSize) {
                return size;
            }
        }
        // 如果需要的尺寸超过所有预定义尺寸，返回最大尺寸
        return BUFFER_SIZES[BUFFER_SIZES.length - 1];
    }

    /**
     * 格式化大小显示
     */
    private String formatSize(int bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1fKB", bytes / 1024.0);
        } else {
            return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
        }
    }

    /**
     * 内部缓冲区池类
     */
    private static class BufferPool {
        private final int bufferSize;
        private final int maxSize;
        private final SparseArray<ByteBuffer> buffers;

        BufferPool(int bufferSize, int maxSize) {
            this.bufferSize = bufferSize;
            this.maxSize = maxSize;
            this.buffers = new SparseArray<>();
        }

        synchronized ByteBuffer acquire() {
            if (buffers.size() > 0) {
                // 使用最近最少使用的策略
                ByteBuffer buffer = buffers.valueAt(buffers.size() - 1);
                buffers.removeAt(buffers.size() - 1);
                return buffer;
            }
            return null;
        }

        synchronized boolean release(ByteBuffer buffer) {
            if (buffer == null || buffer.capacity() != bufferSize) {
                return false;
            }

            if (buffers.size() < maxSize) {
                // 清理缓冲区
                buffer.clear();
                buffers.put(buffer.hashCode(), buffer);
                return true;
            }

            // 池已满，不回收
            return false;
        }

        synchronized void clear() {
            buffers.clear();
        }

        synchronized int getCurrentSize() {
            return buffers.size();
        }

        int getMaxSize() {
            return maxSize;
        }

        int getBufferSize() {
            return bufferSize;
        }
    }
}
