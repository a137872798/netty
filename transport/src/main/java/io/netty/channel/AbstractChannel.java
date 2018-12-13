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
package io.netty.channel;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.socket.ChannelOutputShutdownEvent;
import io.netty.channel.socket.ChannelOutputShutdownException;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ThrowableUtil;
import io.netty.util.internal.UnstableApi;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * A skeletal {@link Channel} implementation.
 *
 * 通道对象  attribute 代表了 channel能设置属性的特性
 */
public abstract class AbstractChannel extends DefaultAttributeMap implements Channel {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractChannel.class);

    private static final ClosedChannelException FLUSH0_CLOSED_CHANNEL_EXCEPTION = ThrowableUtil.unknownStackTrace(
            new ClosedChannelException(), AbstractUnsafe.class, "flush0()");
    private static final ClosedChannelException ENSURE_OPEN_CLOSED_CHANNEL_EXCEPTION = ThrowableUtil.unknownStackTrace(
            new ClosedChannelException(), AbstractUnsafe.class, "ensureOpen(...)");
    private static final ClosedChannelException CLOSE_CLOSED_CHANNEL_EXCEPTION = ThrowableUtil.unknownStackTrace(
            new ClosedChannelException(), AbstractUnsafe.class, "close(...)");
    private static final ClosedChannelException WRITE_CLOSED_CHANNEL_EXCEPTION = ThrowableUtil.unknownStackTrace(
            new ClosedChannelException(), AbstractUnsafe.class, "write(...)");
    private static final NotYetConnectedException FLUSH0_NOT_YET_CONNECTED_EXCEPTION = ThrowableUtil.unknownStackTrace(
            new NotYetConnectedException(), AbstractUnsafe.class, "flush0()");

    /**
     * channel 存在一个 父对象
     */
    private final Channel parent;
    /**
     * 该channel 的唯一id
     */
    private final ChannelId id;
    /**
     * unsafe 对象 一切connect bind 等操作都是委托给该对象实现的
     */
    private final Unsafe unsafe;
    /**
     * 该channel 所属的 pipeline 对象 组合了一个handler 链
     */
    private final DefaultChannelPipeline pipeline;
    /**
     * 该对象无法支持监听器 当出现异常时 根据 是否允许触发异常 调用 pipeline 的 异常事件 否则不做处理 且结果始终是 失败
     * 在channel 进行关闭的时候 返回的promise 就是这个对象
     */
    private final VoidChannelPromise unsafeVoidPromise = new VoidChannelPromise(this, false);

    /**
     * 已经关闭的 promise 对象 无法进行操作 注意 promise 对象 始终会关联到一个 channel 代表这个对象是针对哪个连接的
     */
    private final CloseFuture closeFuture = new CloseFuture(this);

    //地址对象

    private volatile SocketAddress localAddress;
    private volatile SocketAddress remoteAddress;

    /**
     * 该channel 绑定的 事件循环对象
     */
    private volatile EventLoop eventLoop;
    /**
     * 该channel 是否已经注册完成
     */
    private volatile boolean registered;
    /**
     * 是否开始关闭
     */
    private boolean closeInitiated;

    /** Cache for the string representation of this channel */
    /**
     * 是否 缓存 该channel 的 String表现形式???
     */
    private boolean strValActive;
    /**
     * 该channel 的 String 表现形式
     */
    private String strVal;

    /**
     * Creates a new instance.
     *
     * @param parent
     *        the parent of this channel. {@code null} if there's no parent.
     */
    protected AbstractChannel(Channel parent) {
        this.parent = parent;
        //为该channel 创建id
        id = newId();

        //这2个对象 就是 channel 的核心了 unsafe 管理write connect 等一系列操作 pipeline 则是执行通过事件触发的处理器逻辑

        //创建unsafe 对象
        unsafe = newUnsafe();
        //创建 pipeline 对象
        pipeline = newChannelPipeline();
    }

    /**
     * Creates a new instance.
     *
     * @param parent
     *        the parent of this channel. {@code null} if there's no parent.
     */
    protected AbstractChannel(Channel parent, ChannelId id) {
        this.parent = parent;
        this.id = id;
        unsafe = newUnsafe();
        pipeline = newChannelPipeline();
    }

    @Override
    public final ChannelId id() {
        return id;
    }

    /**
     * Returns a new {@link DefaultChannelId} instance. Subclasses may override this method to assign custom
     * {@link ChannelId}s to {@link Channel}s that use the {@link AbstractChannel#AbstractChannel(Channel)} constructor.
     * 返回一个 ChannelId 实例 作为 该channel 的唯一id 这个对象包含了 machineId processId 等一系列能唯一定位channel的参数
     */
    protected ChannelId newId() {
        return DefaultChannelId.newInstance();
    }

    /**
     * Returns a new {@link DefaultChannelPipeline} instance.
     * 创建一个 默认的 pipeline 对象 传入了本channel对象 这里就是做了一些初始化工作包括生成 head tail 上下文对象
     */
    protected DefaultChannelPipeline newChannelPipeline() {
        return new DefaultChannelPipeline(this);
    }

    /**
     * 是否可写 是通过 委托到unsafe 对象实现的  这里就是使用到高低水位的 地方 这个水位 判断 没有在项目中看到 难道使用户 自己获取
     * channel 来判断吗???
     * @return
     */
    @Override
    public boolean isWritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        return buf != null && buf.isWritable();
    }

    @Override
    public long bytesBeforeUnwritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        // isWritable() is currently assuming if there is no outboundBuffer then the channel is not writable.
        // We should be consistent with that here.
        return buf != null ? buf.bytesBeforeUnwritable() : 0;
    }

    @Override
    public long bytesBeforeWritable() {
        ChannelOutboundBuffer buf = unsafe.outboundBuffer();
        // isWritable() is currently assuming if there is no outboundBuffer then the channel is not writable.
        // We should be consistent with that here.
        return buf != null ? buf.bytesBeforeWritable() : Long.MAX_VALUE;
    }

    @Override
    public Channel parent() {
        return parent;
    }

    @Override
    public ChannelPipeline pipeline() {
        return pipeline;
    }

    /**
     * 获取 byte 分配器的 逻辑被转移到 config 对象上  这个config 由子类来实现根据不同channel 类型返回不同channelConfig
     * @return
     */
    @Override
    public ByteBufAllocator alloc() {
        return config().getAllocator();
    }

    /**
     * 获取该channel 绑定的 事件循环对象
     * @return
     */
    @Override
    public EventLoop eventLoop() {
        EventLoop eventLoop = this.eventLoop;
        if (eventLoop == null) {
            throw new IllegalStateException("channel not registered to an event loop");
        }
        return eventLoop;
    }

    @Override
    public SocketAddress localAddress() {
        SocketAddress localAddress = this.localAddress;
        if (localAddress == null) {
            try {
                this.localAddress = localAddress = unsafe().localAddress();
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                // Sometimes fails on a closed socket in Windows.
                return null;
            }
        }
        return localAddress;
    }

    /**
     * @deprecated no use-case for this.
     */
    @Deprecated
    protected void invalidateLocalAddress() {
        localAddress = null;
    }

    @Override
    public SocketAddress remoteAddress() {
        SocketAddress remoteAddress = this.remoteAddress;
        if (remoteAddress == null) {
            try {
                this.remoteAddress = remoteAddress = unsafe().remoteAddress();
            } catch (Error e) {
                throw e;
            } catch (Throwable t) {
                // Sometimes fails on a closed socket in Windows.
                return null;
            }
        }
        return remoteAddress;
    }

    /**
     * @deprecated no use-case for this.
     */
    @Deprecated
    protected void invalidateRemoteAddress() {
        remoteAddress = null;
    }

    @Override
    public boolean isRegistered() {
        return registered;
    }

    //绑定和 连接是用 pipeline 实现的 ???不是 unsafe吗

    @Override
    public ChannelFuture bind(SocketAddress localAddress) {
        return pipeline.bind(localAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress) {
        return pipeline.connect(remoteAddress);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return pipeline.connect(remoteAddress, localAddress);
    }

    @Override
    public ChannelFuture disconnect() {
        return pipeline.disconnect();
    }

    @Override
    public ChannelFuture close() {
        return pipeline.close();
    }

    @Override
    public ChannelFuture deregister() {
        return pipeline.deregister();
    }

    @Override
    public Channel flush() {
        pipeline.flush();
        return this;
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress, ChannelPromise promise) {
        return pipeline.bind(localAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return pipeline.connect(remoteAddress, promise);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
        return pipeline.connect(remoteAddress, localAddress, promise);
    }

    @Override
    public ChannelFuture disconnect(ChannelPromise promise) {
        return pipeline.disconnect(promise);
    }

    @Override
    public ChannelFuture close(ChannelPromise promise) {
        return pipeline.close(promise);
    }

    @Override
    public ChannelFuture deregister(ChannelPromise promise) {
        return pipeline.deregister(promise);
    }

    @Override
    public Channel read() {
        pipeline.read();
        return this;
    }

    @Override
    public ChannelFuture write(Object msg) {
        return pipeline.write(msg);
    }

    @Override
    public ChannelFuture write(Object msg, ChannelPromise promise) {
        return pipeline.write(msg, promise);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg) {
        return pipeline.writeAndFlush(msg);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg, ChannelPromise promise) {
        return pipeline.writeAndFlush(msg, promise);
    }

    @Override
    public ChannelPromise newPromise() {
        return pipeline.newPromise();
    }

    @Override
    public ChannelProgressivePromise newProgressivePromise() {
        return pipeline.newProgressivePromise();
    }

    @Override
    public ChannelFuture newSucceededFuture() {
        return pipeline.newSucceededFuture();
    }

    @Override
    public ChannelFuture newFailedFuture(Throwable cause) {
        return pipeline.newFailedFuture(cause);
    }

    @Override
    public ChannelFuture closeFuture() {
        return closeFuture;
    }

    @Override
    public Unsafe unsafe() {
        return unsafe;
    }

    /**
     * Create a new {@link AbstractUnsafe} instance which will be used for the life-time of the {@link Channel}
     * 创建 unsafe 根据具体的 channel 实现来决定 一般默认是 NioChannel
     *
     * 这里的 unsafe 也有2个 一个是 byteunsafe  一个是 messageunsafe
     */
    protected abstract AbstractUnsafe newUnsafe();

    /**
     * Returns the ID of this channel.
     */
    @Override
    public final int hashCode() {
        return id.hashCode();
    }

    /**
     * Returns {@code true} if and only if the specified object is identical
     * with this channel (i.e: {@code this == o}).
     */
    @Override
    public final boolean equals(Object o) {
        return this == o;
    }

    @Override
    public final int compareTo(Channel o) {
        if (this == o) {
            return 0;
        }

        return id().compareTo(o.id());
    }

    /**
     * Returns the {@link String} representation of this channel.  The returned
     * string contains the {@linkplain #hashCode() ID}, {@linkplain #localAddress() local address},
     * and {@linkplain #remoteAddress() remote address} of this channel for
     * easier identification.
     */
    @Override
    public String toString() {
        boolean active = isActive();
        if (strValActive == active && strVal != null) {
            return strVal;
        }

        SocketAddress remoteAddr = remoteAddress();
        SocketAddress localAddr = localAddress();
        if (remoteAddr != null) {
            StringBuilder buf = new StringBuilder(96)
                .append("[id: 0x")
                .append(id.asShortText())
                .append(", L:")
                .append(localAddr)
                .append(active? " - " : " ! ")
                .append("R:")
                .append(remoteAddr)
                .append(']');
            strVal = buf.toString();
        } else if (localAddr != null) {
            StringBuilder buf = new StringBuilder(64)
                .append("[id: 0x")
                .append(id.asShortText())
                .append(", L:")
                .append(localAddr)
                .append(']');
            strVal = buf.toString();
        } else {
            StringBuilder buf = new StringBuilder(16)
                .append("[id: 0x")
                .append(id.asShortText())
                .append(']');
            strVal = buf.toString();
        }

        strValActive = active;
        return strVal;
    }

    @Override
    public final ChannelPromise voidPromise() {
        return pipeline.voidPromise();
    }

    /**
     * {@link Unsafe} implementation which sub-classes must extend and use.
     *
     * unsafe  的默认实现
     */
    protected abstract class AbstractUnsafe implements Unsafe {

        /**
         * 获取一个 输出 buf对象
         */
        private volatile ChannelOutboundBuffer outboundBuffer = new ChannelOutboundBuffer(AbstractChannel.this);
        /**
         * 可回收 内存分配器的handle 对象
         */
        private RecvByteBufAllocator.Handle recvHandle;
        /**
         * 是否刷盘
         */
        private boolean inFlush0;
        /** true if the channel has never been registered, false otherwise */
        private boolean neverRegistered = true;

        private void assertEventLoop() {
            assert !registered || eventLoop.inEventLoop();
        }

        @Override
        public RecvByteBufAllocator.Handle recvBufAllocHandle() {
            if (recvHandle == null) {
                //从配置中获取的
                recvHandle = config().getRecvByteBufAllocator().newHandle();
            }
            return recvHandle;
        }

        @Override
        public final ChannelOutboundBuffer outboundBuffer() {
            return outboundBuffer;
        }

        @Override
        public final SocketAddress localAddress() {
            return localAddress0();
        }

        @Override
        public final SocketAddress remoteAddress() {
            return remoteAddress0();
        }

        /**
         * 为channel 注册 事件执行线程 就是走这里
         * 这时传入的 eventLoop 还没有开启select 也没有开启线程(不一定 可能之前绑过别的channel了)
         * @param eventLoop
         * @param promise 这个是用来做异步的 里面包含的线程对象就是eventLoop 这样保证注册使用的是 非IO线程
         */
        @Override
        public final void register(EventLoop eventLoop, final ChannelPromise promise) {
            if (eventLoop == null) {
                throw new NullPointerException("eventLoop");
            }
            //如果channel 已经注册了 就不能再注册了 bind 会自动注册
            if (isRegistered()) {
                promise.setFailure(new IllegalStateException("registered to an event loop already"));
                return;
            }
            //判断 channel 和 eventloop 的类型是不是对应的
            if (!isCompatible(eventLoop)) {
                promise.setFailure(
                        new IllegalStateException("incompatible event loop type: " + eventLoop.getClass().getName()));
                return;
            }

            //这里给 channel 设置 了 eventLoop属性
            AbstractChannel.this.eventLoop = eventLoop;

            //一般绑定动作都是在 IO线程执行的 所以一般都是使用eventLoop 专有的线程执行任务
            if (eventLoop.inEventLoop()) {
                register0(promise);
            } else {
                try {
                    //使用专有线程执行任务 同时也代表这里是 异步的起点 用户已经可以操作 promise 了
                    //这里的 execute 应该一开始是要创建独有线程的 然后创建成功只要一直使用那个线程的引用就行了 而不用反复创建
                    eventLoop.execute(new Runnable() {
                        @Override
                        public void run() {
                            register0(promise);
                        }
                    });
                } catch (Throwable t) {
                    logger.warn(
                            "Force-closing a channel whose registration task was not accepted by an event loop: {}",
                            AbstractChannel.this, t);
                    closeForcibly();
                    //这一步应该是为了 触发监听器 在什么时机 设置???
                    closeFuture.setClosed();
                    safeSetFailure(promise, t);
                }
            }
        }

        /**
         * 注册的实际逻辑 这里应该是 将该JDK channel 绑定到选择器上
         * @param promise
         */
        private void register0(ChannelPromise promise) {
            try {
                // check if the channel is still open as it could be closed in the mean time when the register
                // call was outside of the eventLoop
                //这里必须保证promise 不能被打断 也就是设置一个 标识  JDKChannel 必须处于打开状态
                if (!promise.setUncancellable() || !ensureOpen(promise)) {
                    return;
                }
                //设置 已注册标识 方便直接拒绝之后的注册行为
                boolean firstRegistration = neverRegistered;
                //注册的 实际逻辑也就是 将 channel 注册到 select上
                doRegister();
                neverRegistered = false;
                registered = true;

                // Ensure we call handlerAdded(...) before we actually notify the promise. This is needed as the
                // user may already fire events through the pipeline in the ChannelFutureListener.
                // 注册完成后 尝试触发 pipeline pendingHandlerCallbackHead 对象还没有初始化 就不会触发handler
                // 一般就不会触发
                pipeline.invokeHandlerAddedIfNeeded();

                //安全设置成功 结果 代表设置 注册到channel 成功 这时 用户可以执行bind 了  同时本线程也会继续进行
                safeSetSuccess(promise);
                //这里触发了 register
                pipeline.fireChannelRegistered();
                // Only fire a channelActive if the channel has never been registered. This prevents firing
                // multiple channel actives if the channel is deregistered and re-registered.

                //是否 已经绑定该channel 一般是 false 因为主线程 才刚触发回调
                if (isActive()) {
                    if (firstRegistration) {
                        pipeline.fireChannelActive();
                    } else if (config().isAutoRead()) {
                        // This channel was registered before and autoRead() is set. This means we need to begin read
                        // again so that we process inbound data.
                        //
                        // See https://github.com/netty/netty/issues/4805
                        beginRead();
                    }
                }
            } catch (Throwable t) {
                // Close the channel directly to avoid FD leak.
                closeForcibly();
                closeFuture.setClosed();
                safeSetFailure(promise, t);
            }
        }

        /**
         * 当把 channel 绑定到本地端口时触发  promise 用户用来判断是否完成操作的promise 对象
         * @param localAddress
         * @param promise
         */
        @Override
        public final void bind(final SocketAddress localAddress, final ChannelPromise promise) {
            //执行eventloop 执行的 会加入到任务队列 然后 独占线程一直在执行select 以及 处理队列任务 所以一定是独占线程在执行这个任务
            assertEventLoop();

            //如果不能设置成不可关闭 就返回  就是设置一个 标识 同时 如果 channel 已经关闭 将promise 设置成false 并触发 listener
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }

            // See: https://github.com/netty/netty/issues/576
            // 广播模式 不懂先不管
            if (Boolean.TRUE.equals(config().getOption(ChannelOption.SO_BROADCAST)) &&
                localAddress instanceof InetSocketAddress &&
                !((InetSocketAddress) localAddress).getAddress().isAnyLocalAddress() &&
                !PlatformDependent.isWindows() && !PlatformDependent.maybeSuperUser()) {
                // Warn a user about the fact that a non-root user can't receive a
                // broadcast packet on *nix if the socket is bound on non-wildcard address.
                logger.warn(
                        "A non-root user can't receive a broadcast packet if the socket " +
                        "is not bound to a wildcard address; binding to a non-wildcard " +
                        "address (" + localAddress + ") anyway as requested.");
            }

            //现在 client channel 还没有连接 也就是 active = false
            //server 也是不活跃 还没有绑定
            boolean wasActive = isActive();
            try {
                //绑定逻辑 委托给子类 也就是调用JDK channel 进行绑定 就是将地址绑定到 JDK channel 上
                doBind(localAddress);
            } catch (Throwable t) {
                safeSetFailure(promise, t);
                closeIfClosed();
                return;
            }

            //这里不存在并发情况 因为已经是 在独占线程中处理了 并且该触发任务是 放入到队列中 也就是要排队 而不是现在就直接执行
            //这里只是 server 会 active client 必须要连接成功才会设置 active 那么服务器会开启 accept监听
            if (!wasActive && isActive()) {
                invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireChannelActive();
                    }
                });
            }

            //设置成功并触发回调 这个promise 是用户调用bind 时返回的
            safeSetSuccess(promise);
        }

        /**
         * disconnect 触发
         * @param promise
         */
        @Override
        public final void disconnect(final ChannelPromise promise) {
            assertEventLoop();

            if (!promise.setUncancellable()) {
                return;
            }

            boolean wasActive = isActive();
            try {
                doDisconnect();
            } catch (Throwable t) {
                safeSetFailure(promise, t);
                closeIfClosed();
                return;
            }

            if (wasActive && !isActive()) {
                invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireChannelInactive();
                    }
                });
            }

            safeSetSuccess(promise);
            closeIfClosed(); // doDisconnect() might have closed the channel
        }

        @Override
        public final void close(final ChannelPromise promise) {
            //必须在独占线程执行 看来外部是调用不了这个方法的
            assertEventLoop();

            close(promise, CLOSE_CLOSED_CHANNEL_EXCEPTION, CLOSE_CLOSED_CHANNEL_EXCEPTION, false);
        }

        /**
         * Shutdown the output portion of the corresponding {@link Channel}.
         * For example this will clean up the {@link ChannelOutboundBuffer} and not allow any more writes.
         */
        @UnstableApi
        public final void shutdownOutput(final ChannelPromise promise) {
            assertEventLoop();
            shutdownOutput(promise, null);
        }

        /**
         * Shutdown the output portion of the corresponding {@link Channel}.
         * For example this will clean up the {@link ChannelOutboundBuffer} and not allow any more writes.
         * @param cause The cause which may provide rational for the shutdown.
         *
         * 关闭 output 流
         */
        private void shutdownOutput(final ChannelPromise promise, Throwable cause) {
            if (!promise.setUncancellable()) {
                return;
            }

            //获取 内存队列
            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null) {
                promise.setFailure(CLOSE_CLOSED_CHANNEL_EXCEPTION);
                return;
            }
            //置空 这样就不能再写入了
            this.outboundBuffer = null; // Disallow adding any messages and flushes to outboundBuffer.

            final Throwable shutdownCause = cause == null ?
                    new ChannelOutputShutdownException("Channel output shutdown") :
                    new ChannelOutputShutdownException("Channel output shutdown", cause);
            //注销该channel 返回一个全局线程池
            Executor closeExecutor = prepareToClose();
            if (closeExecutor != null) {
                closeExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // Execute the shutdown.
                            doShutdownOutput();
                            promise.setSuccess();
                        } catch (Throwable err) {
                            promise.setFailure(err);
                        } finally {
                            // Dispatch to the EventLoop
                            eventLoop().execute(new Runnable() {
                                @Override
                                public void run() {
                                    //设置一个关闭任务 到eventloop上
                                    closeOutboundBufferForShutdown(pipeline, outboundBuffer, shutdownCause);
                                }
                            });
                        }
                    }
                });
            } else {
                try {
                    // Execute the shutdown.
                    doShutdownOutput();
                    promise.setSuccess();
                } catch (Throwable err) {
                    promise.setFailure(err);
                } finally {
                    closeOutboundBufferForShutdown(pipeline, outboundBuffer, shutdownCause);
                }
            }
        }

        /**
         * 关闭输出流
         * @param pipeline
         * @param buffer
         * @param cause
         */
        private void closeOutboundBufferForShutdown(
                ChannelPipeline pipeline, ChannelOutboundBuffer buffer, Throwable cause) {
            //提示bytebuf 刷盘失败
            buffer.failFlushed(cause, false);
            //关闭buf
            buffer.close(cause, true);
            //根据实例触发用户事件
            pipeline.fireUserEventTriggered(ChannelOutputShutdownEvent.INSTANCE);
        }

        /**
         * 当 channel 被关闭时调用这个
         *
         * 总共做3件事  1.关闭channel 2.在select上 注销 selectKey 3.销毁pipeline
         * @param promise 是 unsafeVoidPromise 对象
         * @param cause
         * @param closeCause
         * @param notify
         */
        private void close(final ChannelPromise promise, final Throwable cause,
                           final ClosedChannelException closeCause, final boolean notify) {
            //unsafeVoidPromise  这个方法被重写了  直接返回true 也就是一定能往下走
            if (!promise.setUncancellable()) {
                return;
            }

            //如果已经开始关闭了
            if (closeInitiated) {
                //如果已经完成  设置结果
                if (closeFuture.isDone()) {
                    // Closed already.
                    safeSetSuccess(promise);
                    //这里会触发吗???
                } else if (!(promise instanceof VoidChannelPromise)) { // Only needed if no VoidChannelPromise.
                    // This means close() was called before so we just register a listener and return
                    closeFuture.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) throws Exception {
                            promise.setSuccess();
                        }
                    });
                }
                return;
            }

            closeInitiated = true;

            //当前应该是 活跃的
            final boolean wasActive = isActive();
            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            //置空了 之前的数据都会被gc 回收
            this.outboundBuffer = null; // Disallow adding any messages and flushes to outboundBuffer.
            //这里看是否设置了 SoLonger 属性 设置了 就会先取消client的 注册 并返回一个 全局线程池对象
            Executor closeExecutor = prepareToClose();
            if (closeExecutor != null) {
                //在额外线程池中进行 socket 的 关闭 因为 设置了 SoLonger 会阻塞当前线程
                closeExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // Execute the close.
                            // 核心逻辑就是 关闭socket 对象
                            doClose0(promise);
                        } finally {
                            // Call invokeLater so closeAndDeregister is executed in the EventLoop again!
                            invokeLater(new Runnable() {
                                @Override
                                public void run() {
                                    //这里存放了 最后的数据进行 写入和 关闭
                                    if (outboundBuffer != null) {
                                        // Fail all the queued messages
                                        outboundBuffer.failFlushed(cause, notify);
                                        outboundBuffer.close(closeCause);
                                    }
                                    //close 后 会触发 inactive
                                    fireChannelInactiveAndDeregister(wasActive);
                                }
                            });
                        }
                    }
                });
            } else {
                //这里代表 没有设置 SoLonger  也就是 socket.close() 会立即返回结果
                try {
                    // Close the channel and fail the queued messages in all cases.
                    doClose0(promise);
                } finally {
                    //这里存放了 最后的数据进行 写入和 关闭
                    if (outboundBuffer != null) {
                        // Fail all the queued messages.
                        outboundBuffer.failFlushed(cause, notify);
                        outboundBuffer.close(closeCause);
                    }
                }
                //如果还在刷盘中 等待完成 后 才 激活 inactive 方法 这里会触发 注销
                if (inFlush0) {
                    invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            fireChannelInactiveAndDeregister(wasActive);
                        }
                    });
                } else {
                    fireChannelInactiveAndDeregister(wasActive);
                }
            }
        }

        /**
         * channel 关闭的 实际逻辑
         * @param promise
         */
        private void doClose0(ChannelPromise promise) {
            try {
                //这里 client 和 server 应该是不同的 因为 server 还没有进行注销 但是 server 只是将JDK channel 关闭没有做别的事
                doClose();
                //设置已关闭 也就是 针对CloseFuture.trySuccess 这个对象就是对应到 client.closeFuture().sync() 阻塞主线程监听客户端关闭
                closeFuture.setClosed();
                //将promise 设置成 success
                safeSetSuccess(promise);
            } catch (Throwable t) {
                closeFuture.setClosed();
                safeSetFailure(promise, t);
            }
        }

        /**
         * 关闭channel 时触发
         * @param wasActive
         */
        private void fireChannelInactiveAndDeregister(final boolean wasActive) {
            deregister(voidPromise(), wasActive && !isActive());
        }

        /**
         * 暴力关闭 没有触发 pipeline 上的事件
         */
        @Override
        public final void closeForcibly() {
            assertEventLoop();

            try {
                doClose();
            } catch (Exception e) {
                logger.warn("Failed to close a channel.", e);
            }
        }

        @Override
        public final void deregister(final ChannelPromise promise) {
            assertEventLoop();

            deregister(promise, false);
        }

        /**
         * 关闭channel 时触发
         * @param promise
         * @param fireChannelInactive
         */
        private void deregister(final ChannelPromise promise, final boolean fireChannelInactive) {
            //一般都是 voidPromise  该方法重写后 直接返回true
            if (!promise.setUncancellable()) {
                return;
            }

            //如果已经注销了直接返回
            if (!registered) {
                safeSetSuccess(promise);
                return;
            }

            // As a user may call deregister() from within any method while doing processing in the ChannelPipeline,
            // we need to ensure we do the actual deregister operation later. This is needed as for example,
            // we may be in the ByteToMessageDecoder.callDecode(...) method and so still try to do processing in
            // the old EventLoop while the user already registered the Channel to a new EventLoop. Without delay,
            // the deregister operation this could lead to have a handler invoked by different EventLoop and so
            // threads.
            //
            // See:
            // https://github.com/netty/netty/issues/4435
            //理解不了上面的意思 大概就是 注销必须延迟执行 直接执行会有问题
            invokeLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        //注销selectionKey
                        doDeregister();
                    } catch (Throwable t) {
                        logger.warn("Unexpected exception occurred while deregistering a channel.", t);
                    } finally {
                        //传递 失活事件
                        if (fireChannelInactive) {
                            pipeline.fireChannelInactive();
                        }
                        // Some transports like local and AIO does not allow the deregistration of
                        // an open channel.  Their doDeregister() calls close(). Consequently,
                        // close() calls deregister() again - no need to fire channelUnregistered, so check
                        // if it was registered.
                        // 触发注销事件
                        if (registered) {
                            registered = false;
                            pipeline.fireChannelUnregistered();
                        }
                        safeSetSuccess(promise);
                    }
                }
            });
        }

        /**
         * 当channel 绑定 完成后 开始 设置 感兴趣的事件
         */
        @Override
        public final void beginRead() {
            assertEventLoop();

            if (!isActive()) {
                return;
            }

            try {
                doBeginRead();
            } catch (final Exception e) {
                //将任务设置到队尾
                invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        pipeline.fireExceptionCaught(e);
                    }
                });
                close(voidPromise());
            }
        }

        /**
         * 写入数据的核心逻辑  这里是 怎么保证write 的数据是 bytebuf 类型的???
         * @param msg
         * @param promise
         */
        @Override
        public final void write(Object msg, ChannelPromise promise) {
            //必须在 事件循环中
            assertEventLoop();

            ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            //如果不存在outboundBuffer 对象就没办法 写数据  置空一般就是被closeoutput了
            if (outboundBuffer == null) {
                // If the outboundBuffer is null we know the channel was closed and so
                // need to fail the future right away. If it is not null the handling of the rest
                // will be done in flush0()
                // See https://github.com/netty/netty/issues/2362
                // 设置结果 触发回调
                safeSetFailure(promise, WRITE_CLOSED_CHANNEL_EXCEPTION);
                // release message now to prevent resource-leak
                ReferenceCountUtil.release(msg);
                return;
            }

            int size;
            try {
                //对数据做处理  如果是 bytebuf 数据会被转移到一个新的 directBytebuf中
                msg = filterOutboundMessage(msg);
                //计算该 msg 大小
                size = pipeline.estimatorHandle().size(msg);
                if (size < 0) {
                    size = 0;
                }
            } catch (Throwable t) {
                safeSetFailure(promise, t);
                ReferenceCountUtil.release(msg);
                return;
            }

            //将数据添加到outboundbytebuf 中  注意这里的 msg 是 bytebuf类型的
            outboundBuffer.addMessage(msg, size, promise);
        }

        /**
         * 对应到 pipeline 的 刷盘操作
         */
        @Override
        public final void flush() {
            assertEventLoop();

            ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null) {
                return;
            }

            //标记内存队列开始 flush
            outboundBuffer.addFlush();
            //flush 实际逻辑
            flush0();
        }

        /**
         * unsafe 的 flush 会转发到这里   NioChannel 对这个方法重写了 增加了对 OP_WRITE事件的判断 如果注册了就是 TCP
         * 缓冲区写满了 那需要等待 写事件 准备完 才能开始写入
         * 当下次准备好 OP_WRITE  后会进行强制 刷盘
         */
        @SuppressWarnings("deprecation")
        protected void flush0() {
            //正在刷盘 直接返回
            if (inFlush0) {
                // Avoid re-entrance
                return;
            }

            //获取buf对象
            final ChannelOutboundBuffer outboundBuffer = this.outboundBuffer;
            if (outboundBuffer == null || outboundBuffer.isEmpty()) {
                return;
            }

            //设置标识
            inFlush0 = true;

            // Mark all pending write requests as failure if the channel is inactive.
            //如果 连接已经中断了
            if (!isActive()) {
                try {
                    //写出不同的异常 这些都是配合用户自定义事件做处理的
                    if (isOpen()) {
                        outboundBuffer.failFlushed(FLUSH0_NOT_YET_CONNECTED_EXCEPTION, true);
                    } else {
                        // Do not trigger channelWritabilityChanged because the channel is closed already.
                        outboundBuffer.failFlushed(FLUSH0_CLOSED_CHANNEL_EXCEPTION, false);
                    }
                } finally {
                    //完成刷盘
                    inFlush0 = false;
                }
                return;
            }

            try {
                //完成写动作  其实就是将数据写入到 JDK channel 同时 如果写不进去了 就会注册 OP_WRITE 事件
                doWrite(outboundBuffer);
            } catch (Throwable t) {
                //如果设置了 异常自动关闭就 关闭
                if (t instanceof IOException && config().isAutoClose()) {
                    /**
                     * Just call {@link #close(ChannelPromise, Throwable, boolean)} here which will take care of
                     * failing all flushed messages and also ensure the actual close of the underlying transport
                     * will happen before the promises are notified.
                     *
                     * This is needed as otherwise {@link #isActive()} , {@link #isOpen()} and {@link #isWritable()}
                     * may still return {@code true} even if the channel should be closed as result of the exception.
                     *
                     * 出现异常关闭channel
                     */
                    close(voidPromise(), t, FLUSH0_CLOSED_CHANNEL_EXCEPTION, false);
                } else {
                    try {
                        //关闭输出流 应该对应的 也是JDK 底层的方法
                        shutdownOutput(voidPromise(), t);
                    } catch (Throwable t2) {
                        close(voidPromise(), t2, FLUSH0_CLOSED_CHANNEL_EXCEPTION, false);
                    }
                }
            } finally {
                inFlush0 = false;
            }
        }

        @Override
        public final ChannelPromise voidPromise() {
            assertEventLoop();

            return unsafeVoidPromise;
        }

        /**
         * 判断当前是否已开启
         * @param promise
         * @return
         */
        protected final boolean ensureOpen(ChannelPromise promise) {
            //JDKchannel 是否处于开启状态
            if (isOpen()) {
                return true;
            }

            //因为channel 属于关闭状态 设置失败结果
            //如果出现异常变成打印日志 因为如果 promise 是 VoidPromise 是不能设置结果的
            safeSetFailure(promise, ENSURE_OPEN_CLOSED_CHANNEL_EXCEPTION);
            return false;
        }

        /**
         * Marks the specified {@code promise} as success.  If the {@code promise} is done already, log a message.
         */
        protected final void safeSetSuccess(ChannelPromise promise) {
            if (!(promise instanceof VoidChannelPromise) && !promise.trySuccess()) {
                logger.warn("Failed to mark a promise as success because it is done already: {}", promise);
            }
        }

        /**
         * Marks the specified {@code promise} as failure.  If the {@code promise} is done already, log a message.
         * 如果是 特殊的promise 设置结果会抛出异常 这里变成打印日志的情况 一般是不会出现问题的 因为这个promise 是 channel eventLoop传进来的
         */
        protected final void safeSetFailure(ChannelPromise promise, Throwable cause) {
            if (!(promise instanceof VoidChannelPromise) && !promise.tryFailure(cause)) {
                logger.warn("Failed to mark a promise as failure because it's done already: {}", promise, cause);
            }
        }

        protected final void closeIfClosed() {
            if (isOpen()) {
                return;
            }
            close(voidPromise());
        }

        private void invokeLater(Runnable task) {
            try {
                // This method is used by outbound operation implementations to trigger an inbound event later.
                // They do not trigger an inbound event immediately because an outbound operation might have been
                // triggered by another inbound event handler method.  If fired immediately, the call stack
                // will look like this for example:
                //
                //   handlerA.inboundBufferUpdated() - (1) an inbound handler method closes a connection.
                //   -> handlerA.ctx.close()
                //      -> channel.unsafe.close()
                //         -> handlerA.channelInactive() - (2) another inbound handler method called while in (1) yet
                //
                // which means the execution of two inbound handler methods of the same handler overlap undesirably.
                eventLoop().execute(task);
            } catch (RejectedExecutionException e) {
                logger.warn("Can't invoke task later as EventLoop rejected it", e);
            }
        }

        /**
         * Appends the remote address to the message of the exceptions caused by connection attempt failure.
         */
        protected final Throwable annotateConnectException(Throwable cause, SocketAddress remoteAddress) {
            if (cause instanceof ConnectException) {
                return new AnnotatedConnectException((ConnectException) cause, remoteAddress);
            }
            if (cause instanceof NoRouteToHostException) {
                return new AnnotatedNoRouteToHostException((NoRouteToHostException) cause, remoteAddress);
            }
            if (cause instanceof SocketException) {
                return new AnnotatedSocketException((SocketException) cause, remoteAddress);
            }

            return cause;
        }

        /**
         * Prepares to close the {@link Channel}. If this method returns an {@link Executor}, the
         * caller must call the {@link Executor#execute(Runnable)} method with a task that calls
         * {@link #doClose()} on the returned {@link Executor}. If this method returns {@code null},
         * {@link #doClose()} must be called from the caller thread. (i.e. {@link EventLoop})
         */
        protected Executor prepareToClose() {
            return null;
        }
    }

    /**
     * Return {@code true} if the given {@link EventLoop} is compatible with this instance.
     */
    protected abstract boolean isCompatible(EventLoop loop);

    /**
     * Returns the {@link SocketAddress} which is bound locally.
     */
    protected abstract SocketAddress localAddress0();

    /**
     * Return the {@link SocketAddress} which the {@link Channel} is connected to.
     */
    protected abstract SocketAddress remoteAddress0();

    /**
     * Is called after the {@link Channel} is registered with its {@link EventLoop} as part of the register process.
     *
     * Sub-classes may override this method
     */
    protected void doRegister() throws Exception {
        // NOOP
    }

    /**
     * Bind the {@link Channel} to the {@link SocketAddress}
     */
    protected abstract void doBind(SocketAddress localAddress) throws Exception;

    /**
     * Disconnect this {@link Channel} from its remote peer
     */
    protected abstract void doDisconnect() throws Exception;

    /**
     * Close the {@link Channel}
     */
    protected abstract void doClose() throws Exception;

    /**
     * Called when conditions justify shutting down the output portion of the channel. This may happen if a write
     * operation throws an exception.
     */
    @UnstableApi
    protected void doShutdownOutput() throws Exception {
        doClose();
    }

    /**
     * Deregister the {@link Channel} from its {@link EventLoop}.
     *
     * Sub-classes may override this method
     */
    protected void doDeregister() throws Exception {
        // NOOP
    }

    /**
     * Schedule a read operation.
     */
    protected abstract void doBeginRead() throws Exception;

    /**
     * Flush the content of the given buffer to the remote peer.
     */
    protected abstract void doWrite(ChannelOutboundBuffer in) throws Exception;

    /**
     * Invoked when a new message is added to a {@link ChannelOutboundBuffer} of this {@link AbstractChannel}, so that
     * the {@link Channel} implementation converts the message to another. (e.g. heap buffer -> direct buffer)
     */
    protected Object filterOutboundMessage(Object msg) throws Exception {
        return msg;
    }

    /**
     * 一个已经关闭的promise 对象 无法调用相关方法
     */
    static final class CloseFuture extends DefaultChannelPromise {

        CloseFuture(AbstractChannel ch) {
            super(ch);
        }

        @Override
        public ChannelPromise setSuccess() {
            throw new IllegalStateException();
        }

        @Override
        public ChannelPromise setFailure(Throwable cause) {
            throw new IllegalStateException();
        }

        @Override
        public boolean trySuccess() {
            throw new IllegalStateException();
        }

        @Override
        public boolean tryFailure(Throwable cause) {
            throw new IllegalStateException();
        }

        boolean setClosed() {
            return super.trySuccess();
        }
    }

    private static final class AnnotatedConnectException extends ConnectException {

        private static final long serialVersionUID = 3901958112696433556L;

        AnnotatedConnectException(ConnectException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
            setStackTrace(exception.getStackTrace());
        }

        @Override
        public Throwable fillInStackTrace() {
            return this;
        }
    }

    private static final class AnnotatedNoRouteToHostException extends NoRouteToHostException {

        private static final long serialVersionUID = -6801433937592080623L;

        AnnotatedNoRouteToHostException(NoRouteToHostException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
            setStackTrace(exception.getStackTrace());
        }

        @Override
        public Throwable fillInStackTrace() {
            return this;
        }
    }

    private static final class AnnotatedSocketException extends SocketException {

        private static final long serialVersionUID = 3896743275010454039L;

        AnnotatedSocketException(SocketException exception, SocketAddress remoteAddress) {
            super(exception.getMessage() + ": " + remoteAddress);
            initCause(exception);
            setStackTrace(exception.getStackTrace());
        }

        @Override
        public Throwable fillInStackTrace() {
            return this;
        }
    }
}
