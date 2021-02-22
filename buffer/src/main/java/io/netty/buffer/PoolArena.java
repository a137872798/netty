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

import io.netty.util.internal.LongCounter;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Math.max;

/**
 * 池化代表着内存可以被回收以及再分配, 需要一个管理内存块的对象,就是这个arena对象
 * @param <T>
 */
abstract class PoolArena<T> implements PoolArenaMetric {
    //判断是否支持 unsafe 对象
    static final boolean HAS_UNSAFE = PlatformDependent.hasUnsafe();

    /**
     * 内存块大小被划分为多个级别
     */
    enum SizeClass {
        //以16b为单位
        Tiny,
        //以512为单位
        Small,
        // pageSize< ? < chunkSize
        Normal
    }

    /**
     * 一个arena中tiny大小的内存块数量
     */
    static final int numTinySubpagePools = 512 >>> 4;

    /**
     * 本arena由哪个分配器创建
     */
    final PooledByteBufAllocator parent;

    private final int maxOrder;
    /**
     * page 大小 默认是8k
     */
    final int pageSize;
    /**
     *
     */
    final int pageShifts;
    /**
     * chunk的 大小 默认是16m
     */
    final int chunkSize;
    final int subpageOverflowMask;
    final int numSmallSubpagePools;
    final int directMemoryCacheAlignment;
    final int directMemoryCacheAlignmentMask;
    /**
     * tinySubpagePools 长度为32 数组中每个元素内内存块大小为16的倍数 从16->512   当刚好需要512byte时会分配一个smallpage
     * smallSubpagePools 长度为4 数组中每个元素内内存块大小为 512 1024 1536 2048
     */
    private final PoolSubpage<T>[] tinySubpagePools;
    private final PoolSubpage<T>[] smallSubpagePools;

    /**
     * 按照使用率来划分的 PoolChunkList 对象
     */
    private final PoolChunkList<T> q050;
    private final PoolChunkList<T> q025;
    private final PoolChunkList<T> q000;
    private final PoolChunkList<T> qInit;
    private final PoolChunkList<T> q075;
    private final PoolChunkList<T> q100;

    private final List<PoolChunkListMetric> chunkListMetrics;

    // Metrics for allocations and deallocations  记录申请 pageSize< ? <chunkSize 的内存块次数
    private long allocationsNormal;
    // We need to use the LongCounter here as this is not guarded via synchronized block.
    /**
     * 计数器对象
     */
    private final LongCounter allocationsTiny = PlatformDependent.newLongCounter();
    private final LongCounter allocationsSmall = PlatformDependent.newLongCounter();
    private final LongCounter allocationsHuge = PlatformDependent.newLongCounter();
    private final LongCounter activeBytesHuge = PlatformDependent.newLongCounter();

    /**
     * 记录释放的次数 每次 free 都会增加1
     */
    private long deallocationsTiny;
    private long deallocationsSmall;
    private long deallocationsNormal;

    // We need to use the LongCounter here as this is not guarded via synchronized block.
    private final LongCounter deallocationsHuge = PlatformDependent.newLongCounter();

