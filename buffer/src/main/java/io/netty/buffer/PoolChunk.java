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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Description of algorithm for PageRun/PoolSubpage allocation from PoolChunk
 *
 * Notation: The following terms are important to understand the code
 * > page  - a page is the smallest unit of memory chunk that can be allocated
 * > chunk - a chunk is a collection of pages
 * > in this code chunkSize = 2^{maxOrder} * pageSize
 *
 * To begin we allocate a byte array of size = chunkSize
 * Whenever a ByteBuf of given size needs to be created we search for the first position
 * in the byte array that has enough empty space to accommodate the requested size and
 * return a (long) handle that encodes this offset information, (this memory segment is then
 * marked as reserved so it is always used by exactly one ByteBuf and no more)
 *
 * For simplicity all sizes are normalized according to PoolArena#normalizeCapacity method
 * This ensures that when we request for memory segments of size >= pageSize the normalizedCapacity
 * equals the next nearest power of 2
 *
 * To search for the first offset in chunk that has at least requested size available we construct a
 * complete balanced binary tree and store it in an array (just like heaps) - memoryMap
 *
 * The tree looks like this (the size of each node being mentioned in the parenthesis)
 *
 * depth=0        1 node (chunkSize)
 * depth=1        2 nodes (chunkSize/2)
 * ..
 * ..
 * depth=d        2^d nodes (chunkSize/2^d)
 * ..
 * depth=maxOrder 2^maxOrder nodes (chunkSize/2^{maxOrder} = pageSize)
 *
 * depth=maxOrder is the last level and the leafs consist of pages
 *
 * With this tree available searching in chunkArray translates like this:
 * To allocate a memory segment of size chunkSize/2^k we search for the first node (from left) at height k
 * which is unused
 *
 * Algorithm:
 * ----------
 * Encode the tree in memoryMap with the notation
 *   memoryMap[id] = x => in the subtree rooted at id, the first node that is free to be allocated
 *   is at depth x (counted from depth=0) i.e., at depths [depth_of_id, x), there is no node that is free
 *
 *  As we allocate & free nodes, we update values stored in memoryMap so that the property is maintained
 *
 * Initialization -
 *   In the beginning we construct the memoryMap array by storing the depth of a node at each node
 *     i.e., memoryMap[id] = depth_of_id
 *
 * Observations:
 * -------------
 * 1) memoryMap[id] = depth_of_id  => it is free / unallocated
 * 2) memoryMap[id] > depth_of_id  => at least one of its child nodes is allocated, so we cannot allocate it, but
 *                                    some of its children can still be allocated based on their availability
 * 3) memoryMap[id] = maxOrder + 1 => the node is fully allocated & thus none of its children can be allocated, it
 *                                    is thus marked as unusable
 *
 * Algorithm: [allocateNode(d) => we want to find the first node (from left) at height h that can be allocated]
 * ----------
 * 1) start at root (i.e., depth = 0 or id = 1)
 * 2) if memoryMap[1] > d => cannot be allocated from this chunk
 * 3) if left node value <= h; we can allocate from left subtree so move to left and repeat until found
 * 4) else try in right subtree
 *
 * Algorithm: [allocateRun(size)]
 * ----------
 * 1) Compute d = log_2(chunkSize/size)
 * 2) Return allocateNode(d)
 *
 * Algorithm: [allocateSubpage(size)]
 * ----------
 * 1) use allocateNode(maxOrder) to find an empty (i.e., unused) leaf (i.e., page)
 * 2) use this handle to construct the PoolSubpage object or if it already exists just call init(normCapacity)
 *    note that this PoolSubpage object is added to subpagesPool in the PoolArena when we init() it
 *
 * Note:
 * -----
 * In the implementation for improving cache coherence,
 * we store 2 pieces of information depth_of_id and x as two byte values in memoryMap and depthMap respectively
 *
 * memoryMap[id]= depth_of_id  is defined above
 * depthMap[id]= x  indicates that the first node which is free to be allocated is at depth x (from root)
 *
 * 分配内存的 对象 最大为 arena 其次是 chunk  chunk 包含2048 个 page 该对象实现一个数据统计接口
 */
