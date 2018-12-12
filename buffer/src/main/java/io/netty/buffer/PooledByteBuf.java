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

import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 池化的 bytebuf 对象
 * @param <T>
 */
abstract class PooledByteBuf<T> extends AbstractReferenceCountedByteBuf {

    /**
     * 通过 recycle 对象 实现 资源重复利用
     */
    private final Recycler.Handle<PooledByteBuf<T>> recyclerHandle;

    /**
     * 内存所属的 PoolChunk 对象
     */
    protected PoolChunk<T> chunk;

    /**
     * 内存分配的位置  针对 chunk 的 page数组 下标 (针对chunk 来说就是使用了 哪个page 进行分配)
     */
    protected long handle;
    /**
     * 内存类型 byte[] or bytebuffer  对应 HeapBytebuf 和 DirectBytebuf
     */
    protected T memory;
    /**
     * 代表memory 的偏移量
     */
    protected int offset;
    /**
     * 内存块目前大小
     */
    protected int length;
    /**
     * 最大大小
     */
    int maxLength;
    /**
     * chunk 相关的 属性
     */
    PoolThreadCache cache;
    /**
     * 临时的 nioBuf对象
     */
    ByteBuffer tmpNioBuf;
    /**
     * netty bytebuf分配器
     */
    private ByteBufAllocator allocator;

    @SuppressWarnings("unchecked")
    protected PooledByteBuf(Recycler.Handle<? extends PooledByteBuf<T>> recyclerHandle, int maxCapacity) {
        super(maxCapacity);
        this.recyclerHandle = (Handle<PooledByteBuf<T>>) recyclerHandle;
    }

    /**
     * 初始化
     * @param chunk
     * @param nioBuffer
     * @param handle
     * @param offset
     * @param length
     * @param maxLength
     * @param cache
     */
    void init(PoolChunk<T> chunk, ByteBuffer nioBuffer,
              long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
        init0(chunk, nioBuffer, handle, offset, length, maxLength, cache);
    }

    void initUnpooled(PoolChunk<T> chunk, int length) {
        init0(chunk, null, 0, chunk.offset, length, length, null);
    }

    /**
     * 初始化逻辑
     * @param chunk
     * @param nioBuffer
     * @param handle
     * @param offset
     * @param length
     * @param maxLength
     * @param cache
     */
    private void init0(PoolChunk<T> chunk, ByteBuffer nioBuffer,
                       long handle, int offset, int length, int maxLength, PoolThreadCache cache) {
        assert handle >= 0;
        assert chunk != null;

        this.chunk = chunk;
        memory = chunk.memory;
        tmpNioBuf = nioBuffer;
        allocator = chunk.arena.parent;
        this.cache = cache;
        this.handle = handle;
        this.offset = offset;
        this.length = length;
        this.maxLength = maxLength;
    }

    /**
     * Method must be called before reuse this {@link PooledByteBufAllocator}
     * 重置该对象
     */
    final void reuse(int maxCapacity) {
        //设置最大容量
        maxCapacity(maxCapacity);
        //重置引用计数为1
        setRefCnt(1);
        //重置下标
        setIndex0(0, 0);
        //丢弃标记数据
        discardMarks();
    }

    /**
     * 获取容量大小 就是长度
     * @return
     */
    @Override
    public final int capacity() {
        return length;
    }

    /**
     * 重置容量大小
     * @param newCapacity
     * @return
     */
    @Override
    public final ByteBuf capacity(int newCapacity) {
        checkNewCapacity(newCapacity);

        //这里缩容 扩容 都是根据 length 来的 怎么跟 capacity 无关

        // If the request capacity does not require reallocation, just update the length of the memory.
        //如果是 非池化
        if (chunk.unpooled) {
            if (newCapacity == length) {
                return this;
            }
        } else {
            //池化 进行扩容
            if (newCapacity > length) {
                //在 小于maxLength 之前  直接修改length
                if (newCapacity <= maxLength) {
                    length = newCapacity;
                    return this;
                }
                //要缩容
            } else if (newCapacity < length) {
                //先进行位运算 代表 大于 maxLength 的 一半
                if (newCapacity > maxLength >>> 1) {
                    //如果 maxLength 小于 512  这里是对应到 chunk 的 某个内存大小单位
                    if (maxLength <= 512) {
                        //代表 newCapacity至少要大于16
                        if (newCapacity > maxLength - 16) {
                            length = newCapacity;
                            setIndex(Math.min(readerIndex(), newCapacity), Math.min(writerIndex(), newCapacity));
                            return this;
                        }
                    } else { // > 512 (i.e. >= 1024)
                        length = newCapacity;
                        setIndex(Math.min(readerIndex(), newCapacity), Math.min(writerIndex(), newCapacity));
                        return this;
                    }
                }
                //不足 maxLength 的一半 就不进行缩容了
            } else {
                //大小一致 直接返回
                return this;
            }
        }

        // Reallocation required.
        // 非池化 就需要重新分配了
        chunk.arena.reallocate(this, newCapacity, true);
        return this;
    }

    @Override
    public final ByteBufAllocator alloc() {
        return allocator;
    }

    /**
     * 因为 TCP/IP 协议 规定了 在网络上 必须采用 大端模式
     * @return
     */
    @Override
    public final ByteOrder order() {
        return ByteOrder.BIG_ENDIAN;
    }

    /**
     * 无包装对象
     * @return
     */
    @Override
    public final ByteBuf unwrap() {
        return null;
    }

    /**
     * 生成副本的 同时 增加引用计数
     * @return
     */
    @Override
    public final ByteBuf retainedDuplicate() {
        return PooledDuplicatedByteBuf.newInstance(this, this, readerIndex(), writerIndex());
    }

    /**
     * 生成分片对象的同时 增加引用计数
     * @return
     */
    @Override
    public final ByteBuf retainedSlice() {
        final int index = readerIndex();
        return retainedSlice(index, writerIndex() - index);
    }

    @Override
    public final ByteBuf retainedSlice(int index, int length) {
        return PooledSlicedByteBuf.newInstance(this, this, index, length);
    }

    /**
     * 获取临时的 bytebuf 对象
     * @return
     */
    protected final ByteBuffer internalNioBuffer() {
        ByteBuffer tmpNioBuf = this.tmpNioBuf;
        //如果不存在 临时 nioBuf
        if (tmpNioBuf == null) {
            //创建 临时对象 由子类实现 传入 生成的bytebuf 类型 byte[] or DirectBytebuffer
            this.tmpNioBuf = tmpNioBuf = newInternalNioBuffer(memory);
        }
        return tmpNioBuf;
    }

    protected abstract ByteBuffer newInternalNioBuffer(T memory);

    /**
     * 回收分配的对象
     */
    @Override
    protected final void deallocate() {
        if (handle >= 0) {
            final long handle = this.handle;
            //重置属性
            this.handle = -1;
            memory = null;
            //归还内存
            chunk.arena.free(chunk, tmpNioBuf, handle, maxLength, cache);
            tmpNioBuf = null;
            chunk = null;
            //回收对象
            recycle();
        }
    }

    /**
     * 使用recycle 对象 回收 该对象
     */
    private void recycle() {
        recyclerHandle.recycle(this);
    }

    /**
     * 如果是 堆外内存 不能直接定位到 地址 需要 加上 offset
     * @param index
     * @return
     */
    protected final int idx(int index) {
        return offset + index;
    }
}