    // Number of thread caches backed by this arena.
    /**
     * 当前arena 正在被多少个线程共用,这些线程需要分配内存时都会从该对象上获取
     */
    final AtomicInteger numThreadCaches = new AtomicInteger();

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /**
     * 创建 arena 对象
     * @param parent
     * @param pageSize
     * @param maxOrder
     * @param pageShifts
     * @param chunkSize
     * @param cacheAlignment
     */
    protected PoolArena(PooledByteBufAllocator parent, int pageSize,
          int maxOrder, int pageShifts, int chunkSize, int cacheAlignment) {
        //设置 allocator 代表是 那个 分配器创建的对象
        this.parent = parent;
        //page 的大小
        this.pageSize = pageSize;
        //page 深度 默认是 11
        this.maxOrder = maxOrder;
        //默认是 13 代表 1<< 多少位 到 page的大小
        this.pageShifts = pageShifts;
        //一个chunk 的默认大小 16m
        this.chunkSize = chunkSize;

        //对齐属性 默认为0
        directMemoryCacheAlignment = cacheAlignment;
        directMemoryCacheAlignmentMask = cacheAlignment - 1;

        // 便于快速比较分配的大小与page的关系
        subpageOverflowMask = ~(pageSize - 1);
        //代表针对tiny内存块存在32种大小的数组
        tinySubpagePools = newSubpagePoolArray(numTinySubpagePools);
        for (int i = 0; i < tinySubpagePools.length; i ++) {
            //都先创建 PoolHead 对象
            tinySubpagePools[i] = newSubpagePoolHead(pageSize);
        }

        numSmallSubpagePools = pageShifts - 9;
        //默认存在4个small大小的内存块 512 1024 1536 2048
        smallSubpagePools = newSubpagePoolArray(numSmallSubpagePools);
        for (int i = 0; i < smallSubpagePools.length; i ++) {
            //使用head填充数组
            smallSubpagePools[i] = newSubpagePoolHead(pageSize);
        }

        //按照使用率 来创建 Chunklist 对象
        //最小使用率为100就代表不能分配内存
        q100 = new PoolChunkList<T>(this, null, 100, Integer.MAX_VALUE, chunkSize);
        q075 = new PoolChunkList<T>(this, q100, 75, 100, chunkSize);
        q050 = new PoolChunkList<T>(this, q075, 50, 100, chunkSize);
        q025 = new PoolChunkList<T>(this, q050, 25, 75, chunkSize);
        q000 = new PoolChunkList<T>(this, q025, 1, 50, chunkSize);
        qInit = new PoolChunkList<T>(this, q000, Integer.MIN_VALUE, 25, chunkSize);

        q100.prevList(q075);
        q075.prevList(q050);
        q050.prevList(q025);
        q025.prevList(q000);
        //代表q000不能往上走
        q000.prevList(null);
        qInit.prevList(qInit);

        //创建统计对象的视图对象
        List<PoolChunkListMetric> metrics = new ArrayList<PoolChunkListMetric>(6);
        metrics.add(qInit);
        metrics.add(q000);
        metrics.add(q025);
        metrics.add(q050);
        metrics.add(q075);
        metrics.add(q100);
        chunkListMetrics = Collections.unmodifiableList(metrics);
    }

    /**
     * head 的 prev 和 next 都指向自己
     * @param pageSize
     * @return
     */
    private PoolSubpage<T> newSubpagePoolHead(int pageSize) {
        PoolSubpage<T> head = new PoolSubpage<T>(pageSize);
        head.prev = head;
        head.next = head;
        return head;
    }

    /**
     * 创建tiny Subpage 数组对象
     * @param size
     * @return
     */
    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpagePoolArray(int size) {
        return new PoolSubpage[size];
    }

    /**
     * 申请的内存是否是直接内存
     * @return
     */
    abstract boolean isDirect();

    /**
     * 外部线程通过pooledByteBufAllocator分配内存 会转发到该方法
     * @param cache 传入一个 缓存对象
     * @param reqCapacity 请求的 容量
     * @param maxCapacity 最大允许容量
     * @return
     */
    PooledByteBuf<T> allocate(PoolThreadCache cache, int reqCapacity, int maxCapacity) {
        //这里只是创建容器 并没有设置内部的memory属性
        PooledByteBuf<T> buf = newByteBuf(maxCapacity);
        allocate(cache, buf, reqCapacity);
        return buf;
    }

    /**
     * 获得 tiny数组的 数量
     * @param normCapacity
     * @return
     */
    static int tinyIdx(int normCapacity) {
        return normCapacity >>> 4;
    }

    /**
     * 获得 small 数组的 元素数量
     * @param normCapacity
     * @return
     */
    static int smallIdx(int normCapacity) {
        int tableIdx = 0;
        int i = normCapacity >>> 10;
        while (i != 0) {
            i >>>= 1;
            tableIdx ++;
        }
        return tableIdx;
    }

    // capacity < pageSize
    boolean isTinyOrSmall(int normCapacity) {
        return (normCapacity & subpageOverflowMask) == 0;
    }

    // normCapacity < 512
    static boolean isTiny(int normCapacity) {
        return (normCapacity & 0xFFFFFE00) == 0;
    }