final class PoolChunk<T> implements PoolChunkMetric {

    private static final int INTEGER_SIZE_MINUS_ONE = Integer.SIZE - 1;

    /**
     * 属于哪个 PoolArena
     */
    final PoolArena<T> arena;
    /**
     * 属于 heap 内存 or direct 内存
     */
    final T memory;
    /**
     * 是否非池化
     */
    final boolean unpooled;
    final int offset;
    /**
     * 里面的数值 如果等于  page 深度 就是未分配 越接近最大深度  分配了 越多的内存 超过最大深度 代表无内存可分配
     */
    private final byte[] memoryMap;
    /**
     * 记录当前page 深度的
     */
    private final byte[] depthMap;
    /**
     * 该chunk 所属 的 subPage 数组对象
     */
    private final PoolSubpage<T>[] subpages;
    /** Used to determine if the requested capacity is equal to or greater than pageSize. */
    private final int subpageOverflowMask;
    private final int pageSize;
    private final int pageShifts;
    private final int maxOrder;
    private final int chunkSize;
    private final int log2ChunkSize;
    private final int maxSubpageAllocs;
    /** Used to mark memory as unusable */
    private final byte unusable;

    // Use as cache for ByteBuffer created from the memory. These are just duplicates and so are only a container
    // around the memory itself. These are often needed for operations within the Pooled*ByteBuf and so
    // may produce extra GC, which can be greatly reduced by caching the duplicates.
    //
    // This may be null if the PoolChunk is unpooled as pooling the ByteBuffer instances does not make any sense here.
    private final Deque<ByteBuffer> cachedNioBuffers;

    /**
     * 可分配的 剩余内存
     */
    private int freeBytes;

    /**
     * 该chunk 所属的 chunkList
     */
    PoolChunkList<T> parent;
    //Chunk 本身是一个链表结构 chunk 是从Arena 上分配过来的  Arena 是 内存分配的 最大单位
    PoolChunk<T> prev;
    PoolChunk<T> next;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /**
     * 池化的 构造函数  该对象一般是由 Allocate 对象 进行创建的
     * @param arena
     * @param memory
     * @param pageSize
     * @param maxOrder
     * @param pageShifts
     * @param chunkSize
     * @param offset
     */
    PoolChunk(PoolArena<T> arena, T memory, int pageSize, int maxOrder, int pageShifts, int chunkSize, int offset) {
        unpooled = false;
        this.arena = arena;
        this.memory = memory;
        //默认是 8k
        this.pageSize = pageSize;
        // 默认是 13 代表 1要左移多少位到达 pageSize
        this.pageShifts = pageShifts;
        //二叉树的高度 为11 默认从0开始
        this.maxOrder = maxOrder;
        //默认大小为 16m
        this.chunkSize = chunkSize;
        this.offset = offset;
        //起始标识为 最大深度+1  当节点被使用后 会被标记成 unusable   并且会更新 父节点的 高度值变成 较小的那个可用值 想当于是降级了
        unusable = (byte) (maxOrder + 1);
        //默认为 24
        log2ChunkSize = log2(chunkSize);
        //判断 分配的内存是否是 小于 page 的 比如 small/tiny
        subpageOverflowMask = ~(pageSize - 1);
        //剩余 可用的 bytes  默认就是 chunkSize的大小
        freeBytes = chunkSize;

        assert maxOrder < 30 : "maxOrder should be < 30, but is: " + maxOrder;
        maxSubpageAllocs = 1 << maxOrder;

        // Generate the memory map.
        //这里存放 内存块  就是2048的那个
        memoryMap = new byte[maxSubpageAllocs << 1];
        //这里记录对应元素的深度
        depthMap = new byte[memoryMap.length];
        //起点从1开始 对应[1]为起点的 二叉树 子节点 就分别为 2x 2x+1
        int memoryMapIndex = 1;
        for (int d = 0; d <= maxOrder; ++ d) { // move down the tree one level at a time
            //外层代表这层有多少元素
            int depth = 1 << d;
            //内存是在遍历这层的每个元素
            for (int p = 0; p < depth; ++ p) {

                //对于memoryMap 来说 总共存在3种情况 根据这个值的变化 来推断 是否有可分配内存
                //1.memory[i] = depthMap[i] 代表这块内存 还没有被分配
                //2.depthMap[i] < memory[I] <= 最大高度 代表已经被分配了部分内存
                //3.memory[i] = 最大高度+1 代表已经被完全分配

                // in each level traverse left to right and set value to the depth of subtree
                // 分配节点的时候 这个值会发生变动
                memoryMap[memoryMapIndex] = (byte) d;
                //同一层的深度是相同的
                depthMap[memoryMapIndex] = (byte) d;
                memoryMapIndex ++;
            }
        }

        //代表分配2048个 subpage
        subpages = newSubpageArray(maxSubpageAllocs);
        //创建一个双端队列对象
        cachedNioBuffers = new ArrayDeque<ByteBuffer>(8);
    }

