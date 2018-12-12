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

/**
 * 比page 更小的 内存 分配单位  到这里已经意味着在 chunk 进行内存分配时是 小于一个page 的大小了 否则 会 直接在chunk完成分配
 * @param <T>
 */
final class PoolSubpage<T> implements PoolSubpageMetric {

    /**
     * 所属的 chunk 对象
     */
    final PoolChunk<T> chunk;
    /**
     * 对应到 chunk 中的 那个 内存块 下标
     */
    private final int memoryMapIdx;
    private final int runOffset;
    /**
     * page 的大小 应该默认就是8k
     */
    private final int pageSize;
    /**
     * 当page 为8 k 时 默认长度为8 每一位代表 subpage数组中某个内存是否被使用
     * 因为 subpage 最小单位为 16b对应到 512个 元素 /64 就是8个 long 对象
     */
    private final long[] bitmap;

    //subpage 以链表形式连接

    PoolSubpage<T> prev;
    PoolSubpage<T> next;

    /**
     * 是否已经销毁
     */
    boolean doNotDestroy;
    /**
     * 每个小内存块的大小
     */
    int elemSize;
    /**
     * 总共有多少element 块
     */
    private int maxNumElems;
    /**
     * bitMap 真正使用的长度
     */
    private int bitmapLength;
    /**
     * 下个可使用的内存块
     */
    private int nextAvail;
    /**
     * 可使用的 element 块
     */
    private int numAvail;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    /**
     * 用于创建 链首的 构造方法 创建 一般的 subpage 是不走这边
     * @param pageSize
     */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    /**
     * 创建一个 subPage 对象
     * @param head
     * @param chunk
     * @param memoryMapIdx
     * @param runOffset
     * @param pageSize 代表page 的大小
     * @param elemSize 代表每个 subpage 的大小
     */
    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize) {
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        this.pageSize = pageSize;
        //当page 大小默认为8k 时 bitmap 长度为 8bit
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64
        init(head, elemSize);
    }

    /**
     * 初始化
     * @param head
     * @param elemSize subpage的大小
     */
    void init(PoolSubpage<T> head, int elemSize) {
        //未销毁
        doNotDestroy = true;
        //设置subpage 的大小
        this.elemSize = elemSize;
        if (elemSize != 0) {
            //存在 多少个 小的内存块 (多少个 element)
            maxNumElems = numAvail = pageSize / elemSize;
            //一开始能使用的下个内存块就是从0开始
            nextAvail = 0;
            //真正使用的数组数量 因为bitmap 是将 element 看作是 最小的 16b 实际上还有其他规格
            bitmapLength = maxNumElems >>> 6;
            //代表 还有余数 进一  因为上面的除是去尾法
            if ((maxNumElems & 63) != 0) {
                bitmapLength ++;
            }

            //将每位都设置成0
            for (int i = 0; i < bitmapLength; i ++) {
                bitmap[i] = 0;
            }
        }
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     * 分配一个 合适的 下标 也就是分配了 该下标对应的内存
     */
    long allocate() {
        //一般不存在该情况
        if (elemSize == 0) {
            return toHandle(0);
        }

        //没有可用数量 或者是 已销毁
        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        //获取下个 还没有分配的对象
        final int bitmapIdx = getNextAvail();
        //获取 对于bitmap 的下标
        int q = bitmapIdx >>> 6;
        //  /64 得到 对于 bitmap[q] 的第几位
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) == 0;
        //将 对应位置的值 修改为1 代表已经被分配过
        bitmap[q] |= 1L << r;

        //如果没有能分配的 移除该节点  没有体现出pool 啊 这样不就利用GC 回收了吗
        if (-- numAvail == 0) {
            removeFromPool();
        }

        //计算handle 值 高32位代表 subpage中分配到了第几个 低32位代表 使用的是 chunk 中的第几个page
        return toHandle(bitmapIdx);
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     *         根据 bitmapIdx 释放指定位置的 subpage 内存
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        //一般不存在
        if (elemSize == 0) {
            return true;
        }
        //获取 对应到 bitmap 的第几个元素
        int q = bitmapIdx >>> 6;
        //    /64 的余数
        int r = bitmapIdx & 63;
        //断言 是已经被分配的
        assert (bitmap[q] >>> r & 1) != 0;
        //设置成0
        bitmap[q] ^= 1L << r;

        //将该位置设置成下个可分配地址  难怪要特地设置一个 nextAvail 为了避免内存碎片化
        setNextAvail(bitmapIdx);

        //如果一开始是0 又重新回到 pool 链中 都不在链中 是怎么找到这个元素的???
        if (numAvail ++ == 0) {
            addToPool(head);
            return true;
        }

        //可用的 小于最大数量直接返回true
        if (numAvail != maxNumElems) {
            return true;
        } else {
            // 代表 全都是可用
            // Subpage not in use (numAvail == maxNumElems)
            // 在该链表中只有一个元素不能移除
            if (prev == next) {
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            // 移除
            doNotDestroy = false;
            removeFromPool();
            return false;
        }
    }

    /**
     * 把head 节点插入到自身前面
     * @param head
     */
    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        //前一个节点指向 head
        prev = head;
        next = head.next;
        //将 head 的下个节点指向自身
        next.prev = this;
        head.next = this;
    }

    /**
     * 将自身从链表中移除
     */
    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    /**
     * 获取下个可使用的 内存块
     * @return
     */
    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        //第一次会是0
        if (nextAvail >= 0) {
            //设置为-1 代表下次需要重新查找
            this.nextAvail = -1;
            return nextAvail;
        }
        return findNextAvail();
    }

    /**
     * 重新查找 合适的下个内存块
     * @return
     */
    private int findNextAvail() {
        //获取bitmap 数组对象 这个对象 标志着哪个内存是被使用过的
        final long[] bitmap = this.bitmap;
        //代表 一共用到了几个元素
        final int bitmapLength = this.bitmapLength;
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            //1代表占用 0 代表 未使用 ~0 == 0 代表 64位 全是 1
            if (~bits != 0) {
                //这里是 定位到第 几个元素可以分配
                return findNextAvail0(i, bits);
            }
        }
        //都被使用了 就是返回-1
        return -1;
    }

    /**
     * 从给定的 bitmap 元素中 进一步确定第几位 是可以分配的
     * @param i 代表bitmap 的第几个元素
     * @param bits 该元素
     * @return
     */
    private int findNextAvail0(int i, long bits) {
        //代表一共有几个内存块
        final int maxNumElems = this.maxNumElems;
        //代表 该 元素 达到了 第几个 内存块 因为可能会超过maxNumElems
        final int baseVal = i << 6;

        //开始针对每一位进行探测 因为每次 bits>>>=1 都相当于是消去了 尾部的 位数
        for (int j = 0; j < 64; j ++) {
            //代表未分配 0 & 1 = 0
            if ((bits & 1) == 0) {
                //一般来说baseVal 前面被分配的部分会全部是1  当 到可以分配的 内存块所属的 bitmap元素时该64位全是0
                // j 就是代表被分配的位数
                int val = baseVal | j;
                if (val < maxNumElems) {
                    return val;
                } else {
                    //代表 已经超过了 maxNumElements
                    break;
                }
            }
            //否则消去末尾
            bits >>>= 1;
        }
        return -1;
    }

    /**
     * 根据对于bitmapIdx 的下标 推导出handle
     * @param bitmapIdx
     * @return
     */
    private long toHandle(int bitmapIdx) {
        //最前面的值是用来 解决冲突的  相当与是标识 这个hanle 是从subpage 获取来的 然后后面高32位代表在bitmap 中的下标
        //低32位代表 memoryMap的下标也就是使用 第几个 page
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }

    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        synchronized (chunk.arena) {
            if (!this.doNotDestroy) {
                doNotDestroy = false;
                // Not used for creating the String.
                maxNumElems = numAvail = elemSize = -1;
            } else {
                doNotDestroy = true;
                maxNumElems = this.maxNumElems;
                numAvail = this.numAvail;
                elemSize = this.elemSize;
            }
        }

        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    /**
     * 销毁chunk
     */
    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
