/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;


import io.netty.buffer.PoolArena.SizeClass;
import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;
import io.netty.util.internal.MathUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Acts a Thread cache for allocations. This implementation is moduled after
 * <a href="http://people.freebsd.org/~jasone/jemalloc/bsdcan2006/jemalloc.pdf">jemalloc</a> and the descripted
 * technics of
 * <a href="https://www.facebook.com/notes/facebook-engineering/scalable-memory-allocation-using-jemalloc/480222803919">
 * Scalable memory allocation using jemalloc</a>
 *
 * 池化的内存块如果被多个线程争用,需要加锁,也会造成较大的锁竞争.所以尽可能将内存块绑定在某个线程上
 * 该对象代表着分配到某个线程上的内存块
 */
final class PoolThreadCache {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(PoolThreadCache.class);

    /**
     * 本线程申请的堆内存会从该对象中获取
     */
    final PoolArena<byte[]> heapArena;
    /**
     * 本线程申请的堆外内存会从该对象中获取
     */
    final PoolArena<ByteBuffer> directArena;

    // Hold the caches for the different size classes, which are tiny, small and normal.
    private final MemoryRegionCache<byte[]>[] tinySubPageHeapCaches;
    private final MemoryRegionCache<byte[]>[] smallSubPageHeapCaches;
    private final MemoryRegionCache<ByteBuffer>[] tinySubPageDirectCaches;
    private final MemoryRegionCache<ByteBuffer>[] smallSubPageDirectCaches;
    private final MemoryRegionCache<byte[]>[] normalHeapCaches;
    private final MemoryRegionCache<ByteBuffer>[] normalDirectCaches;

    // Used for bitshifting when calculate the index of normal caches later
    private final int numShiftsNormalDirect;
    private final int numShiftsNormalHeap;
    private final int freeSweepAllocationThreshold;
    private final AtomicBoolean freed = new AtomicBoolean();

    private int allocations;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /**
     * 该对象代表每个线程会从哪个arena上获取/归还内存块
     * @param heapArena
     * @param directArena
     * @param tinyCacheSize
     * @param smallCacheSize
     * @param normalCacheSize
     * @param maxCachedBufferCapacity
     * @param freeSweepAllocationThreshold
     */
    PoolThreadCache(PoolArena<byte[]> heapArena, PoolArena<ByteBuffer> directArena,
                    int tinyCacheSize, int smallCacheSize, int normalCacheSize,
                    int maxCachedBufferCapacity, int freeSweepAllocationThreshold) {
        if (maxCachedBufferCapacity < 0) {
            throw new IllegalArgumentException("maxCachedBufferCapacity: "
                    + maxCachedBufferCapacity + " (expected: >= 0)");
        }
        //根据 传入的 属性  赋值  这个不知道什么东西 不过在传入 arena 的情况下 需要>0
        this.freeSweepAllocationThreshold = freeSweepAllocationThreshold;
        this.heapArena = heapArena;
        this.directArena = directArena;
        if (directArena != null) {
            //以tiny 大小为参数 初始化缓存对象
            tinySubPageDirectCaches = createSubPageCaches(
                    tinyCacheSize, PoolArena.numTinySubpagePools, SizeClass.Tiny);
            //以small 大小为参数 初始化缓存对象
            smallSubPageDirectCaches = createSubPageCaches(
                    smallCacheSize, directArena.numSmallSubpagePools, SizeClass.Small);

            //代表 2的 多少次 能变成 pageSize
            numShiftsNormalDirect = log2(directArena.pageSize);
            //创建 normal 大小的 缓存对象 也就是 以chunk 为单位
            normalDirectCaches = createNormalCaches(
                    normalCacheSize, maxCachedBufferCapacity, directArena);

            //代表该arena此时也被本线程使用
            directArena.numThreadCaches.getAndIncrement();
        } else {
            // No directArea is configured so just null out all caches
            tinySubPageDirectCaches = null;
            smallSubPageDirectCaches = null;
            normalDirectCaches = null;
            numShiftsNormalDirect = -1;
        }
        //如果heap 内存不为空
        if (heapArena != null) {
            // Create the caches for the heap allocations
            // 创建tiny大小的 缓存对象
            tinySubPageHeapCaches = createSubPageCaches(
                    tinyCacheSize, PoolArena.numTinySubpagePools, SizeClass.Tiny);
            // 创建small大小的缓存对象
            smallSubPageHeapCaches = createSubPageCaches(
                    smallCacheSize, heapArena.numSmallSubpagePools, SizeClass.Small);

            //基本都跟上面一样
            numShiftsNormalHeap = log2(heapArena.pageSize);
            normalHeapCaches = createNormalCaches(
                    normalCacheSize, maxCachedBufferCapacity, heapArena);

            heapArena.numThreadCaches.getAndIncrement();
        } else {
            // No heapArea is configured so just null out all caches
            tinySubPageHeapCaches = null;
            smallSubPageHeapCaches = null;
            normalHeapCaches = null;
            numShiftsNormalHeap = -1;
        }

        // Only check if there are caches in use.
        if ((tinySubPageDirectCaches != null || smallSubPageDirectCaches != null || normalDirectCaches != null
                || tinySubPageHeapCaches != null || smallSubPageHeapCaches != null || normalHeapCaches != null)
                && freeSweepAllocationThreshold < 1) {
            throw new IllegalArgumentException("freeSweepAllocationThreshold: "
                    + freeSweepAllocationThreshold + " (expected: > 0)");
        }
    }

