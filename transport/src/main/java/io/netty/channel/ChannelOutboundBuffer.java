/*
 * Copyright 2013 The Netty Project
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
package io.netty.channel;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.PromiseNotificationUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import static java.lang.Math.min;

/**
 * (Transport implementors only) an internal data structure used by {@link AbstractChannel} to store its pending
 * outbound write requests.
 * <p>
 * All methods must be called by a transport implementation from an I/O thread, except the following ones:
 * <ul>
 * <li>{@link #size()} and {@link #isEmpty()}</li>
 * <li>{@link #isWritable()}</li>
 * <li>{@link #getUserDefinedWritability(int)} and {@link #setUserDefinedWritability(int, boolean)}</li>
 * </ul>
 * </p>
 * 当调用channel.write时,数据不会立即写入到底层网络io缓冲区,而是尽可能批量写入,这些数据会先留存在容器中(ByteBuf),
 * 而ChannelOutboundBuffer对他们进行统一管理,当执行flush时,会取出ByteBuf并转换成ByteBuffer写入到底层缓冲区
 */
public final class ChannelOutboundBuffer {
    // Assuming a 64-bit JVM:
    //  - 16 bytes object header
    //  - 8 reference fields
    //  - 2 long fields
    //  - 2 int fields
    //  - 1 boolean field
    //  - padding
    static final int CHANNEL_OUTBOUND_BUFFER_ENTRY_OVERHEAD =
            SystemPropertyUtil.getInt("io.netty.transport.outboundBufferEntrySizeOverhead", 96);

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ChannelOutboundBuffer.class);

    private static final FastThreadLocal<ByteBuffer[]> NIO_BUFFERS = new FastThreadLocal<ByteBuffer[]>() {
        @Override
        protected ByteBuffer[] initialValue() throws Exception {
            return new ByteBuffer[1024];
        }
    };

    /**
     * 该内存队列对应的 channel 对象
     */
    private final Channel channel;

    /**
     * The Entry that is the first in the linked-list structure that was flushed
     * 当前被标记成flush的首个entry
     */
    private Entry flushedEntry;
    /**
     * The Entry which is the first unflushed in the linked-list structure
     * 第一个未flush 的 entry对象
     */
    private Entry unflushedEntry;
    /**
     * The Entry which represents the tail of the buffer
     * 对应最近一个插入的buffer
     */
    private Entry tailEntry;
    /**
     * The number of flushed entries that are not written yet
     * 已刷入的entry 数量  (以entry 为单位)  这个应该是单次的 比如在 flush 结束后 会置零之类的
     */
    private int flushed;

    /**
     * 记录当前有多少个 bytebuffer 对象
     */
    private int nioBufferCount;
    /**
     * 记录每个buffer 的大小
     */
    private long nioBufferSize;

    private boolean inFail;

    /**
     * 原子更新 totalPendingSize
     */
    private static final AtomicLongFieldUpdater<ChannelOutboundBuffer> TOTAL_PENDING_SIZE_UPDATER =
            AtomicLongFieldUpdater.newUpdater(ChannelOutboundBuffer.class, "totalPendingSize");

    @SuppressWarnings("UnusedDeclaration")
    /**
     * 所有的待刷盘的 entry 大小  在 addMessage 时 会增加 在 flush 时 会减少
     */
    private volatile long totalPendingSize;

    private static final AtomicIntegerFieldUpdater<ChannelOutboundBuffer> UNWRITABLE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(ChannelOutboundBuffer.class, "unwritable");

    @SuppressWarnings("UnusedDeclaration")
    /**
     * 是否不可写入的状态 感觉是 0 or 1 1 代表不可写入
     */
    private volatile int unwritable;

    /**
     * 修改水位状态的 task
     */
    private volatile Runnable fireChannelWritabilityChangedTask;

    /**
     * 初始化对象时 将channel 绑定到 outboundbuffer 上   是在channel 初始化时 创建了unsafe 的同时 创建的
     * @param channel
     */
    ChannelOutboundBuffer(AbstractChannel channel) {
        this.channel = channel;
    }

    /**
     * Add given message to this {@link ChannelOutboundBuffer}. The given {@link ChannelPromise} will be notified once
     * the message was written.
     *
     */
    public void addMessage(Object msg, int size, ChannelPromise promise) {
        //将数据体构建成一个 entry 对象
        Entry entry = Entry.newInstance(msg, size, total(msg), promise);
        // 代表所有数据都已经刷盘完成
        if (tailEntry == null) {
            flushedEntry = null;
        } else {
            //链表操作 配合 tailEntry = entry 就是为 entry 增加新元素 并将 尾部指向当前entry
            Entry tail = tailEntry;
            tail.next = entry;
        }
        tailEntry = entry;

        // 如果此时没有未刷盘的entry 将该entry标记成未刷盘
        if (unflushedEntry == null) {
            unflushedEntry = entry;
        }

        // increment pending bytes after adding message to the unflushed arrays.
        // See https://github.com/netty/netty/issues/1619
        // 增加了 待flush 的 数据
        incrementPendingOutboundBytes(entry.pendingSize, false);
    }

    /**
     * Add a flush to this {@link ChannelOutboundBuffer}. This means all previous added messages are marked as flushed
     * and so you will be able to handle them.
     * 执行flush0的前置工作
     * 将之前write未flush的所有entry都标记成 flushed 之后flush0就会对这些entry进行刷盘
     */
    public void addFlush() {
        // There is no need to process all entries if there was already a flush before and no new messages
        // where added in the meantime.
        //
        // See https://github.com/netty/netty/issues/2577
        // 获取 未刷盘的 entry 不存在未刷盘的 entry 直接结束
        Entry entry = unflushedEntry;
        if (entry != null) {
            if (flushedEntry == null) {
                // there is no flushedEntry yet, so start with the entry
                // 定位到未刷盘链表
                flushedEntry = entry;
            }
            do {
                flushed ++;
                if (!entry.promise.setUncancellable()) {
                    // Was cancelled so make sure we free up memory and notify about the freed bytes
                    // 取消对该entry 的刷盘动作
                    int pending = entry.cancel();
                    //减少待 flush 数量 并 根据情况 恢复可写状态
                    decrementPendingOutboundBytes(pending, false, true);
                }
                entry = entry.next;
            } while (entry != null);

            // All flushed so reset unflushedEntry
            // 代表所有entry 就绪flush  所以不存在 未flush 的entry
            unflushedEntry = null;
        }
    }

    /**
     * Increment the pending bytes which will be written at some point.
     * This method is thread-safe!
     */
    void incrementPendingOutboundBytes(long size) {
        incrementPendingOutboundBytes(size, true);
    }

    /**
     * 增加待 flush 的数据
     * @param size
     * @param invokeLater
     */
    private void incrementPendingOutboundBytes(long size, boolean invokeLater) {
        if (size == 0) {
            return;
        }

        // 更新待刷盘的数据量
        long newWriteBufferSize = TOTAL_PENDING_SIZE_UPDATER.addAndGet(this, size);
        // 此时囤积的数据过多
        if (newWriteBufferSize > channel.config().getWriteBufferHighWaterMark()) {
            setUnwritable(invokeLater);
        }
    }

    /**
     * Decrement the pending bytes which will be written at some point.
     * This method is thread-safe!
     */
    void decrementPendingOutboundBytes(long size) {
        decrementPendingOutboundBytes(size, true, true);
    }

    /**
     * 代表 刷盘时 修改 当前积累的 待刷盘数量 并且 如果低于低水位 又会重新可写
     * @param size
     * @param invokeLater
     * @param notifyWritability
     */
    private void decrementPendingOutboundBytes(long size, boolean invokeLater, boolean notifyWritability) {
        if (size == 0) {
            return;
        }

        //修改 待刷盘数据
        long newWriteBufferSize = TOTAL_PENDING_SIZE_UPDATER.addAndGet(this, -size);
        //低于低水位时 变成可写
        if (notifyWritability && newWriteBufferSize < channel.config().getWriteBufferLowWaterMark()) {
            setWritable(invokeLater);
        }
    }

    /**
     * 计算数据大小 其实就是把 msg 恢复成 buf 类型然后看可读大小
     * @param msg
     * @return
     */
    private static long total(Object msg) {
        if (msg instanceof ByteBuf) {
            return ((ByteBuf) msg).readableBytes();
        }
        if (msg instanceof FileRegion) {
            return ((FileRegion) msg).count();
        }
        if (msg instanceof ByteBufHolder) {
            return ((ByteBufHolder) msg).content().readableBytes();
        }
        return -1;
    }

    /**
     * Return the current message to write or {@code null} if nothing was flushed before and so is ready to be written.
     * 返回当前第一个准备flush的entry的数据体
     */
    public Object current() {
        Entry entry = flushedEntry;
        if (entry == null) {
            return null;
        }

        return entry.msg;
    }

    /**
     * Notify the {@link ChannelPromise} of the current message about writing progress.
     * 计算进度
     */
    public void progress(long amount) {
        //获取当前正在 flush 的 entry 对象
        Entry e = flushedEntry;
        assert e != null;
        //获取刷盘相关的 进度 promise 对象
        ChannelPromise p = e.promise;
        if (p instanceof ChannelProgressivePromise) {
            //增加进度
            long progress = e.progress + amount;
            e.progress = progress;
            //设置进度 这里可能会触发到 processPromise 关联的监听器对象
            ((ChannelProgressivePromise) p).tryProgress(progress, e.total);
        }
    }

    /**
     * Will remove the current message, mark its {@link ChannelPromise} as success and return {@code true}. If no
     * flushed message exists at the time this method is called it will return {@code false} to signal that no more
     * messages are ready to be handled.
     *
     * 移除某个entry对象,应该是在flush操作完成后 不需要的entry就可以移除掉了
     */
    public boolean remove() {
        //获取当前 正在刷盘的 entry 对象
        Entry e = flushedEntry;
        //如果不存在 (未添加消息之前 不存在 flushedEntry 对象)
        if (e == null) {
            //清空全部 buffer 对象
            clearNioBuffers();
            // 没有任何entry被移除
            return false;
        }
        Object msg = e.msg;

        ChannelPromise promise = e.promise;
        int size = e.pendingSize;

        //移除单个 entry对象
        removeEntry(e);

        //如果未关闭就进行关闭
        if (!e.cancelled) {
            // only release message, notify and decrement if it was not canceled before.
            ReferenceCountUtil.safeRelease(msg);
            //直接提示 完成 flush 其实没有
            safeSuccess(promise);
            //这是正常情况下的 移除 上面addFlush 是异常情况所以直接关闭了
            decrementPendingOutboundBytes(size, false, true);
        }

        // recycle the entry
        e.recycle();

        return true;
    }

    /**
     * Will remove the current message, mark its {@link ChannelPromise} as failure using the given {@link Throwable}
     * and return {@code true}. If no   flushed message exists at the time this method is called it will return
     * {@code false} to signal that no more messages are ready to be handled.
     *
     * 移除并设置异常结果
     */
    public boolean remove(Throwable cause) {
        return remove0(cause, true);
    }

    /**
     * 移除并设置异常结果 应该跟上面的方法一样
     * @param cause
     * @param notifyWritability
     * @return
     */
    private boolean remove0(Throwable cause, boolean notifyWritability) {
        Entry e = flushedEntry;
        if (e == null) {
            clearNioBuffers();
            return false;
        }
        Object msg = e.msg;

        ChannelPromise promise = e.promise;
        int size = e.pendingSize;

        removeEntry(e);

        if (!e.cancelled) {
            // only release message, fail and decrement if it was not canceled before.
            ReferenceCountUtil.safeRelease(msg);

            safeFail(promise, cause);
            decrementPendingOutboundBytes(size, false, notifyWritability);
        }

        // recycle the entry
        e.recycle();

        return true;
    }

    /**
     * 移除 单个 entry
     * @param e
     */
    private void removeEntry(Entry e) {
        //这里移除的是正在刷盘(或准备刷盘)中的 如果只有这一个 entry 是 正在刷盘 那么一旦移除 就没有 准备entry对象了
        if (-- flushed == 0) {
            // processed everything
            flushedEntry = null;
            if (e == tailEntry) {
                tailEntry = null;
                unflushedEntry = null;
            }
        } else {
            //将下一个entry 标记成 准备刷盘
            flushedEntry = e.next;
        }
    }

    /**
     * Removes the fully written entries and update the reader index of the partially written entry.
     * This operation assumes all messages in this buffer is {@link ByteBuf}.
     *
     * 移除指定大小的byte
     */
    public void removeBytes(long writtenBytes) {
        for (;;) {
            //获取当前 准备 flush 的 数据  msg 其实就是bytebuf
            Object msg = current();
            if (!(msg instanceof ByteBuf)) {
                assert writtenBytes == 0;
                break;
            }

            //恢复成 bytebuf 对象
            final ByteBuf buf = (ByteBuf) msg;
            //获取读指针 和 写指针计算 待读取数据
            final int readerIndex = buf.readerIndex();
            final int readableBytes = buf.writerIndex() - readerIndex;

            //小于要移除的 大小 这样进入下次循环会获取到entry 链的下个 对象
            if (readableBytes <= writtenBytes) {
                if (writtenBytes != 0) {
                    //提示当前进度  传入的 readableBytes 是 增加的进度
                    progress(readableBytes);
                    //代表 该 buf 剩余的数据都被读完了
                    writtenBytes -= readableBytes;
                }
                //移除当前的 flushed 对象 对应 上面的 current() 获取 flushed 对象
                remove();
            } else { // readableBytes > writtenBytes
                //当可移除的大小 小于 当前entry 的剩余数据
                if (writtenBytes != 0) {
                    //直接移动读指针
                    buf.readerIndex(readerIndex + (int) writtenBytes);
                    //提示进度发生变化
                    progress(writtenBytes);
                }
                break;
            }
        }
        // 在写入到底层网络io前 会将byteBuf的数据转移到 byteBuffer中, byteBuffer就存储在NioBuffers数组中. 现在对应的数据已经刷盘完成就可以移除了
        clearNioBuffers();
    }

    /**
     * Clear all ByteBuffer from the array so these can be GC'ed.
     * See https://github.com/netty/netty/issues/3837
     */
    private void clearNioBuffers() {
        int count = nioBufferCount;
        if (count > 0) {
            nioBufferCount = 0;
            Arrays.fill(NIO_BUFFERS.get(), 0, count, null);
        }
    }

    /**
     * Returns an array of direct NIO buffers if the currently pending messages are made of {@link ByteBuf} only.
     * {@link #nioBufferCount()} and {@link #nioBufferSize()} will return the number of NIO buffers in the returned
     * array and the total number of readable bytes of the NIO buffers respectively.
     * <p>
     * Note that the returned array is reused and thus should not escape
     * {@link AbstractChannel#doWrite(ChannelOutboundBuffer)}.
     * Refer to {@link NioSocketChannel#doWrite(ChannelOutboundBuffer)} for an example.
     * </p>
     */
    public ByteBuffer[] nioBuffers() {
        return nioBuffers(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    /**
     * Returns an array of direct NIO buffers if the currently pending messages are made of {@link ByteBuf} only.
     * {@link #nioBufferCount()} and {@link #nioBufferSize()} will return the number of NIO buffers in the returned
     * array and the total number of readable bytes of the NIO buffers respectively.
     * <p>
     * Note that the returned array is reused and thus should not escape
     * {@link AbstractChannel#doWrite(ChannelOutboundBuffer)}.
     * Refer to {@link NioSocketChannel#doWrite(ChannelOutboundBuffer)} for an example.
     *
     * </p>
     * @param maxCount The maximum amount of buffers that will be added to the return value.
     *
     * @param maxBytes A hint toward the maximum number of bytes to include as part of the return value. Note that this
     *                 value maybe exceeded because we make a best effort to include at least 1 {@link ByteBuffer}
     *                 in the return value to ensure write progress is made.
     *                 根据限定的byteBuf数量和数据量大小 将数据转移到 ByteBuffer中
     */
    public ByteBuffer[] nioBuffers(int maxCount, long maxBytes) {
        assert maxCount > 0;
        assert maxBytes > 0;
        long nioBufferSize = 0;
        int nioBufferCount = 0;
        final InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
        // 该数组可以被复用
        ByteBuffer[] nioBuffers = NIO_BUFFERS.get(threadLocalMap);

        // 代表从哪个entry开始转移数据
        Entry entry = flushedEntry;
        while (isFlushedEntry(entry) && entry.msg instanceof ByteBuf) {
            //该entry 还没有关闭的情况
            if (!entry.cancelled) {
                ByteBuf buf = (ByteBuf) entry.msg;
                final int readerIndex = buf.readerIndex();
                final int readableBytes = buf.writerIndex() - readerIndex;

                //还有数据可以读取
                if (readableBytes > 0) {
                    // 等价于 maxBytes < nioBufferSize + readableBytes 代表本次读取数据后会超标 放弃读取
                    // 如果nioBufferCount == 0 代表本次还没有读取到任何数据 那么至少会读取一次
                    if (maxBytes - readableBytes < nioBufferSize && nioBufferCount != 0) {
                        // If the nioBufferSize + readableBytes will overflow maxBytes, and there is at least one entry
                        // we stop populate the ByteBuffer array. This is done for 2 reasons:
                        // 1. bsd/osx don't allow to write more bytes then Integer.MAX_VALUE with one writev(...) call
                        // and so will return 'EINVAL', which will raise an IOException. On Linux it may work depending
                        // on the architecture and kernel but to be safe we also enforce the limit here.
                        // 2. There is no sense in putting more data in the array than is likely to be accepted by the
                        // OS.
                        //
                        // See also:
                        // - https://www.freebsd.org/cgi/man.cgi?query=write&sektion=2
                        // - http://linux.die.net/man/2/writev
                        break;
                    }
                    //记录当前读取的总大小
                    nioBufferSize += readableBytes;
                    // 每个entry内部可能还有多个buffer
                    int count = entry.count;
                    //代表还没有开始创建
                    if (count == -1) {
                        //noinspection ConstantValueVariableUse
                        //buf可能是 CompositeByteBuf 所以这里可能会返回多个
                        entry.count = count = buf.nioBufferCount();
                    }
                    int neededSpace = min(maxCount, nioBufferCount + count);
                    //如果超过数组大小进行扩容
                    if (neededSpace > nioBuffers.length) {
                        //扩容
                        nioBuffers = expandNioBufferArray(nioBuffers, neededSpace, nioBufferCount);
                        //重新设置到本地线程变量中
                        NIO_BUFFERS.set(threadLocalMap, nioBuffers);
                    }
                    if (count == 1) {
                        //默认是不设置的
                        ByteBuffer nioBuf = entry.buf;
                        if (nioBuf == null) {
                            // cache ByteBuffer as it may need to create a new ByteBuffer instance if its a
                            // derived buffer
                            entry.buf = nioBuf = buf.internalNioBuffer(readerIndex, readableBytes);
                        }
                        //将 派生出来的nioBuffer 保存到数组中
                        nioBuffers[nioBufferCount++] = nioBuf;
                    } else {
                        // 如果该msg 转换的 buf 需要多个 nioBuffer 对象支撑 就多使用几个 数组中的元素
                        // The code exists in an extra method to ensure the method is not too big to inline as this
                        // branch is not very likely to get hit very frequently.
                        nioBufferCount = nioBuffers(entry, buf, nioBuffers, nioBufferCount, maxCount);
                    }
                    if (nioBufferCount == maxCount) {
                        break;
                    }
                }
            }
            entry = entry.next;
        }
        this.nioBufferCount = nioBufferCount;
        this.nioBufferSize = nioBufferSize;

        return nioBuffers;
    }

    /**
     *
     * @param entry
     * @param buf msg 转换成的 buf对象
     * @param nioBuffers 当前nioBuffer 数组
     * @param nioBufferCount 当前使用的niobuffer数量
     * @param maxCount 最多允许使用的 nioBuffer 数量
     * @return
     */
    private static int nioBuffers(Entry entry, ByteBuf buf, ByteBuffer[] nioBuffers, int nioBufferCount, int maxCount) {
        ByteBuffer[] nioBufs = entry.bufs;
        //一般都是null
        if (nioBufs == null) {
            // cached ByteBuffers as they may be expensive to create in terms
            // of Object allocation
            entry.bufs = nioBufs = buf.nioBuffers();
        }
        for (int i = 0; i < nioBufs.length && nioBufferCount < maxCount; ++i) {
            ByteBuffer nioBuf = nioBufs[i];
            if (nioBuf == null) {
                break;
            } else if (!nioBuf.hasRemaining()) {
                continue;
            }
            //将 生成的 nioBuffer 数组的元素设置到 nioBuffers 中
            nioBuffers[nioBufferCount++] = nioBuf;
        }
        return nioBufferCount;
    }

    /**
     * 为 bytebuffer 数组扩容
     * @param array
     * @param neededSpace
     * @param size
     * @return
     */
    private static ByteBuffer[] expandNioBufferArray(ByteBuffer[] array, int neededSpace, int size) {
        int newCapacity = array.length;
        do {
            // double capacity until it is big enough
            // See https://github.com/netty/netty/issues/1890
            newCapacity <<= 1;

            if (newCapacity < 0) {
                throw new IllegalStateException();
            }

        } while (neededSpace > newCapacity);

        ByteBuffer[] newArray = new ByteBuffer[newCapacity];
        //将旧数据拷贝到 新数组中
        System.arraycopy(array, 0, newArray, 0, size);

        return newArray;
    }

    /**
     * Returns the number of {@link ByteBuffer} that can be written out of the {@link ByteBuffer} array that was
     * obtained via {@link #nioBuffers()}. This method <strong>MUST</strong> be called after {@link #nioBuffers()}
     * was called.
     *
     * 返回当前使用了 多少 nioBuffer 元素
     */
    public int nioBufferCount() {
        return nioBufferCount;
    }

    /**
     * Returns the number of bytes that can be written out of the {@link ByteBuffer} array that was
     * obtained via {@link #nioBuffers()}. This method <strong>MUST</strong> be called after {@link #nioBuffers()}
     * was called.
     *
     * nioBuffer 的数据总大小
     */
    public long nioBufferSize() {
        return nioBufferSize;
    }

    /**
     * Returns {@code true} if and only if {@linkplain #totalPendingWriteBytes() the total number of pending bytes} did
     * not exceed the write watermark of the {@link Channel} and
     * no {@linkplain #setUserDefinedWritability(int, boolean) user-defined writability flag} has been set to
     * {@code false}.
     *
     * 是否可写入
     */
    public boolean isWritable() {
        return unwritable == 0;
    }

    /**
     * Returns {@code true} if and only if the user-defined writability flag at the specified index is set to
     * {@code true}.
     *
     * 使用用户自定义的 标识
     */
    public boolean getUserDefinedWritability(int index) {
        return (unwritable & writabilityMask(index)) == 0;
    }

    /**
     * Sets a user-defined writability flag at the specified index.
     */
    public void setUserDefinedWritability(int index, boolean writable) {
        if (writable) {
            setUserDefinedWritability(index);
        } else {
            clearUserDefinedWritability(index);
        }
    }

    private void setUserDefinedWritability(int index) {
        final int mask = ~writabilityMask(index);
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue & mask;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                if (oldValue != 0 && newValue == 0) {
                    fireChannelWritabilityChanged(true);
                }
                break;
            }
        }
    }

    private void clearUserDefinedWritability(int index) {
        final int mask = writabilityMask(index);
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue | mask;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                if (oldValue == 0 && newValue != 0) {
                    fireChannelWritabilityChanged(true);
                }
                break;
            }
        }
    }

    private static int writabilityMask(int index) {
        if (index < 1 || index > 31) {
            throw new IllegalArgumentException("index: " + index + " (expected: 1~31)");
        }
        return 1 << index;
    }

    /**
     * 修改 可写状态 并触发事件
     * @param invokeLater
     */
    private void setWritable(boolean invokeLater) {
        for (;;) {
            final int oldValue = unwritable;
            final int newValue = oldValue & ~1;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                if (oldValue != 0 && newValue == 0) {
                    fireChannelWritabilityChanged(invokeLater);
                }
                break;
            }
        }
    }

    /**
     * 设置成不可再写入数据
     * @param invokeLater
     */
    private void setUnwritable(boolean invokeLater) {
        for (;;) {
            //获取 之前 不可写入的数据大小  这个感觉是 一个标识  0 or 1 而不是 一个数据大小
            final int oldValue = unwritable;
            //将二进制 尾部变成1
            final int newValue = oldValue | 1;
            if (UNWRITABLE_UPDATER.compareAndSet(this, oldValue, newValue)) {
                //代表水位状态发生了改变
                if (oldValue == 0 && newValue != 0) {
                    fireChannelWritabilityChanged(invokeLater);
                }
                break;
            }
        }
    }

    /**
     * 当水位状态发生了变化  传递事件
     * @param invokeLater
     */
    private void fireChannelWritabilityChanged(boolean invokeLater) {
        final ChannelPipeline pipeline = channel.pipeline();
        //如果是延迟调用形式 就是添加到普通任务队列 的尾部
        if (invokeLater) {
            Runnable task = fireChannelWritabilityChangedTask;
            if (task == null) {
                fireChannelWritabilityChangedTask = task = new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireChannelWritabilityChanged();
                    }
                };
            }
            channel.eventLoop().execute(task);
        } else {
            //直接执行
            pipeline.fireChannelWritabilityChanged();
        }
    }

    /**
     * Returns the number of flushed messages in this {@link ChannelOutboundBuffer}.
     *
     * 有多少需要 flush 的entry
     */
    public int size() {
        return flushed;
    }

    /**
     * Returns {@code true} if there are flushed messages in this {@link ChannelOutboundBuffer} or {@code false}
     * otherwise.
     *
     * 是否当前 没有需要 flush 的 entry
     */
    public boolean isEmpty() {
        return flushed == 0;
    }

    /**
     * 当channel 关闭时触发
     * @param cause
     * @param notify
     */
    void failFlushed(Throwable cause, boolean notify) {
        // Make sure that this method does not reenter.  A listener added to the current promise can be notified by the
        // current thread in the tryFailure() call of the loop below, and the listener can trigger another fail() call
        // indirectly (usually by closing the channel.)
        //
        // See https://github.com/netty/netty/issues/1501
        if (inFail) {
            return;
        }

        try {
            //代表正在处理失败状态
            inFail = true;
            for (;;) {
                //为每个 flushed entry(待flush entry) 设置promise 并将 nioBuffer 清空
                if (!remove0(cause, notify)) {
                    break;
                }
            }
        } finally {
            inFail = false;
        }
    }

    /**
     * 在出现异常时 需要关闭output 就会调用这里
     * @param cause
     * @param allowChannelOpen
     */
    void close(final Throwable cause, final boolean allowChannelOpen) {
        //如果正在处理 fallFlushed 就 延迟执行
        if (inFail) {
            channel.eventLoop().execute(new Runnable() {
                @Override
                public void run() {
                    close(cause, allowChannelOpen);
                }
            });
            return;
        }

        inFail = true;

        if (!allowChannelOpen && channel.isOpen()) {
            throw new IllegalStateException("close() must be invoked after the channel is closed.");
        }

        if (!isEmpty()) {
            throw new IllegalStateException("close() must be invoked after all flushed writes are handled.");
        }

        // Release all unflushed messages.
        try {
            Entry e = unflushedEntry;
            while (e != null) {
                // Just decrease; do not trigger any events via decrementPendingOutboundBytes()
                int size = e.pendingSize;
                TOTAL_PENDING_SIZE_UPDATER.addAndGet(this, -size);

                //设置失败结果
                if (!e.cancelled) {
                    ReferenceCountUtil.safeRelease(e.msg);
                    safeFail(e.promise, cause);
                }
                // 这里是释放 unflushedEntry -> tailEntry
                e = e.recycleAndGetNext();
            }
        } finally {
            inFail = false;
        }
        //确保 nioBuffer 已经被清除
        clearNioBuffers();
    }

    /**
     * 最后 finalFulsh 结束后触发
     * @param cause
     */
    void close(ClosedChannelException cause) {
        close(cause, false);
    }

    private static void safeSuccess(ChannelPromise promise) {
        // Only log if the given promise is not of type VoidChannelPromise as trySuccess(...) is expected to return
        // false.
        PromiseNotificationUtil.trySuccess(promise, null, promise instanceof VoidChannelPromise ? null : logger);
    }

    private static void safeFail(ChannelPromise promise, Throwable cause) {
        // Only log if the given promise is not of type VoidChannelPromise as tryFailure(...) is expected to return
        // false.
        PromiseNotificationUtil.tryFailure(promise, cause, promise instanceof VoidChannelPromise ? null : logger);
    }

    @Deprecated
    public void recycle() {
        // NOOP
    }

    public long totalPendingWriteBytes() {
        return totalPendingSize;
    }

    /**
     * Get how many bytes can be written until {@link #isWritable()} returns {@code false}.
     * This quantity will always be non-negative. If {@link #isWritable()} is {@code false} then 0.
     *
     * 计算还要加入多少 就不能写入了
     */
    public long bytesBeforeUnwritable() {
        long bytes = channel.config().getWriteBufferHighWaterMark() - totalPendingSize;
        // If bytes is negative we know we are not writable, but if bytes is non-negative we have to check writability.
        // Note that totalPendingSize and isWritable() use different volatile variables that are not synchronized
        // together. totalPendingSize will be updated before isWritable().
        if (bytes > 0) {
            return isWritable() ? bytes : 0;
        }
        return 0;
    }

    /**
     * Get how many bytes must be drained from the underlying buffer until {@link #isWritable()} returns {@code true}.
     * This quantity will always be non-negative. If {@link #isWritable()} is {@code true} then 0.
     */
    public long bytesBeforeWritable() {
        long bytes = totalPendingSize - channel.config().getWriteBufferLowWaterMark();
        // If bytes is negative we know we are writable, but if bytes is non-negative we have to check writability.
        // Note that totalPendingSize and isWritable() use different volatile variables that are not synchronized
        // together. totalPendingSize will be updated before isWritable().
        if (bytes > 0) {
            return isWritable() ? 0 : bytes;
        }
        return 0;
    }

    /**
     * Call {@link MessageProcessor#processMessage(Object)} for each flushed message
     * in this {@link ChannelOutboundBuffer} until {@link MessageProcessor#processMessage(Object)}
     * returns {@code false} or there are no more flushed messages to process.
     *
     * 使用特殊的 MessageProcessor 来进行flush
     */
    public void forEachFlushedMessage(MessageProcessor processor) throws Exception {
        if (processor == null) {
            throw new NullPointerException("processor");
        }

        Entry entry = flushedEntry;
        if (entry == null) {
            return;
        }

        do {
            if (!entry.cancelled) {
                if (!processor.processMessage(entry.msg)) {
                    return;
                }
            }
            entry = entry.next;
        } while (isFlushedEntry(entry));
    }

    private boolean isFlushedEntry(Entry e) {
        return e != null && e != unflushedEntry;
    }

    /**
     * 消息处理对象接口
     */
    public interface MessageProcessor {
        /**
         * Will be called for each flushed message until it either there are no more flushed messages or this
         * method returns {@code false}.
         */
        boolean processMessage(Object msg) throws Exception;
    }

    /**
     * 每次想要写出的byteBuf 会被包装成一个entry对象
     */
    static final class Entry {
        //该对象用于回收Entry 对象
        private static final Recycler<Entry> RECYCLER = new Recycler<Entry>() {
            @Override
            protected Entry newObject(Handle<Entry> handle) {
                return new Entry(handle);
            }
        };

        //Recycle对象的 内部类 用于执行 回收和 获取
        private final Handle<Entry> handle;
        //看来这是一个链式的 数据结构
        Entry next;
        //消息实体
        Object msg;
        //该msg转换 为 nioBuffer 后对应的 nioBuffer对象  可能是一个 或 多个
        ByteBuffer[] bufs;
        //该msg转换 为 nioBuffer 后对应的 nioBuffer对象
        ByteBuffer buf;
        //写入后的 promise 用于提示该操作是否完成  该对象一般是 ProcessChannelPromise 可以表示 flush 进度等
        ChannelPromise promise;
        //已写入大小
        long progress;
        //消息总大小
        long total;
        //预估每个entry 占用的内存大小 为msg 大小 加上Entry 本身大小
        int pendingSize;
        //msg 对应能转换为多少个 bytebuffer 对象
        int count = -1;
        //是否已经被关闭
        boolean cancelled;

        private Entry(Handle<Entry> handle) {
            this.handle = handle;
        }

        /**
         * 创建 Entry 实例
         * @param msg
         * @param size
         * @param total
         * @param promise
         * @return
         */
        static Entry newInstance(Object msg, int size, long total, ChannelPromise promise) {
            //先尝试从Recycle 中获取对象
            Entry entry = RECYCLER.get();
            entry.msg = msg;
            entry.pendingSize = size + CHANNEL_OUTBOUND_BUFFER_ENTRY_OVERHEAD;
            entry.total = total;
            entry.promise = promise;
            return entry;
        }

        /**
         * 关闭该entry
         * @return
         */
        int cancel() {
            //如果还没cancel 才能进行处理
            if (!cancelled) {
                //修改为 已关闭
                cancelled = true;
                //总大小
                int pSize = pendingSize;

                // release message and replace with an empty buffer
                // 释放msg 所占内存 因为使用的是 堆外内存 需要自身做 内存关闭 这里就使用 引用计数的方式处理
                ReferenceCountUtil.safeRelease(msg);
                // 将msg 重置成空bytebuf
                msg = Unpooled.EMPTY_BUFFER;

                //重置属性
                pendingSize = 0;
                total = 0;
                progress = 0;
                bufs = null;
                buf = null;
                return pSize;
            }
            return 0;
        }

        /**
         * 将该对象进行回收 也就是 清空属性 并存放到 recycle.handle中
         */
        void recycle() {
            next = null;
            bufs = null;
            buf = null;
            msg = null;
            promise = null;
            progress = 0;
            total = 0;
            pendingSize = 0;
            count = -1;
            cancelled = false;
            handle.recycle(this);
        }

        /**
         * 返回下个对象 并 回收当前对象
         * @return
         */
        Entry recycleAndGetNext() {
            Entry next = this.next;
            recycle();
            return next;
        }
    }
}
