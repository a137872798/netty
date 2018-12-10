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
package io.netty.channel.nio;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.EventLoop;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.internal.ThrowableUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base class for {@link Channel} implementations which use a Selector based approach.
 *
 *
 */
public abstract class AbstractNioChannel extends AbstractChannel {

    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(AbstractNioChannel.class);

    private static final ClosedChannelException DO_CLOSE_CLOSED_CHANNEL_EXCEPTION = ThrowableUtil.unknownStackTrace(
            new ClosedChannelException(), AbstractNioChannel.class, "doClose()");

    /**
     * JDKchannel对象
     */
    private final SelectableChannel ch;
    /**
     * 注册事件
     */
    protected final int readInterestOp;
    /**
     * 返回的 准备事件
     */
    volatile SelectionKey selectionKey;
    /**
     * 是否正在读取
     */
    boolean readPending;
    /**
     * 清理注册事件的 任务
     */
    private final Runnable clearReadPendingRunnable = new Runnable() {
        @Override
        public void run() {
            clearReadPending0();
        }
    };

    /**
     * The future of the current connection attempt.  If not null, subsequent
     * connection attempts will fail.
     * 连接结果对象
     */
    private ChannelPromise connectPromise;
    /**
     * 定时任务
     */
    private ScheduledFuture<?> connectTimeoutFuture;
    /**
     * 客户端地址
     */
    private SocketAddress requestedRemoteAddress;

    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     * @param readInterestOp    the ops to set to receive data from the {@link SelectableChannel}
     */
    protected AbstractNioChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
        //默认 parent为null
        super(parent);
        //从下层传来的 JDK ServerSocketChannel or SocketChannel
        this.ch = ch;
        //初始化感兴趣的 事件 如果是 ServiceNioChannel 是 连接事件  客户端应该是 读取事件
        this.readInterestOp = readInterestOp;
        try {
            //设置 读写都不阻塞 还不太明白 底层开启额外线程写入???
            ch.configureBlocking(false);
        } catch (IOException e) {
            try {
                ch.close();
            } catch (IOException e2) {
                if (logger.isWarnEnabled()) {
                    logger.warn(
                            "Failed to close a partially initialized socket.", e2);
                }
            }

            throw new ChannelException("Failed to enter non-blocking mode.", e);
        }
    }

    @Override
    public boolean isOpen() {
        return ch.isOpen();
    }

    @Override
    public NioUnsafe unsafe() {
        return (NioUnsafe) super.unsafe();
    }

    protected SelectableChannel javaChannel() {
        return ch;
    }

    @Override
    public NioEventLoop eventLoop() {
        return (NioEventLoop) super.eventLoop();
    }

    /**
     * Return the current {@link SelectionKey}
     */
    protected SelectionKey selectionKey() {
        assert selectionKey != null;
        return selectionKey;
    }

    /**
     * @deprecated No longer supported.
     * No longer supported.
     */
    @Deprecated
    protected boolean isReadPending() {
        return readPending;
    }

    /**
     * @deprecated Use {@link #clearReadPending()} if appropriate instead.
     * No longer supported.
     */
    @Deprecated
    protected void setReadPending(final boolean readPending) {
        if (isRegistered()) {
            EventLoop eventLoop = eventLoop();
            if (eventLoop.inEventLoop()) {
                setReadPending0(readPending);
            } else {
                eventLoop.execute(new Runnable() {
                    @Override
                    public void run() {
                        setReadPending0(readPending);
                    }
                });
            }
        } else {
            // Best effort if we are not registered yet clear readPending.
            // NB: We only set the boolean field instead of calling clearReadPending0(), because the SelectionKey is
            // not set yet so it would produce an assertion failure.
            this.readPending = readPending;
        }
    }

    /**
     * Set read pending to {@code false}.
     */
    protected final void clearReadPending() {
        //当已经完成注册的情况 一般也就是处在这里
        if (isRegistered()) {
            EventLoop eventLoop = eventLoop();
            if (eventLoop.inEventLoop()) {
                //直接清除 对应的 autoRead 时 注册的 事件
                clearReadPending0();
            } else {
                //clearReadPendingRunnable 就是封装了clearReadPending0
                eventLoop.execute(clearReadPendingRunnable);
            }
        } else {
            // Best effort if we are not registered yet clear readPending. This happens during channel initialization.
            // NB: We only set the boolean field instead of calling clearReadPending0(), because the SelectionKey is
            // not set yet so it would produce an assertion failure.
            readPending = false;
        }
    }

    private void setReadPending0(boolean readPending) {
        this.readPending = readPending;
        if (!readPending) {
            ((AbstractNioUnsafe) unsafe()).removeReadOp();
        }
    }

    /**
     * 清理之前 注册的 事件
     */
    private void clearReadPending0() {
        readPending = false;
        ((AbstractNioUnsafe) unsafe()).removeReadOp();
    }

    /**
     * Special {@link Unsafe} sub-type which allows to access the underlying {@link SelectableChannel}
     */
    public interface NioUnsafe extends Unsafe {
        /**
         * Return underlying {@link SelectableChannel}
         */
        SelectableChannel ch();

        /**
         * Finish connect
         */
        void finishConnect();

        /**
         * Read from underlying {@link SelectableChannel}
         */
        void read();

        void forceFlush();
    }

    protected abstract class AbstractNioUnsafe extends AbstractUnsafe implements NioUnsafe {

        /**
         * 移除 beginRead 注册的事件 server 对应accept  client 对应 read
         */
        protected final void removeReadOp() {
            SelectionKey key = selectionKey();
            // Check first if the key is still valid as it may be canceled as part of the deregistration
            // from the EventLoop
            // See https://github.com/netty/netty/issues/2104
            if (!key.isValid()) {
                return;
            }
            int interestOps = key.interestOps();
            if ((interestOps & readInterestOp) != 0) {
                // only remove readInterestOp if needed
                key.interestOps(interestOps & ~readInterestOp);
            }
        }

        @Override
        public final SelectableChannel ch() {
            return javaChannel();
        }

        /**
         * 根据 指定地址 进行connect  这里一般情况是发起连接请求 但是这里不能直接给与结果 而是要 server 那段处理请求
         * @param remoteAddress
         * @param localAddress  没有的情况下为null
         * @param promise
         */
        @Override
        public final void connect(
                final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {
            //设置 不可关闭
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }

            try {
                //已经开始连接
                if (connectPromise != null) {
                    // Already a connect in process.
                    throw new ConnectionPendingException();
                }

                boolean wasActive = isActive();
                //如果已经完成连接 激活active 事件 这时 就不需要connect了 而是修改为 read 准备接受server 的数据
                if (doConnect(remoteAddress, localAddress)) {
                    fulfillConnectPromise(promise, wasActive);
                } else {
                    //设置 连接 promise 代表开始连接
                    connectPromise = promise;
                    //设置 连接地址
                    requestedRemoteAddress = remoteAddress;

                    // Schedule connect timeout.
                    int connectTimeoutMillis = config().getConnectTimeoutMillis();
                    if (connectTimeoutMillis > 0) {
                        //设置一个 超时任务  这个任务是一旦连接超时 就 自动关闭channel 并抛出异常
                        connectTimeoutFuture = eventLoop().schedule(new Runnable() {
                            @Override
                            public void run() {
                                ChannelPromise connectPromise = AbstractNioChannel.this.connectPromise;
                                ConnectTimeoutException cause =
                                        new ConnectTimeoutException("connection timed out: " + remoteAddress);
                                if (connectPromise != null && connectPromise.tryFailure(cause)) {
                                    //这里的 close 最后又会关闭 connectTimeoutFuture
                                    close(voidPromise());
                                }
                            }
                        }, connectTimeoutMillis, TimeUnit.MILLISECONDS);
                    }

                    //当连接成功时 触发回调
                    promise.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            //如果任务被关闭
                            if (future.isCancelled()) {
                                //关闭这个定时任务 防止 它 到时抛出异常
                                if (connectTimeoutFuture != null) {
                                    connectTimeoutFuture.cancel(false);
                                }
                                connectPromise = null;
                                //如果连接任务 被关闭 就 关闭channel
                                close(voidPromise());
                            }
                        }
                    });
                }
            } catch (Throwable t) {
                //annotateConnectException 做一个异常的映射
                promise.tryFailure(annotateConnectException(t, remoteAddress));
                //如果channel 已经被关闭 执行close 方法
                closeIfClosed();
            }
        }

        /**
         * 设置 connect promise 对象
         * @param promise
         * @param wasActive  一般是 false
         */
        private void fulfillConnectPromise(ChannelPromise promise, boolean wasActive) {
            if (promise == null) {
                // Closed via cancellation and the promise has been notified already.
                return;
            }

            // Get the state as trySuccess() may trigger an ChannelFutureListener that will close the Channel.
            // We still need to ensure we call fireChannelActive() in this case.
            boolean active = isActive();

            // trySuccess() will return false if a user cancelled the connection attempt.
            boolean promiseSet = promise.trySuccess();

            // Regardless if the connection attempt was cancelled, channelActive() event should be triggered,
            // because what happened is what happened.
            //触发 active事件
            if (!wasActive && active) {
                pipeline().fireChannelActive();
            }

            // If a user cancelled the connection attempt, close the channel, which is followed by channelInactive().
            if (!promiseSet) {
                close(voidPromise());
            }
        }

        private void fulfillConnectPromise(ChannelPromise promise, Throwable cause) {
            if (promise == null) {
                // Closed via cancellation and the promise has been notified already.
                return;
            }

            // Use tryFailure() instead of setFailure() to avoid the race against cancel().
            promise.tryFailure(cause);
            closeIfClosed();
        }

        /**
         * 当select 轮询到 连接事件时 转发到这里进行处理
         */
        @Override
        public final void finishConnect() {
            // Note this method is invoked by the event loop only if the connection attempt was
            // neither cancelled nor timed out.

            assert eventLoop().inEventLoop();

            try {
                //这里还没有完成connect 所以是false
                boolean wasActive = isActive();
                //这里完成了 连接事件
                doFinishConnect();
                //设置 promise 结果
                fulfillConnectPromise(connectPromise, wasActive);
            } catch (Throwable t) {
                fulfillConnectPromise(connectPromise, annotateConnectException(t, requestedRemoteAddress));
            } finally {
                // Check for null as the connectTimeoutFuture is only created if a connectTimeoutMillis > 0 is used
                // See https://github.com/netty/netty/issues/1770
                if (connectTimeoutFuture != null) {
                    connectTimeoutFuture.cancel(false);
                }
                connectPromise = null;
            }
        }

        /**
         * 相比父类 多了一个状态的判断
         */
        @Override
        protected final void flush0() {
            // Flush immediately only when there's no pending flush.
            // If there's a pending flush operation, event loop will call forceFlush() later,
            // and thus there's no need to call it now.
            // 如果不是正在 等待 flush 的状态 就开始 flush
            // JDK channel 本身是不需要绑定写事件的 如果 绑定了 就是 isFlushPending 为true 那么就代表暂时不能写入 那么flush 就不会调用了
            // forceFlush()
            if (!isFlushPending()) {
                super.flush0();
            }
        }

        @Override
        public final void forceFlush() {
            // directly call super.flush0() to force a flush now
            super.flush0();
        }

        /**
         * 判断是否正在等待 flush 状态
         * @return
         */
        private boolean isFlushPending() {
            SelectionKey selectionKey = selectionKey();
            //selectionKey 有效(这是JDK 原生的方法) 并且 已经注册了 写事件
            return selectionKey.isValid() && (selectionKey.interestOps() & SelectionKey.OP_WRITE) != 0;
        }
    }

    /**
     * 判断 channel 和 eventloop 是否对应
     * @param loop
     * @return
     */
    @Override
    protected boolean isCompatible(EventLoop loop) {
        return loop instanceof NioEventLoop;
    }

    /**
     * 注册的 实际逻辑 也就是将 channel 注册到 eventloop 的 选择器上
     * @throws Exception
     */
    @Override
    protected void doRegister() throws Exception {
        boolean selected = false;
        for (;;) {
            try {
                //这里为channel 注册 select 但是没有设置感兴趣事件 这里使用的是未包装的 select 就是每次select 之前不会清空key
                //这个返回的selectionKey 代表 该channel 与 selector的 关联关系 可以通过它 为这个channel设置感兴趣设置 能够监听到不同事件
                //同时attachment 绑定了自己 这样方便select 能够知道获取到 selectKey是哪个channel 的 并且可以直接对channel 进行操作
                selectionKey = javaChannel().register(eventLoop().unwrappedSelector(), 0, this);
                return;
            } catch (CancelledKeyException e) {
                if (!selected) {
                    // Force the Selector to select now as the "canceled" SelectionKey may still be
                    // cached and not removed because no Select.select(..) operation was called yet.
                    //这里会立即清空之前获取的 准备事件 可能在 register时 已经有准备好的事件会出bug  还不太懂
                    eventLoop().selectNow();
                    selected = true;
                } else {
                    // We forced a select operation on the selector before but the SelectionKey is still cached
                    // for whatever reason. JDK bug ?
                    throw e;
                }
            }
        }
    }

    @Override
    protected void doDeregister() throws Exception {
        eventLoop().cancel(selectionKey());
    }

    /**
     * 当channel active 也就是绑定 or 连接完成后开始读取
     * @throws Exception
     */
    @Override
    protected void doBeginRead() throws Exception {
        // Channel.read() or ChannelHandlerContext.read() was called
        //这个selectKey 是在register完成时设置的
        final SelectionKey selectionKey = this.selectionKey;
        if (!selectionKey.isValid()) {
            return;
        }
        //正在等待读取数据
        readPending = true;

        //获取当前感兴趣的事件
        final int interestOps = selectionKey.interestOps();
        if ((interestOps & readInterestOp) == 0) {
            //添加事件 这样在select 上就能 获取 准备完成的读事件了 在server 会设置 accept事件
            selectionKey.interestOps(interestOps | readInterestOp);
        }
    }

    /**
     * Connect to the remote peer
     */
    protected abstract boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception;

    /**
     * Finish the connect
     */
    protected abstract void doFinishConnect() throws Exception;

    /**
     * Returns an off-heap copy of the specified {@link ByteBuf}, and releases the original one.
     * Note that this method does not create an off-heap copy if the allocation / deallocation cost is too high,
     * but just returns the original {@link ByteBuf}..
     * 将原本的 bytebuf 封装成 directBuffer
     */
    protected final ByteBuf newDirectBuffer(ByteBuf buf) {
        final int readableBytes = buf.readableBytes();
        //该buf 中没有数据 返回空对象
        if (readableBytes == 0) {
            ReferenceCountUtil.safeRelease(buf);
            return Unpooled.EMPTY_BUFFER;
        }

        //获取 bytebuf 分配器
        final ByteBufAllocator alloc = alloc();
        //如果是 该分配器是池化直接内存分配器
        if (alloc.isDirectBufferPooled()) {
            //分配指定大小的 直接内存bytebuf对象
            ByteBuf directBuf = alloc.directBuffer(readableBytes);
            //将数据转移
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            //释放原对象
            ReferenceCountUtil.safeRelease(buf);
            return directBuf;
        }

        //如果 该分配器是 非池化分配器  或者是 非直接内存分配器  尝试获取本地线程中缓存的直接内存对象
        final ByteBuf directBuf = ByteBufUtil.threadLocalDirectBuffer();
        if (directBuf != null) {
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(buf);
            return directBuf;
        }

        // Allocating and deallocating an unpooled direct buffer is very expensive; give up.
        // 使用非池化的 bytebuf 分配 directbuffer 很昂贵 放弃该操作
        return buf;
    }

    /**
     * Returns an off-heap copy of the specified {@link ByteBuf}, and releases the specified holder.
     * The caller must ensure that the holder releases the original {@link ByteBuf} when the holder is released by
     * this method.  Note that this method does not create an off-heap copy if the allocation / deallocation cost is
     * too high, but just returns the original {@link ByteBuf}..
     */
    protected final ByteBuf newDirectBuffer(ReferenceCounted holder, ByteBuf buf) {
        final int readableBytes = buf.readableBytes();
        if (readableBytes == 0) {
            ReferenceCountUtil.safeRelease(holder);
            return Unpooled.EMPTY_BUFFER;
        }

        final ByteBufAllocator alloc = alloc();
        if (alloc.isDirectBufferPooled()) {
            ByteBuf directBuf = alloc.directBuffer(readableBytes);
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(holder);
            return directBuf;
        }

        final ByteBuf directBuf = ByteBufUtil.threadLocalDirectBuffer();
        if (directBuf != null) {
            directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
            ReferenceCountUtil.safeRelease(holder);
            return directBuf;
        }

        // Allocating and deallocating an unpooled direct buffer is very expensive; give up.
        if (holder != buf) {
            // Ensure to call holder.release() to give the holder a chance to release other resources than its content.
            buf.retain();
            ReferenceCountUtil.safeRelease(holder);
        }

        return buf;
    }

    /**
     * 关闭channel 要执行的逻辑 下层就是 关闭 JDK channel 了  这里主要是针对连接还未完成的情况下 被close
     * @throws Exception
     */
    @Override
    protected void doClose() throws Exception {
        //这个是用户调用连接返回的 promise 对象
        ChannelPromise promise = connectPromise;
        //为之前的 connectPromise 设置 异常
        if (promise != null) {
            // Use tryFailure() instead of setFailure() to avoid the race against cancel().
            promise.tryFailure(DO_CLOSE_CLOSED_CHANNEL_EXCEPTION);
            connectPromise = null;
        }

        //如果 等待连接的 future 还存在 就直接关闭 这个对象是当发起连接时 在指定时候后自动抛出异常提示用户 连接超时的对象
        ScheduledFuture<?> future = connectTimeoutFuture;
        if (future != null) {
            future.cancel(false);
            connectTimeoutFuture = null;
        }
    }
}