    /**
     * 线程只要分配过内存块,一种激进的优化策略是认为该线程之后还会分配相同的内存块 所以在这一层上再做一个缓存
     * (内存块不会直接归还回arena 而是先保留在cache中 当线程被回收时将cache中所有内存块一次性归还到arena 但是这样做的前提是线程数少)
     * @param cacheSize
     * @param numCaches  Tiny 默认是 32 Small 是4
     * @param sizeClass  Tiny or small
     * @param <T>
     * @return
     */
    private static <T> MemoryRegionCache<T>[] createSubPageCaches(
            int cacheSize, int numCaches, SizeClass sizeClass) {
        if (cacheSize > 0 && numCaches > 0) {
            // 该对象用于存储分配过的内存块
            @SuppressWarnings("unchecked")
            MemoryRegionCache<T>[] cache = new MemoryRegionCache[numCaches];
            for (int i = 0; i < cache.length; i++) {
                // TODO: maybe use cacheSize / cache.length
                cache[i] = new SubPageMemoryRegionCache<T>(cacheSize, sizeClass);
            }
            return cache;
        } else {
            return null;
        }
    }

    /**
     * 创建 Chunk 级别的 缓存对象
     * @param cacheSize
     * @param maxCachedBufferCapacity  缓存块的 最大大小
     * @param area
     * @param <T>
     * @return
     */
    private static <T> MemoryRegionCache<T>[] createNormalCaches(
            int cacheSize, int maxCachedBufferCapacity, PoolArena<T> area) {
        if (cacheSize > 0 && maxCachedBufferCapacity > 0) {
            //最多只能保存 一个 chunk 大小 或者是 maxCachedBufferCapacity
            int max = Math.min(area.chunkSize, maxCachedBufferCapacity);
            int arraySize = Math.max(1, log2(max / area.pageSize) + 1);

            @SuppressWarnings("unchecked")
            //创建arraySize 的 数组对象
            MemoryRegionCache<T>[] cache = new MemoryRegionCache[arraySize];
            for (int i = 0; i < cache.length; i++) {
                cache[i] = new NormalMemoryRegionCache<T>(cacheSize);
            }
            return cache;
        } else {
            return null;
        }
    }

    /**
     * 2的 多少次 能得到val
     * @param val
     * @return
     */
    private static int log2(int val) {
        int res = 0;
        while (val > 1) {
            val >>= 1;
            res++;
        }
        return res;
    }

    /**
     * Try to allocate a tiny buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     * 从缓存中拿出一个tiny大小的内存块
     */
    boolean allocateTiny(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int normCapacity) {
        return allocate(cacheForTiny(area, normCapacity), buf, reqCapacity);
    }

    /**
     * Try to allocate a small buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     * 从缓存中拿出一个small大小的内存
     */
    boolean allocateSmall(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int normCapacity) {
        return allocate(cacheForSmall(area, normCapacity), buf, reqCapacity);
    }