    /**
     * 从预先分配的内存块中划分内存并设置到buf中
     * @param cache 缓存对象
     * @param buf 需要分配内存的  buf 对象
     * @param reqCapacity 请求分配的大小
     */
    private void allocate(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity) {
        //修改成靠近规定的内存块大小
        final int normCapacity = normalizeCapacity(reqCapacity);
        //如果分配的大小 < page 代表要分配的是subpage
        if (isTinyOrSmall(normCapacity)) { // capacity < pageSize
            int tableIdx;
            PoolSubpage<T>[] table;
            //tiny or small
            boolean tiny = isTiny(normCapacity);

            if (tiny) { // < 512
                // 如果调用该方法的线程之前已经申请了空闲的tiny大小的内存块 直接使用
                if (cache.allocateTiny(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;
                }
                //换算出对应的下标  也就是/16  因为针对tinySubpagePools来讲以 16b为大小递增
                tableIdx = tinyIdx(normCapacity);
                table = tinySubpagePools;
            } else {
                //基本跟上面是一样的
                if (cache.allocateSmall(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;
                }
                // smallSubpagePools 以512为单位进行递增
                tableIdx = smallIdx(normCapacity);
                table = smallSubpagePools;
            }

            //获取到 对应的 subpage对象
            final PoolSubpage<T> head = table[tableIdx];

            /**
             * Synchronize on the head. This is needed as {@link PoolChunk#allocateSubpage(int)} and
             * {@link PoolChunk#free(long)} may modify the doubly linked list as well.
             * 多线程可能会在这里发生竞争,所以要加锁 如果在之前发现线程缓存了空闲的内存块 就不会进入这里,也就避免了竞争
             */
            synchronized (head) {
                final PoolSubpage<T> s = head.next;
                // 当subPage还没有分配任何内存块的时候 next指向自身  当之前针对某个tiny/small大小生成PooledSubPage后会连接到head后
                if (s != head) {
                    assert s.doNotDestroy && s.elemSize == normCapacity;
                    // 之前已经申请了一个page的大小用于分配某一规格的tiny/small内存块 这里尝试寻找空闲内存
                    long handle = s.allocate();
                    assert handle >= 0;
                    //使用subpage 初始化 chunk
                    s.chunk.initBufWithSubpage(buf, null, handle, reqCapacity);
                    //记录分配的内存大小
                    incTinySmallAllocation(tiny);
                    return;
                }
            }

            // 代表此时还没有分配任何内存 创建一个poolChunk(此时已经完成了chunkSize的内存申请) 并按需划分内存
            synchronized (this) {
                allocateNormal(buf, reqCapacity, normCapacity);
            }

            //记录分配的内存大小
            incTinySmallAllocation(tiny);
            return;
        }
        //代表申请的大小小于chunkSize
        if (normCapacity <= chunkSize) {
            //先尝试从本地线程缓存中获取
            if (cache.allocateNormal(this, buf, reqCapacity, normCapacity)) {
                // was able to allocate out of the cache so move on
                return;
            }
            synchronized (this) {
                //分配内存 并记录分配的数量
                allocateNormal(buf, reqCapacity, normCapacity);
                ++allocationsNormal;
            }
        } else {
            // Huge allocations are never served via the cache so just call allocateHuge
            //当申请的内存块大小超过chunk时 不支持进行池化
            allocateHuge(buf, reqCapacity);
        }
    }

    /**
     * Method must be called inside synchronized(this) { ... } block
     * @param buf
     * @param reqCapacity
     * @param normCapacity
     * 首次分配一个内存块
     */
    private void allocateNormal(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        // 代表此时有可用的内存块 不需要重复分配 注意这里复用内存块存在一个优先级顺序
        // 先从使用率较高的q050开始获取, 这时倾向于复用之前创建的内存,之后是025/000 倾向于分配新内存 最后是075
        if (q050.allocate(buf, reqCapacity, normCapacity) || q025.allocate(buf, reqCapacity, normCapacity) ||
            q000.allocate(buf, reqCapacity, normCapacity) || qInit.allocate(buf, reqCapacity, normCapacity) ||
            q075.allocate(buf, reqCapacity, normCapacity)) {
            return;
        }

        // Add a new chunk.
        // chunk对象内包含了分配算法的实现
        PoolChunk<T> c = newChunk(pageSize, maxOrder, pageShifts, chunkSize);
        // 此时内存已经分配完成了 会将chunk大小的内存中可以使用的部分对应的offset/length设置到 pooledByteBuf中
        boolean success = c.allocate(buf, reqCapacity, normCapacity);
        assert success;
        //加入到 init 中 随着使用率上升 会移动到 使用率更高的ChunkList 中
        qInit.add(c);
    }

    private void incTinySmallAllocation(boolean tiny) {
        if (tiny) {
            allocationsTiny.increment();
        } else {
            allocationsSmall.increment();
        }
    }

    /**
     * 分配大内存
     * @param buf
     * @param reqCapacity
     */
    private void allocateHuge(PooledByteBuf<T> buf, int reqCapacity) {
        //大内存 使用 非池化的方式进行创建
        PoolChunk<T> chunk = newUnpooledChunk(reqCapacity);
        //增加 分配的 huge 内存大小
        activeBytesHuge.add(chunk.chunkSize());
        //使用创建的 chunk 未buf 分配内存
        buf.initUnpooled(chunk, reqCapacity);
        //增加分配的 huge 内存数量
        allocationsHuge.increment();
    }

    /**
     * 当某个由本对象创建的pooledByteBuf引用计数清0后 会将对应的chunk的内存块标记成可用状态
     * @param chunk 旧的chunk对象
     * @param nioBuffer
     * @param handle 用来定位使用了哪个page 以及使用了 哪个subpage(如果有的话)
     * @param normCapacity
     * @param cache
     */
    void free(PoolChunk<T> chunk, ByteBuffer nioBuffer, long handle, int normCapacity, PoolThreadCache cache) {

        // 代表本次分配的是一个huge内存 不支持池化
        if (chunk.unpooled) {
            int size = chunk.chunkSize();
            //销毁chunk对象  也就是回收内存
            destroyChunk(chunk);
            //将 可以用的huge 对象减少
            activeBytesHuge.add(-size);
            //增加回收的huge 对象
            deallocationsHuge.increment();
        } else {
            SizeClass sizeClass = sizeClass(normCapacity);
            // 优先选择将内存块归还到本地线程中
            if (cache != null && cache.add(this, chunk, nioBuffer, handle, normCapacity, sizeClass)) {
                // cached so not free it.
                return;
            }

            // 代表本地线程(创建buffer的io线程 缓存已经满了 只能归还内存到chunk中)
            freeChunk(chunk, handle, sizeClass, nioBuffer);
        }
    }

    private SizeClass sizeClass(int normCapacity) {
        if (!isTinyOrSmall(normCapacity)) {
            return SizeClass.Normal;
        }
        return isTiny(normCapacity) ? SizeClass.Tiny : SizeClass.Small;
    }

    /**
     * 释放内存 也就是归还到 arena
     * @param chunk 需要被归还的 chunk 对象
     * @param handle 指向哪个page 如果分配的是 subpage 级别还会 获得bitmap的下标用来确定分配的 位置
     * @param sizeClass
     * @param nioBuffer
     */
    void freeChunk(PoolChunk<T> chunk, long handle, SizeClass sizeClass, ByteBuffer nioBuffer) {
        //是否要销毁 chunk
        final boolean destroyChunk;
        synchronized (this) {
            switch (sizeClass) {
                //如果是 normal 增加 释放的 normal 内存数量
            case Normal:
                ++deallocationsNormal;
                break;
                //如果是 small 增加 释放的small 内存数量
            case Small:
                ++deallocationsSmall;
                break;
                //增加释放的  tiny 数量
            case Tiny:
                ++deallocationsTiny;
                break;
            default:
                //不应该存在的 内存 规格
                throw new Error();
            }
            //尝试移动chunk的位置 (因为chunk内存使用率发生了变化)
            destroyChunk = !chunk.parent.free(chunk, handle, nioBuffer);
        }
        //当该chunk内所有内存都释放完毕后 使用率归0 就可以释放chunk了
        if (destroyChunk) {
            // destroyChunk not need to be called while holding the synchronized lock.
            // 销毁不需要 内置锁 包裹
            destroyChunk(chunk);
        }
    }

    /**
     * 根据指定大小 定位到subPage 数组中的哪个元素
     * @param elemSize
     * @return
     */
    PoolSubpage<T> findSubpagePoolHead(int elemSize) {
        int tableIdx;
        PoolSubpage<T>[] table;
        if (isTiny(elemSize)) { // < 512
            tableIdx = elemSize >>> 4;
            table = tinySubpagePools;
        } else {
            tableIdx = 0;
            elemSize >>>= 10;
            while (elemSize != 0) {
                elemSize >>>= 1;
                tableIdx ++;
            }
            table = smallSubpagePools;
        }

        //大小 直接就对应下标
        return table[tableIdx];
    }

    /**
     * 将请求的大小变成2 的幂次
     * @param reqCapacity
     * @return
     */
    int normalizeCapacity(int reqCapacity) {
        if (reqCapacity < 0) {
            throw new IllegalArgumentException("capacity: " + reqCapacity + " (expected: 0+)");
        }

        //请求大小如果超过 chunk 不做处理
        if (reqCapacity >= chunkSize) {
            //这个对齐默认是0 先不看 就当直接返回请求大小
            return directMemoryCacheAlignment == 0 ? reqCapacity : alignCapacity(reqCapacity);
        }

        if (!isTiny(reqCapacity)) { // >= 512
            // Doubled  转换成 2的幂次

            int normalizedCapacity = reqCapacity;
            normalizedCapacity --;
            normalizedCapacity |= normalizedCapacity >>>  1;
            normalizedCapacity |= normalizedCapacity >>>  2;
            normalizedCapacity |= normalizedCapacity >>>  4;
            normalizedCapacity |= normalizedCapacity >>>  8;
            normalizedCapacity |= normalizedCapacity >>> 16;
            normalizedCapacity ++;

            if (normalizedCapacity < 0) {
                normalizedCapacity >>>= 1;
            }
            assert directMemoryCacheAlignment == 0 || (normalizedCapacity & directMemoryCacheAlignmentMask) == 0;

            return normalizedCapacity;
        }

        if (directMemoryCacheAlignment > 0) {
            return alignCapacity(reqCapacity);
        }

        // Quantum-spaced
        // 已经是16的倍数了 不需要处理
        if ((reqCapacity & 15) == 0) {
            return reqCapacity;
        }

        //向上补成16的倍数
        return (reqCapacity & ~15) + 16;
    }

    int alignCapacity(int reqCapacity) {
        int delta = reqCapacity & directMemoryCacheAlignmentMask;
        return delta == 0 ? reqCapacity : reqCapacity + directMemoryCacheAlignment - delta;
    }

    /**
     * 某个PooledByteBuf此时分配的内存量不合适 需要重新分配
     * @param buf 分配的内存 存放的 buf对象
     * @param newCapacity 新的 申请大小
     * @param freeOldMemory 是否要释放旧的内存
     */
    void reallocate(PooledByteBuf<T> buf, int newCapacity, boolean freeOldMemory) {
        if (newCapacity < 0 || newCapacity > buf.maxCapacity()) {
            throw new IllegalArgumentException("newCapacity: " + newCapacity);
        }

        //如果旧的大小和新的大小一样直接返回
        int oldCapacity = buf.length;
        if (oldCapacity == newCapacity) {
            return;
        }

        //获取buf 关联的 chunk 对象
        PoolChunk<T> oldChunk = buf.chunk;
        //获取 临时的 bytebuf 对象
        ByteBuffer oldNioBuffer = buf.tmpNioBuf;
        //通过handle可以追踪到buf的内存块是从哪个chunk的什么位置分配来的
        long oldHandle = buf.handle;
        //旧的内存对象
        T oldMemory = buf.memory;
        //获取内存偏移量
        int oldOffset = buf.offset;
        //获取旧的最大长度
        int oldMaxLength = buf.maxLength;
        //读写指针
        int readerIndex = buf.readerIndex();
        int writerIndex = buf.writerIndex();

        //为buf 对象分配指定的内存大小
        allocate(parent.threadCache(), buf, newCapacity);
        //如果是 扩容 就要将 原来的数据 复制进去
        if (newCapacity > oldCapacity) {
            memoryCopy(
                    oldMemory, oldOffset,
                    buf.memory, buf.offset, oldCapacity);
        } else if (newCapacity < oldCapacity) {
            //小于读指针的情况
            if (readerIndex < newCapacity) {
                //缩容情况 丢弃部分数据 如果 writeIndex < newCapacity 就更不需要改变了 直接拷贝旧数据
                if (writerIndex > newCapacity) {
                    writerIndex = newCapacity;
                }
                memoryCopy(
                        oldMemory, oldOffset + readerIndex,
                        buf.memory, buf.offset + readerIndex, writerIndex - readerIndex);
            } else {
                //读写指针 同时缩小  这里数据都已经读完 就不用复制了
                readerIndex = writerIndex = newCapacity;
            }
        }

        buf.setIndex(readerIndex, writerIndex);

        //代表需要释放旧的内存块
        if (freeOldMemory) {
            //释放旧的内存  因为指定了chunk和handle就能知道释放的是哪块内存了
            free(oldChunk, oldNioBuffer, oldHandle, oldMaxLength, buf.cache);
        }
    }

    @Override
    public int numThreadCaches() {
        return numThreadCaches.get();
    }

    @Override
    public int numTinySubpages() {
        return tinySubpagePools.length;
    }

    @Override
    public int numSmallSubpages() {
        return smallSubpagePools.length;
    }

    @Override
    public int numChunkLists() {
        return chunkListMetrics.size();
    }

    @Override
    public List<PoolSubpageMetric> tinySubpages() {
        return subPageMetricList(tinySubpagePools);
    }

    @Override
    public List<PoolSubpageMetric> smallSubpages() {
        return subPageMetricList(smallSubpagePools);
    }

    @Override
    public List<PoolChunkListMetric> chunkLists() {
        return chunkListMetrics;
    }

    private static List<PoolSubpageMetric> subPageMetricList(PoolSubpage<?>[] pages) {
        List<PoolSubpageMetric> metrics = new ArrayList<PoolSubpageMetric>();
        for (PoolSubpage<?> head : pages) {
            if (head.next == head) {
                continue;
            }
            PoolSubpage<?> s = head.next;
            for (;;) {
                metrics.add(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
        return metrics;
    }

    @Override
    public long numAllocations() {
        final long allocsNormal;
        synchronized (this) {
            allocsNormal = allocationsNormal;
        }
        return allocationsTiny.value() + allocationsSmall.value() + allocsNormal + allocationsHuge.value();
    }

    @Override
    public long numTinyAllocations() {
        return allocationsTiny.value();
    }

    @Override
    public long numSmallAllocations() {
        return allocationsSmall.value();
    }

    @Override
    public synchronized long numNormalAllocations() {
        return allocationsNormal;
    }

    @Override
    public long numDeallocations() {
        final long deallocs;
        synchronized (this) {
            deallocs = deallocationsTiny + deallocationsSmall + deallocationsNormal;
        }
        return deallocs + deallocationsHuge.value();
    }

    @Override
    public synchronized long numTinyDeallocations() {
        return deallocationsTiny;
    }

    @Override
    public synchronized long numSmallDeallocations() {
        return deallocationsSmall;
    }

    @Override
    public synchronized long numNormalDeallocations() {
        return deallocationsNormal;
    }

    @Override
    public long numHugeAllocations() {
        return allocationsHuge.value();
    }

    @Override
    public long numHugeDeallocations() {
        return deallocationsHuge.value();
    }

    @Override
    public  long numActiveAllocations() {
        long val = allocationsTiny.value() + allocationsSmall.value() + allocationsHuge.value()
                - deallocationsHuge.value();
        synchronized (this) {
            val += allocationsNormal - (deallocationsTiny + deallocationsSmall + deallocationsNormal);
        }
        return max(val, 0);
    }

    @Override
    public long numActiveTinyAllocations() {
        return max(numTinyAllocations() - numTinyDeallocations(), 0);
    }

    @Override
    public long numActiveSmallAllocations() {
        return max(numSmallAllocations() - numSmallDeallocations(), 0);
    }

    @Override
    public long numActiveNormalAllocations() {
        final long val;
        synchronized (this) {
            val = allocationsNormal - deallocationsNormal;
        }
        return max(val, 0);
    }

    @Override
    public long numActiveHugeAllocations() {
        return max(numHugeAllocations() - numHugeDeallocations(), 0);
    }

    @Override
    public long numActiveBytes() {
        long val = activeBytesHuge.value();
        synchronized (this) {
            for (int i = 0; i < chunkListMetrics.size(); i++) {
                for (PoolChunkMetric m: chunkListMetrics.get(i)) {
                    val += m.chunkSize();
                }
            }
        }
        return max(0, val);
    }

    /**
     * 创建一个新的chunk 对象
     * @param pageSize
     * @param maxOrder
     * @param pageShifts
     * @param chunkSize
     * @return
     */
    protected abstract PoolChunk<T> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize);

    /**
     * 创建一个新的非池化 chunk
     * @param capacity
     * @return
     */
    protected abstract PoolChunk<T> newUnpooledChunk(int capacity);

    /**
     * 根据指定大小 生成对应的 PooledBytebuf 对象
     * @param maxCapacity
     * @return
     */
    protected abstract PooledByteBuf<T> newByteBuf(int maxCapacity);

    /**
     * 将 给定的数据 复制到 目标对象
     * @param src 旧的数据体
     * @param srcOffset 旧的偏移量
     * @param dst 新的数据体
     * @param dstOffset 新的偏移量
     * @param length copy 的长度
     */
    protected abstract void memoryCopy(T src, int srcOffset, T dst, int dstOffset, int length);

    /**
     * 销毁chunk 对象
     * @param chunk
     */
    protected abstract void destroyChunk(PoolChunk<T> chunk);

    @Override
    public synchronized String toString() {
        StringBuilder buf = new StringBuilder()
            .append("Chunk(s) at 0~25%:")
            .append(StringUtil.NEWLINE)
            .append(qInit)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 0~50%:")
            .append(StringUtil.NEWLINE)
            .append(q000)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 25~75%:")
            .append(StringUtil.NEWLINE)
            .append(q025)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 50~100%:")
            .append(StringUtil.NEWLINE)
            .append(q050)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 75~100%:")
            .append(StringUtil.NEWLINE)
            .append(q075)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 100%:")
            .append(StringUtil.NEWLINE)
            .append(q100)
            .append(StringUtil.NEWLINE)
            .append("tiny subpages:");
        appendPoolSubPages(buf, tinySubpagePools);
        buf.append(StringUtil.NEWLINE)
           .append("small subpages:");
        appendPoolSubPages(buf, smallSubpagePools);
        buf.append(StringUtil.NEWLINE);

        return buf.toString();
    }

    private static void appendPoolSubPages(StringBuilder buf, PoolSubpage<?>[] subpages) {
        for (int i = 0; i < subpages.length; i ++) {
            PoolSubpage<?> head = subpages[i];
            if (head.next == head) {
                continue;
            }

            buf.append(StringUtil.NEWLINE)
                    .append(i)
                    .append(": ");
            PoolSubpage<?> s = head.next;
            for (;;) {
                buf.append(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
    }

    /**
     * 终结方法 一般不推荐使用 当对象被回收时会隐性调用
     * @throws Throwable
     */
    @Override
    protected final void finalize() throws Throwable {
        try {
            //一般都会调用父类的 该方法 保证不出现什么意外
            super.finalize();
        } finally {
            //销毁 small级别的 数组对象
            destroyPoolSubPages(smallSubpagePools);
            //销毁 tiny级别的 数组对象
            destroyPoolSubPages(tinySubpagePools);
            //销毁 chunkList 链表对象
            destroyPoolChunkLists(qInit, q000, q025, q050, q075, q100);
        }
    }

    /**
     * 批量销毁 subpage对象 最终都会来到 PoolArea.destroyChunk(chunk)
     * @param pages
     */
    private static void destroyPoolSubPages(PoolSubpage<?>[] pages) {
        for (PoolSubpage<?> page : pages) {
            page.destroy();
        }
    }

    /**
     * 批量销毁 chunkList 对象  批量销毁 subpage对象 最终都会来到 PoolArea.destroyChunk(chunk)
     * @param chunkLists
     */
    private void destroyPoolChunkLists(PoolChunkList<T>... chunkLists) {
        for (PoolChunkList<T> chunkList: chunkLists) {
            chunkList.destroy(this);
        }
    }

    /**
     * 基于堆内存生成arena
     */
    static final class HeapArena extends PoolArena<byte[]> {

        HeapArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
                int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
                    directMemoryCacheAlignment);
        }

        /**
         * 就是创建一个 byte[] 对象
         * @param size
         * @return
         */
        private static byte[] newByteArray(int size) {
            return PlatformDependent.allocateUninitializedArray(size);
        }

        /**
         * 直接返回 非 Direct
         * @return
         */
        @Override
        boolean isDirect() {
            return false;
        }

        /**
         * 分配一个chunk大小的内存块
         * @param pageSize
         * @param maxOrder
         * @param pageShifts
         * @param chunkSize
         * @return
         */
        @Override
        protected PoolChunk<byte[]> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            return new PoolChunk<byte[]>(this, newByteArray(chunkSize), pageSize, maxOrder, pageShifts, chunkSize, 0);
        }

        /**
         * 池化 和非池化 的参数是不一样的
         * @param capacity
         * @return
         */
        @Override
        protected PoolChunk<byte[]> newUnpooledChunk(int capacity) {
            return new PoolChunk<byte[]>(this, newByteArray(capacity), capacity, 0);
        }

        /**
         * heap 内存 会通过GC回收
         */
        @Override
        protected void destroyChunk(PoolChunk<byte[]> chunk) {
            // Rely on GC.
        }

        /**
         * @param maxCapacity
         * @return
         */
        @Override
        protected PooledByteBuf<byte[]> newByteBuf(int maxCapacity) {
            return HAS_UNSAFE ? PooledUnsafeHeapByteBuf.newUnsafeInstance(maxCapacity)
                    : PooledHeapByteBuf.newInstance(maxCapacity);
        }

        /**
         * 拷贝 对于 heap 对象来说就是拷贝数组
         * @param src 旧的数据体
         * @param srcOffset 旧的偏移量
         * @param dst 新的数据体
         * @param dstOffset 新的偏移量
         * @param length copy 的长度
         */
        @Override
        protected void memoryCopy(byte[] src, int srcOffset, byte[] dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            System.arraycopy(src, srcOffset, dst, dstOffset, length);
        }
    }

    //直接内存对象
    static final class DirectArena extends PoolArena<ByteBuffer> {

        DirectArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
                int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
                    directMemoryCacheAlignment);
        }

        /**
         * 代表是 直接内存
         * @return
         */
        @Override
        boolean isDirect() {
            return true;
        }

        // mark as package-private, only for unit test
        int offsetCacheLine(ByteBuffer memory) {
            // We can only calculate the offset if Unsafe is present as otherwise directBufferAddress(...) will
            // throw an NPE.
            int remainder = HAS_UNSAFE
                    ? (int) (PlatformDependent.directBufferAddress(memory) & directMemoryCacheAlignmentMask)
                    : 0;

            // offset = alignment - address & (alignment - 1)
            return directMemoryCacheAlignment - remainder;
        }

        /**
         * 创建一个新的 chunk 对象
         * @param pageSize
         * @param maxOrder
         * @param pageShifts
         * @param chunkSize
         * @return
         */
        @Override
        protected PoolChunk<ByteBuffer> newChunk(int pageSize, int maxOrder,
                int pageShifts, int chunkSize) {
            if (directMemoryCacheAlignment == 0) {
                return new PoolChunk<ByteBuffer>(this,
                        //根据指定大小分配直接内存
                        allocateDirect(chunkSize), pageSize, maxOrder,
                        pageShifts, chunkSize, 0);
            }
            // 默认对齐值都是0 先忽略
            final ByteBuffer memory = allocateDirect(chunkSize
                    + directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, memory, pageSize,
                    maxOrder, pageShifts, chunkSize,
                    //这里就计算了 偏移量  跟 offset 有关的 还不懂
                    offsetCacheLine(memory));
        }


        /**
         * 创建非池化的 对象  池化和非池化的 参数不一样
         * @param capacity
         * @return
         */
        @Override
        protected PoolChunk<ByteBuffer> newUnpooledChunk(int capacity) {
            if (directMemoryCacheAlignment == 0) {
                return new PoolChunk<ByteBuffer>(this,
                        allocateDirect(capacity), capacity, 0);
            }
            final ByteBuffer memory = allocateDirect(capacity
                    + directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, memory, capacity,
                    offsetCacheLine(memory));
        }

        /**
         * 根据是否支持 cleaner 创建 direct内存
         * @param capacity
         * @return
         */
        private static ByteBuffer allocateDirect(int capacity) {
            return PlatformDependent.useDirectBufferNoCleaner() ?
                    PlatformDependent.allocateDirectNoCleaner(capacity) : ByteBuffer.allocateDirect(capacity);
        }

        /**
         * 释放内存
         * @param chunk
         */
        @Override
        protected void destroyChunk(PoolChunk<ByteBuffer> chunk) {
            if (PlatformDependent.useDirectBufferNoCleaner()) {
                //不使用clean 直接使用unsafe 进行内存释放
                PlatformDependent.freeDirectNoCleaner(chunk.memory);
            } else {
                //使用 反射获取的 clean 对象进行内存释放
                PlatformDependent.freeDirectBuffer(chunk.memory);
            }
        }

        /**
         * 创建 bytebuf 对象
         * @param maxCapacity
         * @return
         */
        @Override
        protected PooledByteBuf<ByteBuffer> newByteBuf(int maxCapacity) {
            if (HAS_UNSAFE) {
                return PooledUnsafeDirectByteBuf.newInstance(maxCapacity);
            } else {
                return PooledDirectByteBuf.newInstance(maxCapacity);
            }
        }

        /**
         * 拷贝内存
         * @param src 旧的数据体
         * @param srcOffset 旧的偏移量
         * @param dst 新的数据体
         * @param dstOffset 新的偏移量
         * @param length copy 的长度
         */
        @Override
        protected void memoryCopy(ByteBuffer src, int srcOffset, ByteBuffer dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            //支持unsafe 直接用该对象操控内存
            if (HAS_UNSAFE) {
                PlatformDependent.copyMemory(
                        PlatformDependent.directBufferAddress(src) + srcOffset,
                        PlatformDependent.directBufferAddress(dst) + dstOffset, length);
            } else {
                // We must duplicate the NIO buffers because they may be accessed by other Netty buffers.
                //操作 NioBytebuffer对象 进行 拷贝
                src = src.duplicate();
                dst = dst.duplicate();
                src.position(srcOffset).limit(srcOffset + length);
                dst.position(dstOffset);
                dst.put(src);
            }
        }
    }
}