    /** Creates a special chunk that is not pooled.
     *  非池化   什么时候使用非池化创建对象???
     * */
    PoolChunk(PoolArena<T> arena, T memory, int size, int offset) {
        unpooled = true;
        this.arena = arena;
        this.memory = memory;
        this.offset = offset;
        memoryMap = null;
        depthMap = null;
        subpages = null;
        subpageOverflowMask = 0;
        pageSize = 0;
        pageShifts = 0;
        maxOrder = 0;
        unusable = (byte) (maxOrder + 1);
        chunkSize = size;
        log2ChunkSize = log2(chunkSize);
        maxSubpageAllocs = 0;
        cachedNioBuffers = null;
    }

    /**
     * 根据指定大小 分配 SubPage 数组对象
     * @param size
     * @return
     */
    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpageArray(int size) {
        return new PoolSubpage[size];
    }

    /**
     * 代表使用率
     * @return
     */
    @Override
    public int usage() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }
        return usage(freeBytes);
    }

    /**
     * 代表使用率
     * @param freeBytes
     * @return
     */
    private int usage(int freeBytes) {
        //没有 多余的内存可用 使用率为 100
        if (freeBytes == 0) {
            return 100;
        }

        int freePercentage = (int) (freeBytes * 100L / chunkSize);
        if (freePercentage == 0) {
            return 99;
        }
        return 100 - freePercentage;
    }

    /**
     * 分配指定大小的内存
     * @param buf
     * @param reqCapacity 请求的实际大小
     * @param normCapacity 这个应该是 通过 换算得到 2的 幂次的结果
     * @return
     */
    boolean allocate(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {
        final long handle;
        //通过掩码 确定 要分配的大小
        if ((normCapacity & subpageOverflowMask) != 0) { // >= pageSize
            //以page 为单位 进行分配
            handle =  allocateRun(normCapacity);
        } else {
            //以 subpage 为单位进行分配
            handle = allocateSubpage(normCapacity);
        }

        //分配结果 小于0 代表分配失败
        if (handle < 0) {
            return false;
        }
        //这里从双端队列中拿出最后一个元素 这时没数据 应该是返回null 吧
        ByteBuffer nioBuffer = cachedNioBuffers != null ? cachedNioBuffers.pollLast() : null;
        //初始化 buf 对象
        initBuf(buf, nioBuffer, handle, reqCapacity);
        return true;
    }

    /**
     * Update method used by allocate
     * This is triggered only when a successor is allocated and all its predecessors
     * need to update their state
     * The minimal depth at which subtree rooted at id has some free space
     *
     * 更新父节点 信息 不断递归向上 每层的 元素 深度都要增加
     * @param id id
     */
    private void updateParentsAlloc(int id) {
        while (id > 1) {
            //获取 父节点 因为 去尾法 所以 2X+1 /2 也是 X
            int parentId = id >>> 1;
            //获取 左值和 右值
            byte val1 = value(id);
            byte val2 = value(id ^ 1);
            //更新为 更小的那个值 对于一个父节点来说 如果 左值发生变化 那么会取较小的右值 也代表这个 还能够分配一个完整的 右值
            //如果 在分配内存时 按照深度找到较小的左值 就会被修改成unusuable 但是对右值还是不影响的 也就是对外来说 父节点 还是能
            //分配一个完整的 右值 如果右值的 子节点(不是右值本身 因为 每次都会找到 对应最合理的 节点直接设置成 unusuable)被分配了一部分
            //就设置成 新的 右值 代表该节点只能分配 更小的内存 同时二叉树 都是分治思想通过递归方式 始终是从最小往上走
            byte val = val1 < val2 ? val1 : val2;
            setValue(parentId, val);
            id = parentId;
        }
    }

    /**
     * Update method used by free
     * This needs to handle the special case when both children are completely free
     * in which case parent be directly allocated on request of size = child-size * 2
     *
     * @param id id
     */
    private void updateParentsFree(int id) {
        int logChild = depth(id) + 1;
        while (id > 1) {
            int parentId = id >>> 1;
            byte val1 = value(id);
            byte val2 = value(id ^ 1);
            logChild -= 1; // in first iteration equals log, subsequently reduce 1 from logChild as we traverse up

            if (val1 == logChild && val2 == logChild) {
                setValue(parentId, (byte) (logChild - 1));
            } else {
                byte val = val1 < val2 ? val1 : val2;
                setValue(parentId, val);
            }

            id = parentId;
        }
    }

    /**
     * Algorithm to allocate an index in memoryMap when we query for a free node
     * at depth d
     *
     * 根据 所在层数 分配对象的 内存
     * @param d depth  代表尝试分配 第几层的内存
     * @return index in memoryMap
     */
    private int allocateNode(int d) {
        int id = 1;
        int initial = - (1 << d); // has last d bits = 0 and rest all = 1
        //获得根节点的 下标 如果根节点的 下标都已经超过 当前深度了 代表 这个 Chunk 已经没有 足够的内存去分配了
        //因为每次子节点 分配出去 父节点的 深度值都会增加
        byte val = value(id);
        if (val > d) { // unusable
            return -1;
        }
        //当还有内存分配的时候  后半段不懂  好像 id小于d 时就是0
        //总结就是从左往右找到第一个 符合分配内存条件的 内存块 并且 深度 在相等时 才会退出循环 合理分配最合适的大小
        while (val < d || (id & initial) == 0) { // id & initial == 1 << d for all ids at depth d, for < d it is 0
            //对应到 二叉树的 左下标
            id <<= 1;
            //获取左下标的值 这个值 每次子元素进行分配都会变化
            val = value(id);
            //代表不能分配
            if (val > d) {
                //这是取反运算  相反为1 相同为0  针对 左右相邻的节点 变化只是 最后一位由0 变成1 也就是0的位还是0
                //左节点尾部肯定是0 ^= 1 就会变成1 也就是变成右节点
                id ^= 1;
                val = value(id);
            }
        }
        //得到最合适的 memory[] 下标  得到的是深度(可能已经进行过增加)
        byte value = value(id);
        //因为这里已经是 找到 跟 深度相同的 内存块了  所以是分配到合适的大小 那么 这个可以直接设置为 unusable
        assert value == d && (id & initial) == 1 << d : String.format("val = %d, id & initial = %d, d = %d",
                value, id & initial, d);
        setValue(id, unusable); // mark as unusable
        //更新 父节点 应该是增加深度 代表该父节点的某些子节点已经使用过
        updateParentsAlloc(id);
        return id;
    }

    /**
     * Allocate a run of pages (>=1)
     *
     * 以page 为单位进行内存分配
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     */
    private long allocateRun(int normCapacity) {
        //能够计算出在第几层
        int d = maxOrder - (log2(normCapacity) - pageShifts);
        //分配指定的节点
        int id = allocateNode(d);
        //代表没有分配到 内存 直接返回 同时 外层方法  得到负数也就知道 分配失败了
        if (id < 0) {
            return id;
        }
        //减少可分配的 内存 这个 id 看来是 分配到的内存 所在的层数 因为层数 通过 <<  能直接得到 分配大小
        freeBytes -= runLength(id);
        return id;
    }

    /**
     * Create / initialize a new PoolSubpage of normCapacity
     * Any PoolSubpage created / initialized here is added to subpage pool in the PoolArena that owns this PoolChunk
     *
     * @param normCapacity normalized capacity
     * @return index in memoryMap
     * 分配 subpage 大小
     */
    private long allocateSubpage(int normCapacity) {
        // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
        // This is need as we may add it back and so alter the linked-list structure.
        // 根据申请大小定位到 subpage 对象
        PoolSubpage<T> head = arena.findSubpagePoolHead(normCapacity);
        int d = maxOrder; // subpages are only be allocated from pages i.e., leaves
        synchronized (head) {
            int id = allocateNode(d);
            if (id < 0) {
                return id;
            }

            final PoolSubpage<T>[] subpages = this.subpages;
            final int pageSize = this.pageSize;

            freeBytes -= pageSize;

            int subpageIdx = subpageIdx(id);
            PoolSubpage<T> subpage = subpages[subpageIdx];
            if (subpage == null) {
                subpage = new PoolSubpage<T>(head, this, id, runOffset(id), pageSize, normCapacity);
                subpages[subpageIdx] = subpage;
            } else {
                subpage.init(head, normCapacity);
            }
            return subpage.allocate();
        }
    }

    /**
     * Free a subpage or a run of pages
     * When a subpage is freed from PoolSubpage, it might be added back to subpage pool of the owning PoolArena
     * If the subpage pool in PoolArena has at least one other PoolSubpage of given elemSize, we can
     * completely free the owning Page so it is available for subsequent allocations
     *
     * @param handle handle to free
     */
    void free(long handle, ByteBuffer nioBuffer) {
        int memoryMapIdx = memoryMapIdx(handle);
        int bitmapIdx = bitmapIdx(handle);

        if (bitmapIdx != 0) { // free a subpage
            PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
            assert subpage != null && subpage.doNotDestroy;

            // Obtain the head of the PoolSubPage pool that is owned by the PoolArena and synchronize on it.
            // This is need as we may add it back and so alter the linked-list structure.
            PoolSubpage<T> head = arena.findSubpagePoolHead(subpage.elemSize);
            synchronized (head) {
                if (subpage.free(head, bitmapIdx & 0x3FFFFFFF)) {
                    return;
                }
            }
        }
        freeBytes += runLength(memoryMapIdx);
        setValue(memoryMapIdx, depth(memoryMapIdx));
        updateParentsFree(memoryMapIdx);

        if (nioBuffer != null && cachedNioBuffers != null &&
                cachedNioBuffers.size() < PooledByteBufAllocator.DEFAULT_MAX_CACHED_BYTEBUFFERS_PER_CHUNK) {
            cachedNioBuffers.offer(nioBuffer);
        }
    }

    /**
     * 初始化 buf 对象
     * @param buf 可能是null
     * @param nioBuffer 传入 从双端队列中拿出来的元素
     * @param handle 代表 内存块 所在的下标
     * @param reqCapacity 实际请求的 大小
     */
    void initBuf(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        //这里只是将 long 变成int
        int memoryMapIdx = memoryMapIdx(handle);
        //获得一个 bitMap 的下标  针对 page 这个值就是没用的
        int bitmapIdx = bitmapIdx(handle);
        //这里是 判别是 page 还是 subpage  page 的话 都会是0
        if (bitmapIdx == 0) {
            //这里的 val 应该就是 unusable
            byte val = value(memoryMapIdx);
            assert val == unusable : String.valueOf(val);
            //初始化 buf 对象 其实就是给 Pooled 设置属性 传入使用的 chunk  runOffset()+offset 代表对应memory 的偏移量
            buf.init(this, nioBuffer, handle, runOffset(memoryMapIdx) + offset,
                    //传入了一个 Allocate 的 缓存对象
                    reqCapacity, runLength(memoryMapIdx), arena.parent.threadCache());
        } else {
            initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx, reqCapacity);
        }
    }

    /**
     * 使用subpage 初始化buf
     * @param buf
     * @param nioBuffer
     * @param handle
     * @param reqCapacity
     */
    void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer, long handle, int reqCapacity) {
        initBufWithSubpage(buf, nioBuffer, handle, bitmapIdx(handle), reqCapacity);
    }

    /**
     * 通过subpage 创建 buf对象
     * @param buf
     * @param nioBuffer
     * @param handle
     * @param bitmapIdx
     * @param reqCapacity
     */
    private void initBufWithSubpage(PooledByteBuf<T> buf, ByteBuffer nioBuffer,
                                    long handle, int bitmapIdx, int reqCapacity) {
        assert bitmapIdx != 0;

        int memoryMapIdx = memoryMapIdx(handle);

        PoolSubpage<T> subpage = subpages[subpageIdx(memoryMapIdx)];
        assert subpage.doNotDestroy;
        assert reqCapacity <= subpage.elemSize;

        buf.init(
            this, nioBuffer, handle,
            runOffset(memoryMapIdx) + (bitmapIdx & 0x3FFFFFFF) * subpage.elemSize + offset,
                reqCapacity, subpage.elemSize, arena.parent.threadCache());
    }

    private byte value(int id) {
        return memoryMap[id];
    }

    private void setValue(int id, byte val) {
        memoryMap[id] = val;
    }

    /**
     * 根据 id 获取当前深度
     * @param id
     * @return
     */
    private byte depth(int id) {
        return depthMap[id];
    }

    private static int log2(int val) {
        // compute the (0-based, with lsb = 0) position of highest set bit i.e, log2
        return INTEGER_SIZE_MINUS_ONE - Integer.numberOfLeadingZeros(val);
    }

    private int runLength(int id) {
        // represents the size in #bytes supported by node 'id' in the tree
        return 1 << log2ChunkSize - depth(id);
    }

    private int runOffset(int id) {
        // represents the 0-based offset in #bytes from start of the byte-array chunk
        int shift = id ^ 1 << depth(id);
        return shift * runLength(id);
    }

    private int subpageIdx(int memoryMapIdx) {
        return memoryMapIdx ^ maxSubpageAllocs; // remove highest set bit, to get offset
    }

    private static int memoryMapIdx(long handle) {
        return (int) handle;
    }

    private static int bitmapIdx(long handle) {
        return (int) (handle >>> Integer.SIZE);
    }

    @Override
    public int chunkSize() {
        return chunkSize;
    }

    @Override
    public int freeBytes() {
        synchronized (arena) {
            return freeBytes;
        }
    }

    @Override
    public String toString() {
        final int freeBytes;
        synchronized (arena) {
            freeBytes = this.freeBytes;
        }

        return new StringBuilder()
                .append("Chunk(")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(": ")
                .append(usage(freeBytes))
                .append("%, ")
                .append(chunkSize - freeBytes)
                .append('/')
                .append(chunkSize)
                .append(')')
                .toString();
    }

    void destroy() {
        arena.destroyChunk(this);
    }
}