    /**
     * Try to allocate a small buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     * 从缓存中拿出page 大小的 内存
     */
    boolean allocateNormal(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int normCapacity) {
        return allocate(cacheForNormal(area, normCapacity), buf, reqCapacity);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    /**
     * 从对应的缓存对象中 分配内存
     */
    private boolean allocate(MemoryRegionCache<?> cache, PooledByteBuf buf, int reqCapacity) {
        if (cache == null) {
            // no cache found so just return false here
            return false;
        }
        //使用缓存 分配 内存 如果没有缓存对象就会返回false
        boolean allocated = cache.allocate(buf, reqCapacity);
        //如果 使用缓存的次数超过了一个 阈值 开始清除很少使用的缓存 这个值是不能减的 直到到达 阈值 清除
        //这个每次 一定都会释放一定数量的 缓存 那不是越频繁越容易释放了吗 跟说法好像不一样啊
        if (++ allocations >= freeSweepAllocationThreshold) {
            allocations = 0;
            trim();
        }
        return allocated;
    }

    /**
     * Add {@link PoolChunk} and {@code handle} to the cache if there is enough room.
     * Returns {@code true} if it fit into the cache {@code false} otherwise.
     * 将chunk 对象 设置到 缓存中
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    boolean add(PoolArena<?> area, PoolChunk chunk, ByteBuffer nioBuffer,
                long handle, int normCapacity, SizeClass sizeClass) {
        //获取缓存对象
        MemoryRegionCache<?> cache = cache(area, normCapacity, sizeClass);
        if (cache == null) {
            return false;
        }
        //为缓存对象增加
        return cache.add(chunk, nioBuffer, handle);
    }

    /**
     * 根据 SizeClass 获取对应的 缓存对象
     * @param area
     * @param normCapacity
     * @param sizeClass
     * @return
     */
    private MemoryRegionCache<?> cache(PoolArena<?> area, int normCapacity, SizeClass sizeClass) {
        switch (sizeClass) {
        case Normal:
            return cacheForNormal(area, normCapacity);
        case Small:
            return cacheForSmall(area, normCapacity);
        case Tiny:
            return cacheForTiny(area, normCapacity);
        default:
            throw new Error();
        }
    }

    /// TODO: In the future when we move to Java9+ we should use java.lang.ref.Cleaner.
    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            //该对象被终结时 调用 free方法
            free();
        }
    }

    /**
     *  Should be called if the Thread that uses this cache is about to exist to release resources out of the cache
     *  当本对象不再被使用时 需要将cache中的所有内存块归还到arena上
     */
    void free() {
        // As free() may be called either by the finalizer or by FastThreadLocal.onRemoval(...) we need to ensure
        // we only call this one time.
        // 代表已 free
        if (freed.compareAndSet(false, true)) {
            //获取 一共释放了 多少 缓存对象
            int numFreed = free(tinySubPageDirectCaches) +
                    free(smallSubPageDirectCaches) +
                    free(normalDirectCaches) +
                    free(tinySubPageHeapCaches) +
                    free(smallSubPageHeapCaches) +
                    free(normalHeapCaches);

            if (numFreed > 0 && logger.isDebugEnabled()) {
                logger.debug("Freed {} thread-local buffer(s) from thread: {}", numFreed,
                        Thread.currentThread().getName());
            }

            //代表 该线程缓存对象不再 使用该 arena 了
            if (directArena != null) {
                directArena.numThreadCaches.getAndDecrement();
            }

            if (heapArena != null) {
                heapArena.numThreadCaches.getAndDecrement();
            }
        }
    }

    /**
     * 释放缓存数组对象 就是依次获取每个元素 并 free
     * @param caches
     * @return
     */
    private static int free(MemoryRegionCache<?>[] caches) {
        if (caches == null) {
            return 0;
        }

        int numFreed = 0;
        for (MemoryRegionCache<?> c: caches) {
            numFreed += free(c);
        }
        return numFreed;
    }

    /**
     * 释放缓存对象 就是委托到cache   实际上是调用 cache 的 freeEntry 方法
     */
    private static int free(MemoryRegionCache<?> cache) {
        if (cache == null) {
            return 0;
        }
        return cache.free();
    }

    /**
     * 当 分配次数 达到 阈值 触发 trim  也就是针对每个缓存数组的 每个缓存对象 调用trim 方法
     */
    void trim() {
        trim(tinySubPageDirectCaches);
        trim(smallSubPageDirectCaches);
        trim(normalDirectCaches);
        trim(tinySubPageHeapCaches);
        trim(smallSubPageHeapCaches);
        trim(normalHeapCaches);
    }

    private static void trim(MemoryRegionCache<?>[] caches) {
        if (caches == null) {
            return;
        }
        for (MemoryRegionCache<?> c: caches) {
            trim(c);
        }
    }

    private static void trim(MemoryRegionCache<?> cache) {
        if (cache == null) {
            return;
        }
        cache.trim();
    }

    /**
     * 每个线程可能之前已经分配了内存块 并且现在该内存块没有被使用 直接复用就可以
     * @param area
     * @param normCapacity
     * @return
     */
    private MemoryRegionCache<?> cacheForTiny(PoolArena<?> area, int normCapacity) {
        //获得 数组下标
        int idx = PoolArena.tinyIdx(normCapacity);
        //如果是 直接内存对象 获取直接内存对应的缓存
        if (area.isDirect()) {
            return cache(tinySubPageDirectCaches, idx);
        }
        return cache(tinySubPageHeapCaches, idx);
    }

    /**
     * 通过传入的 normCapacity
     * @param area
     * @param normCapacity
     * @return
     */
    private MemoryRegionCache<?> cacheForSmall(PoolArena<?> area, int normCapacity) {
        //获取对应到 small 的下标  以512为单位
        int idx = PoolArena.smallIdx(normCapacity);
        if (area.isDirect()) {
            return cache(smallSubPageDirectCaches, idx);
        }
        return cache(smallSubPageHeapCaches, idx);
    }

    /**
     * 通过传入的 normCapacity
     * @param area
     * @param normCapacity
     * @return
     */
    private MemoryRegionCache<?> cacheForNormal(PoolArena<?> area, int normCapacity) {
        if (area.isDirect()) {
            int idx = log2(normCapacity >> numShiftsNormalDirect);
            return cache(normalDirectCaches, idx);
        }
        int idx = log2(normCapacity >> numShiftsNormalHeap);
        return cache(normalHeapCaches, idx);
    }

    /**
     * 就是通过数组对象 和 下标对应到 缓存对象
     * @param cache
     * @param idx
     * @param <T>
     * @return
     */
    private static <T> MemoryRegionCache<T> cache(MemoryRegionCache<T>[] cache, int idx) {
        if (cache == null || idx > cache.length - 1) {
            return null;
        }
        return cache[idx];
    }

    /**
     * Cache used for buffers which are backed by TINY or SMALL size.
     */
    private static final class SubPageMemoryRegionCache<T> extends MemoryRegionCache<T> {
        SubPageMemoryRegionCache(int size, SizeClass sizeClass) {
            super(size, sizeClass);
        }

        @Override
        protected void initBuf(
                PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, PooledByteBuf<T> buf, int reqCapacity) {
            //委托 就是调用buf 的 init 方法 设置 memory 对象
            chunk.initBufWithSubpage(buf, nioBuffer, handle, reqCapacity);
        }
    }

    /**
     * Cache used for buffers which are backed by NORMAL size.
     * 基本跟上面差不多
     */
    private static final class NormalMemoryRegionCache<T> extends MemoryRegionCache<T> {
        NormalMemoryRegionCache(int size) {
            super(size, SizeClass.Normal);
        }

        @Override
        protected void initBuf(
                PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, PooledByteBuf<T> buf, int reqCapacity) {
            chunk.initBuf(buf, nioBuffer, handle, reqCapacity);
        }
    }

    /**
     * 缓存从arena中获取的内存块对象
     * @param <T>
     */
    private abstract static class MemoryRegionCache<T> {

        private final int size;
        /**
         * 缓存队列 是 mpsc 队列 这里指 允许单个添加 多个使用
         */
        private final Queue<Entry<T>> queue;
        /**
         * 该缓存的 类型
         */
        private final SizeClass sizeClass;
        private int allocations;

        /**
         *
         * @param size
         * @param sizeClass
         */
        MemoryRegionCache(int size, SizeClass sizeClass) {
            this.size = MathUtil.safeFindNextPositivePowerOfTwo(size);
            //生成 mpsc queue 对象
            queue = PlatformDependent.newFixedMpscQueue(this.size);
            this.sizeClass = sizeClass;
        }

        /**
         * Init the {@link PooledByteBuf} using the provided chunk and handle with the capacity restrictions.
         */
        protected abstract void initBuf(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle,
                                        PooledByteBuf<T> buf, int reqCapacity);

        /**
         * Add to cache if not already full.
         * 如果队列还能存放元素 就 添加
         */
        @SuppressWarnings("unchecked")
        public final boolean add(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle) {
            //每个添加的 chunk 对象 都被封装成一个 entry 对象
            Entry<T> entry = newEntry(chunk, nioBuffer, handle);
            boolean queued = queue.offer(entry);
            if (!queued) {
                // If it was not possible to cache the chunk, immediately recycle the entry
                //入队失败 立刻回收
                entry.recycle();
            }

            return queued;
        }

        /**
         * Allocate something out of the cache if possible and remove the entry from the cache.
         * 将缓存的内存 取出来 并分配到 buf 上
         */
        public final boolean allocate(PooledByteBuf<T> buf, int reqCapacity) {
            //取出 缓存的东西
            Entry<T> entry = queue.poll();
            if (entry == null) {
                return false;
            }
            //初始化buf 对象 其实就是给 空的bytebuf 设置memory 因为PooledByteBuf 对象并不是在创建时 就设置memory 的
            initBuf(entry.chunk, entry.nioBuffer, entry.handle, buf, reqCapacity);
            entry.recycle();

            // allocations is not thread-safe which is fine as this is only called from the same thread all time.
            // 增加分配量 因为是非线程安全的 所以应该在一个线程中调用
            ++ allocations;
            return true;
        }

        /**
         * Clear out this cache and free up all previous cached {@link PoolChunk}s and {@code handle}s.
         * 将所有内存块归还到arena
         */
        public final int free() {
            return free(Integer.MAX_VALUE);
        }

        /**
         * 释放指定的内存大小
         * @param max
         * @return
         */
        private int free(int max) {
            int numFreed = 0;
            for (; numFreed < max; numFreed++) {
                //从队列中取出 每个 entry 对象并释放
                Entry<T> entry = queue.poll();
                if (entry != null) {
                    freeEntry(entry);
                } else {
                    // all cleared
                    return numFreed;
                }
            }
            //如果 释放了 max 个 就会到这里
            return numFreed;
        }

        /**
         * Free up cached {@link PoolChunk}s if not allocated frequently enough.
         * 如果没有频繁获取 就 释放缓存   不是每次分配才加1 吗 这样 反而更容易 free了
         */
        public final void trim() {
            //这里代表的是没有 使用过的 需要释放的 量
            int free = size - allocations;
            allocations = 0;

            // We not even allocated all the number that are
            //释放指定数量的缓存对象
            if (free > 0) {
                free(free);
            }
        }

        /**
         * 释放 entry 对象
         * @param entry
         */
        @SuppressWarnings({ "unchecked", "rawtypes" })
        private  void freeEntry(Entry entry) {
            PoolChunk chunk = entry.chunk;
            long handle = entry.handle;
            ByteBuffer nioBuffer = entry.nioBuffer;

            // recycle now so PoolChunk can be GC'ed.
            entry.recycle();

            // 将chunk归还到arena
            chunk.arena.freeChunk(chunk, handle, sizeClass, nioBuffer);
        }

        /**
         * 缓存的  基件对象
         * @param <T>
         */
        static final class Entry<T> {
            /**
             * 回收对象
             */
            final Handle<Entry<?>> recyclerHandle;
            /**
             * 该缓存体 对应的 chunk 对象
             */
            PoolChunk<T> chunk;
            ByteBuffer nioBuffer;
            /**
             * 该缓存体 对应的handle  这个 可以定位到 哪个page  如果使用了 subpage 可以定位到 subpage
             */
            long handle = -1;

            Entry(Handle<Entry<?>> recyclerHandle) {
                this.recyclerHandle = recyclerHandle;
            }

            void recycle() {
                chunk = null;
                nioBuffer = null;
                handle = -1;
                recyclerHandle.recycle(this);
            }
        }

        /**
         * 创建新的 entry 对象 就是 从recycle 中 取出来并设置属性
         * @param chunk
         * @param nioBuffer
         * @param handle
         * @return
         */
        @SuppressWarnings("rawtypes")
        private static Entry newEntry(PoolChunk<?> chunk, ByteBuffer nioBuffer, long handle) {
            Entry entry = RECYCLER.get();
            entry.chunk = chunk;
            entry.nioBuffer = nioBuffer;
            entry.handle = handle;
            return entry;
        }

        @SuppressWarnings("rawtypes")
        private static final Recycler<Entry> RECYCLER = new Recycler<Entry>() {
            @SuppressWarnings("unchecked")
            @Override
            protected Entry newObject(Handle<Entry> handle) {
                return new Entry(handle);
            }
        };
    }
}
