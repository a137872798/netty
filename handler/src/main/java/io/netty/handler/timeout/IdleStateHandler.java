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
package io.netty.handler.timeout;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.Channel.Unsafe;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Triggers an {@link IdleStateEvent} when a {@link Channel} has not performed
 * read, write, or both operation for a while.
 *
 * <h3>Supported idle states</h3>
 * <table border="1">
 * <tr>
 * <th>Property</th><th>Meaning</th>
 * </tr>
 * <tr>
 * <td>{@code readerIdleTime}</td>
 * <td>an {@link IdleStateEvent} whose state is {@link IdleState#READER_IDLE}
 *     will be triggered when no read was performed for the specified period of
 *     time.  Specify {@code 0} to disable.</td>
 * </tr>
 * <tr>
 * <td>{@code writerIdleTime}</td>
 * <td>an {@link IdleStateEvent} whose state is {@link IdleState#WRITER_IDLE}
 *     will be triggered when no write was performed for the specified period of
 *     time.  Specify {@code 0} to disable.</td>
 * </tr>
 * <tr>
 * <td>{@code allIdleTime}</td>
 * <td>an {@link IdleStateEvent} whose state is {@link IdleState#ALL_IDLE}
 *     will be triggered when neither read nor write was performed for the
 *     specified period of time.  Specify {@code 0} to disable.</td>
 * </tr>
 * </table>
 *
 * <pre>
 * // An example that sends a ping message when there is no outbound traffic
 * // for 30 seconds.  The connection is closed when there is no inbound traffic
 * // for 60 seconds.
 *
 * public class MyChannelInitializer extends {@link ChannelInitializer}&lt;{@link Channel}&gt; {
 *     {@code @Override}
 *     public void initChannel({@link Channel} channel) {
 *         channel.pipeline().addLast("idleStateHandler", new {@link IdleStateHandler}(60, 30, 0));
 *         channel.pipeline().addLast("myHandler", new MyHandler());
 *     }
 * }
 *
 * // Handler should handle the {@link IdleStateEvent} triggered by {@link IdleStateHandler}.
 * public class MyHandler extends {@link ChannelDuplexHandler} {
 *     {@code @Override}
 *     public void userEventTriggered({@link ChannelHandlerContext} ctx, {@link Object} evt) throws {@link Exception} {
 *         if (evt instanceof {@link IdleStateEvent}) {
 *             {@link IdleStateEvent} e = ({@link IdleStateEvent}) evt;
 *             if (e.state() == {@link IdleState}.READER_IDLE) {
 *                 ctx.close();
 *             } else if (e.state() == {@link IdleState}.WRITER_IDLE) {
 *                 ctx.writeAndFlush(new PingMessage());
 *             }
 *         }
 *     }
 * }
 *
 * {@link ServerBootstrap} bootstrap = ...;
 * ...
 * bootstrap.childHandler(new MyChannelInitializer());
 * ...
 * </pre>
 *
 * @see ReadTimeoutHandler
 * @see WriteTimeoutHandler
 *
 * 实现心跳检测的 核心 handler 对象
 */
public class IdleStateHandler extends ChannelDuplexHandler {
    private static final long MIN_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    // Not create a new ChannelFutureListener per write operation to reduce GC pressure.
    // 写操作的 回调对象 其实就是 更新 最后一次写入时间 这个时间决定了 是否发送心跳检测信息
    private final ChannelFutureListener writeListener = new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            //获取纳秒级别的 写入时间
            lastWriteTime = ticksInNanos();
            //这里 设置了 第一次 写入 以及 第一次 所有空闲事件为true
            firstWriterIdleEvent = firstAllIdleEvent = true;
        }
    };

    /**
     * 代表 当 感知到 handlerAdd事件时 是否 记录当前 待flushed 数量 以及 该 flushed 的hash值
     */
    private final boolean observeOutput;
    /**
     * 读事件 的 间隔时间
     */
    private final long readerIdleTimeNanos;
    /**
     * 写事件 的 间隔时间
     */
    private final long writerIdleTimeNanos;
    /**
     * 读/写 的 间隔时间 只要有一个 时间可以触发 就触发
     */
    private final long allIdleTimeNanos;

    /**
     * 读事件的 定时任务
     */
    private ScheduledFuture<?> readerIdleTimeout;
    /**
     * 最后一次读取的时间间隔
     */
    private long lastReadTime;
    /**
     * 默认 第一次 读取事件为true
     */
    private boolean firstReaderIdleEvent = true;

    /**
     * 写事件的 定时任务
     */
    private ScheduledFuture<?> writerIdleTimeout;
    /**
     * 最后一次写入的时间间隔
     */
    private long lastWriteTime;
    /**
     * 默认 第一次 写入事件为true
     */
    private boolean firstWriterIdleEvent = true;

    /**
     * 针对2种事件的 间隔时间
     */
    private ScheduledFuture<?> allIdleTimeout;
    private boolean firstAllIdleEvent = true;

    /**
     * 应该是 定时任务的 状态 1代表 定时任务 启动 2 代表定时任务 取消
     */
    private byte state; // 0 - none, 1 - initialized, 2 - destroyed
    /**
     * 是否正在读取状态
     */
    private boolean reading;

    /**
     * 当 调用 outputisChange 就会修改这个值
     */
    private long lastChangeCheckTimeStamp;
    /**
     * 首个 待flushed 的 entry 的 hash 值
     */
    private int lastMessageHashCode;
    /**
     * channel 中等待 flushed 的 数量
     */
    private long lastPendingWriteBytes;

    /**
     * Creates a new instance firing {@link IdleStateEvent}s.
     *
     * @param readerIdleTimeSeconds
     *        an {@link IdleStateEvent} whose state is {@link IdleState#READER_IDLE}
     *        will be triggered when no read was performed for the specified
     *        period of time.  Specify {@code 0} to disable.
     * @param writerIdleTimeSeconds
     *        an {@link IdleStateEvent} whose state is {@link IdleState#WRITER_IDLE}
     *        will be triggered when no write was performed for the specified
     *        period of time.  Specify {@code 0} to disable.
     * @param allIdleTimeSeconds
     *        an {@link IdleStateEvent} whose state is {@link IdleState#ALL_IDLE}
     *        will be triggered when neither read nor write was performed for
     *        the specified period of time.  Specify {@code 0} to disable.
     */
    public IdleStateHandler(
            int readerIdleTimeSeconds,
            int writerIdleTimeSeconds,
            int allIdleTimeSeconds) {

        //根据 读写 时间间隔 来创建 对象
        this(readerIdleTimeSeconds, writerIdleTimeSeconds, allIdleTimeSeconds,
             TimeUnit.SECONDS);
    }

    /**
     * @see #IdleStateHandler(boolean, long, long, long, TimeUnit)
     */
    public IdleStateHandler(
            long readerIdleTime, long writerIdleTime, long allIdleTime,
            TimeUnit unit) {
        this(false, readerIdleTime, writerIdleTime, allIdleTime, unit);
    }

    /**
     * Creates a new instance firing {@link IdleStateEvent}s.
     *
     * @param observeOutput
     *        whether or not the consumption of {@code bytes} should be taken into
     *        consideration when assessing write idleness. The default is {@code false}.
     * @param readerIdleTime
     *        an {@link IdleStateEvent} whose state is {@link IdleState#READER_IDLE}
     *        will be triggered when no read was performed for the specified
     *        period of time.  Specify {@code 0} to disable.
     * @param writerIdleTime
     *        an {@link IdleStateEvent} whose state is {@link IdleState#WRITER_IDLE}
     *        will be triggered when no write was performed for the specified
     *        period of time.  Specify {@code 0} to disable.
     * @param allIdleTime
     *        an {@link IdleStateEvent} whose state is {@link IdleState#ALL_IDLE}
     *        will be triggered when neither read nor write was performed for
     *        the specified period of time.  Specify {@code 0} to disable.
     * @param unit
     *        the {@link TimeUnit} of {@code readerIdleTime},
     *        {@code writeIdleTime}, and {@code allIdleTime}
     */
    public IdleStateHandler(boolean observeOutput,
            long readerIdleTime, long writerIdleTime, long allIdleTime,
            TimeUnit unit) {
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        //根据传入 的参数 判断是否要 输出 信息 默认为false
        this.observeOutput = observeOutput;

        if (readerIdleTime <= 0) {
            readerIdleTimeNanos = 0;
        } else {
            //小于1 纳秒的 使用1 纳秒
            readerIdleTimeNanos = Math.max(unit.toNanos(readerIdleTime), MIN_TIMEOUT_NANOS);
        }
        if (writerIdleTime <= 0) {
            writerIdleTimeNanos = 0;
        } else {
            writerIdleTimeNanos = Math.max(unit.toNanos(writerIdleTime), MIN_TIMEOUT_NANOS);
        }
        if (allIdleTime <= 0) {
            allIdleTimeNanos = 0;
        } else {
            allIdleTimeNanos = Math.max(unit.toNanos(allIdleTime), MIN_TIMEOUT_NANOS);
        }
    }

    /**
     * Return the readerIdleTime that was given when instance this class in milliseconds.
     *
     */
    public long getReaderIdleTimeInMillis() {
        return TimeUnit.NANOSECONDS.toMillis(readerIdleTimeNanos);
    }

    /**
     * Return the writerIdleTime that was given when instance this class in milliseconds.
     *
     */
    public long getWriterIdleTimeInMillis() {
        return TimeUnit.NANOSECONDS.toMillis(writerIdleTimeNanos);
    }

    /**
     * Return the allIdleTime that was given when instance this class in milliseconds.
     *
     */
    public long getAllIdleTimeInMillis() {
        return TimeUnit.NANOSECONDS.toMillis(allIdleTimeNanos);
    }

    /**
     * 当handler 自身 所包装成的 ctx 被添加到pipeline 上时 触发 并且是不往下传播的
     * @param ctx 就是本handler 包装成的 ctx对象
     * @throws Exception
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        //初始化本对象 这里必须要 已经注册的状态  如果是add先触发 是无法对该ctx 做初始化的
        if (ctx.channel().isActive() && ctx.channel().isRegistered()) {
            // channelActive() event has been fired already, which means this.channelActive() will
            // not be invoked. We have to initialize here instead.
            // 根据需要 记录 flushed 信息 并 设置3个定时任务
            initialize(ctx);
        } else {
            // channelActive() event has not been fired yet.  this.channelActive() will be invoked
            // and initialization will occur there.
        }
    }

    /**
     * 当本对象从 pipeline 中移除时触发
     * @param ctx
     * @throws Exception
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        //关闭3个定时任务
        destroy();
    }

    /**
     * 当 channel 注册到selector 时 触发
     * @param ctx
     * @throws Exception
     */
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        // Initialize early if channel is active already.
        if (ctx.channel().isActive()) {
            initialize(ctx);
        }
        super.channelRegistered(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // This method will be invoked only if this handler was added
        // before channelActive() event is fired.  If a user adds this handler
        // after the channelActive() event, initialize() will be called by beforeAdd().
        initialize(ctx);
        super.channelActive(ctx);
    }

    /**
     * channel 无效时 销毁定时任务
     * @param ctx
     * @throws Exception
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        destroy();
        super.channelInactive(ctx);
    }

    /**
     * 当读取到 消息时
     * @param ctx
     * @param msg
     * @throws Exception
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        //代表正在 读取
        if (readerIdleTimeNanos > 0 || allIdleTimeNanos > 0) {
            reading = true;
            firstReaderIdleEvent = firstAllIdleEvent = true;
        }
        ctx.fireChannelRead(msg);
    }

    //读取完成时 更新最后读取的时间 并 修改 reading 状态
    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        if ((readerIdleTimeNanos > 0 || allIdleTimeNanos > 0) && reading) {
            lastReadTime = ticksInNanos();
            reading = false;
        }
        ctx.fireChannelReadComplete();
    }

    /**
     * 在写的 同时 还会设置一个 监听器对象 因为netty 没有writeComplete 事件
     * @param ctx
     * @param msg
     * @param promise
     * @throws Exception
     */
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        // Allow writing with void promise if handler is only configured for read timeout events.
        if (writerIdleTimeNanos > 0 || allIdleTimeNanos > 0) {
            //当写入完成时  修改最后的 写入时间  并将 first 变成true
            ctx.write(msg, promise.unvoid()).addListener(writeListener);
        } else {
            ctx.write(msg, promise);
        }
    }

    /**
     * 为本对象 包装成的 ctx 初始化
     * @param ctx 创建的新的 handler 包装成的 ctx
     */
    private void initialize(ChannelHandlerContext ctx) {
        // Avoid the case where destroy() is called before scheduling timeouts.
        // See: https://github.com/netty/netty/issues/143
        // 判断该心跳检测对象是否还处于启动状态
        switch (state) {
        case 1:
        case 2:
            return;
        }

        state = 1;
        //这里是记录当前 channel 需要 flush 的 entry 数量 以及 flushed entry 的hash值
        initOutputChanged(ctx);

        //记录当前 读写 纳秒
        lastReadTime = lastWriteTime = ticksInNanos();
        //将ctx 分别包装成3个对象的对应3个 超时检测对象 并设置定时任务
        if (readerIdleTimeNanos > 0) {
            readerIdleTimeout = schedule(ctx, new ReaderIdleTimeoutTask(ctx),
                    readerIdleTimeNanos, TimeUnit.NANOSECONDS);
        }
        if (writerIdleTimeNanos > 0) {
            writerIdleTimeout = schedule(ctx, new WriterIdleTimeoutTask(ctx),
                    writerIdleTimeNanos, TimeUnit.NANOSECONDS);
        }
        if (allIdleTimeNanos > 0) {
            allIdleTimeout = schedule(ctx, new AllIdleTimeoutTask(ctx),
                    allIdleTimeNanos, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * This method is visible for testing!
     * 获取 纳秒级别的 时间
     */
    long ticksInNanos() {
        return System.nanoTime();
    }

    /**
     * This method is visible for testing!
     * 就是 通过eventloop 对象 设置 定时任务 这里明确使用了ctx的 executor 对象 注意 每个ctx 可以有自己的 executor对象
     * 只有当该对象没有设置的 时候才使用channel 的eventloop对象 所以在handlerAdd才会有 add_pending 属性
     */
    ScheduledFuture<?> schedule(ChannelHandlerContext ctx, Runnable task, long delay, TimeUnit unit) {
        //这些定时任务 都是单次任务 每次触发后 根据 逻辑判断 设置一个 时间更加合理的单次任务 传递下去
        return ctx.executor().schedule(task, delay, unit);
    }

    /**
     * 当 该handler 被移除时触发
     */
    private void destroy() {
        //修改状态为无效
        state = 2;

        //关闭3个定时任务

        if (readerIdleTimeout != null) {
            readerIdleTimeout.cancel(false);
            readerIdleTimeout = null;
        }
        if (writerIdleTimeout != null) {
            writerIdleTimeout.cancel(false);
            writerIdleTimeout = null;
        }
        if (allIdleTimeout != null) {
            allIdleTimeout.cancel(false);
            allIdleTimeout = null;
        }
    }

    /**
     * Is called when an {@link IdleStateEvent} should be fired. This implementation calls
     * {@link ChannelHandlerContext#fireUserEventTriggered(Object)}.
     * 触发 用户自定义的 handler 如果用户 有设置该 handler 并且在自定义中有捕获 心跳事件相关handler
     *
     * 存在一个 ReadTimeoutHandler 重写了这个方法 并返回一个超时异常 已经 触发 channelClose事件
     */
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
        ctx.fireUserEventTriggered(evt);
    }

    /**
     * Returns a {@link IdleStateEvent}.
     * 创建一个新的 心跳检测事件类型用来触发 对端的 userTrigger
     */
    protected IdleStateEvent newIdleStateEvent(IdleState state, boolean first) {
        switch (state) {
            //这里根据是否是第一次 发送 又将 类型细分
            case ALL_IDLE:
                return first ? IdleStateEvent.FIRST_ALL_IDLE_STATE_EVENT : IdleStateEvent.ALL_IDLE_STATE_EVENT;
            case READER_IDLE:
                return first ? IdleStateEvent.FIRST_READER_IDLE_STATE_EVENT : IdleStateEvent.READER_IDLE_STATE_EVENT;
            case WRITER_IDLE:
                return first ? IdleStateEvent.FIRST_WRITER_IDLE_STATE_EVENT : IdleStateEvent.WRITER_IDLE_STATE_EVENT;
            default:
                throw new IllegalArgumentException("Unhandled: state=" + state + ", first=" + first);
        }
    }

    /**
     * @see #hasOutputChanged(ChannelHandlerContext, boolean)
     * 当输出的 handler 对象发生改变时
     */
    private void initOutputChanged(ChannelHandlerContext ctx) {
        //如果需要观察 输出信息
        if (observeOutput) {
            Channel channel = ctx.channel();
            Unsafe unsafe = channel.unsafe();
            ChannelOutboundBuffer buf = unsafe.outboundBuffer();

            if (buf != null) {
                //返回当前准备 flushed 的 entry 的 hashCode 值 如果该方法传入的是null 不会有影响 会返回0
                lastMessageHashCode = System.identityHashCode(buf.current());
                //记录等待 flush 的 数据
                lastPendingWriteBytes = buf.totalPendingWriteBytes();
            }
        }
    }

    /**
     * Returns {@code true} if and only if the {@link IdleStateHandler} was constructed
     * with {@link #observeOutput} enabled and there has been an observed change in the
     * {@link ChannelOutboundBuffer} between two consecutive calls of this method.
     *
     * https://github.com/netty/netty/issues/6150
     *
     * 这里应该是 判断 是否 正好有数据在进行flush
     * @param first 代表是否是 第一次触发 如果没有记录 即将flush 的数据信息 这里就直接返回false
     */
    private boolean hasOutputChanged(ChannelHandlerContext ctx, boolean first) {
        //如果 开启了 观察是否有正在flush 的数据
        if (observeOutput) {

            // We can take this shortcut if the ChannelPromises that got passed into write()
            // appear to complete. It indicates "change" on message level and we simply assume
            // that there's change happening on byte level. If the user doesn't observe channel
            // writability events then they'll eventually OOME and there's clearly a different
            // problem and idleness is least of their concerns.
            // 第一次默认是 0  每次都会记录上次的 写时间  如果写时间发生过变化就是 有新的数据写过了
            if (lastChangeCheckTimeStamp != lastWriteTime) {
                lastChangeCheckTimeStamp = lastWriteTime;

                // But this applies only if it's the non-first call.
                // 因为每次更新 first 都会重新设置为true 这个可以看作是在一个 超时时候后 触发了几次 只有第一次生效
                if (!first) {
                    return true;
                }
            }

            //获取 掌控 write 和 flush方法实现的 ChannelOutboundBuffer 对象
            Channel channel = ctx.channel();
            Unsafe unsafe = channel.unsafe();
            ChannelOutboundBuffer buf = unsafe.outboundBuffer();

            if (buf != null) {
                int messageHashCode = System.identityHashCode(buf.current());
                long pendingWriteBytes = buf.totalPendingWriteBytes();

                //代表 之前的数据被 flush 出去了 也就是完成了 真正的 到对端的write(flush) 操作
                if (messageHashCode != lastMessageHashCode || pendingWriteBytes != lastPendingWriteBytes) {
                    lastMessageHashCode = messageHashCode;
                    lastPendingWriteBytes = pendingWriteBytes;

                    if (!first) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * 一个抽象的 时间间隔任务对象
     */
    private abstract static class AbstractIdleTask implements Runnable {

        /**
         * 为什么要关联一个 ctx 对象???
         */
        private final ChannelHandlerContext ctx;

        AbstractIdleTask(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void run() {
            //如果 通道已经关闭 就返回
            if (!ctx.channel().isOpen()) {
                return;
            }

            //实际的 调用逻辑委托到子类实现
            run(ctx);
        }

        protected abstract void run(ChannelHandlerContext ctx);
    }

    /**
     * 将Ctx 节点对象封装成 读事件心跳检测对象
     */
    private final class ReaderIdleTimeoutTask extends AbstractIdleTask {

        ReaderIdleTimeoutTask(ChannelHandlerContext ctx) {
            super(ctx);
        }

        /**
         * 该任务 执行时的 逻辑  当触发时 代表 已经 到了一个检测时限了
         * @param ctx
         */
        @Override
        protected void run(ChannelHandlerContext ctx) {
            //获取 读事件检测的时间间隔
            long nextDelay = readerIdleTimeNanos;
            //如果不是正在处理读事件的状态
            if (!reading) {
                //时间间隔 减去 距离上次读取 的 时间差  由这个值 的 正负决定是否发起 心跳检测请求
                nextDelay -= ticksInNanos() - lastReadTime;
            }

            //为负代表需要 发起心跳包
            if (nextDelay <= 0) {
                // Reader is idle - set a new timeout and notify the callback.
                // 设置下次检测的 新周期
                readerIdleTimeout = schedule(ctx, this, readerIdleTimeNanos, TimeUnit.NANOSECONDS);

                //判断是否是 首次发起心跳检测事件 在一个检测周期的 第一次触发都会是true
                //如果在2次读取之间 触发了2次 读检测 就会变成false
                boolean first = firstReaderIdleEvent;
                //将首次发送的标识设置为false 代表触发了一次
                firstReaderIdleEvent = false;

                try {
                    //获取对应的心跳检测类型
                    IdleStateEvent event = newIdleStateEvent(IdleState.READER_IDLE, first);
                    //将需要 进行心跳检测的 事件往下传递 也就是 发送并不是在这一层做的
                    channelIdle(ctx, event);
                } catch (Throwable t) {
                    ctx.fireExceptionCaught(t);
                }
            } else {
                // Read occurred before the timeout - set a new timeout with shorter delay.
                // 更新时间间隔 在合适的时间 判断 是否需要 心跳检测 下次 又会获取新的 间隔时间
                readerIdleTimeout = schedule(ctx, this, nextDelay, TimeUnit.NANOSECONDS);
            }
        }
    }

    /**
     * 将ctx 封装成 写事件 心跳检测对象
     */
    private final class WriterIdleTimeoutTask extends AbstractIdleTask {

        WriterIdleTimeoutTask(ChannelHandlerContext ctx) {
            super(ctx);
        }

        /**
         * 定时任务的 实际逻辑  如果时间没到 那么 又是怎么触发的呢???
         * @param ctx
         */
        @Override
        protected void run(ChannelHandlerContext ctx) {

            //获取 最后的写入时间
            long lastWriteTime = IdleStateHandler.this.lastWriteTime;
            //通过写事件发送心跳的时间间隔 和距离上次 最后写入时间 计算下次需要 触发该事件的 时间
            long nextDelay = writerIdleTimeNanos - (ticksInNanos() - lastWriteTime);
            if (nextDelay <= 0) {
                // Writer is idle - set a new timeout and notify the callback.
                // 创建一个 新间隔的定时任务
                writerIdleTimeout = schedule(ctx, this, writerIdleTimeNanos, TimeUnit.NANOSECONDS);

                boolean first = firstWriterIdleEvent;
                firstWriterIdleEvent = false;

                try {
                    //如果刷盘的数据跟上次相比发生了变化 就代表写出数据了 如果在一次写的周期内触发2次这个 就判定 需要发送心跳包
                    //既然能触发2次肯定也是到了2个周期了 肯定超时了
                    if (hasOutputChanged(ctx, first)) {
                        return;
                    }

                    //创建 写心跳包的事件 并传递到 用户事件  用户 自己根据 情况选择是否发送 心跳包
                    IdleStateEvent event = newIdleStateEvent(IdleState.WRITER_IDLE, first);
                    channelIdle(ctx, event);
                } catch (Throwable t) {
                    ctx.fireExceptionCaught(t);
                }
            } else {
                // Write occurred before the timeout - set a new timeout with shorter delay.
                writerIdleTimeout = schedule(ctx, this, nextDelay, TimeUnit.NANOSECONDS);
            }
        }
    }

    /**
     * 2种事件中 任意一种到时 就触发
     */
    private final class AllIdleTimeoutTask extends AbstractIdleTask {

        AllIdleTimeoutTask(ChannelHandlerContext ctx) {
            super(ctx);
        }

        /**
         * 定时 触发的 任务
         * @param ctx
         */
        @Override
        protected void run(ChannelHandlerContext ctx) {

            long nextDelay = allIdleTimeNanos;
            //如果是正在读取 就肯定不用发送心跳了 直接在 下一个 需要判定的时间 再次触发任务
            if (!reading) {
                //取较晚的 时间
                nextDelay -= ticksInNanos() - Math.max(lastReadTime, lastWriteTime);
            }
            if (nextDelay <= 0) {
                // Both reader and writer are idle - set a new timeout and
                // notify the callback.
                allIdleTimeout = schedule(ctx, this, allIdleTimeNanos, TimeUnit.NANOSECONDS);

                boolean first = firstAllIdleEvent;
                firstAllIdleEvent = false;

                try {
                    //如果检测到  马上要进行flush 了 也不 发送心跳包了
                    if (hasOutputChanged(ctx, first)) {
                        return;
                    }

                    IdleStateEvent event = newIdleStateEvent(IdleState.ALL_IDLE, first);
                    channelIdle(ctx, event);
                } catch (Throwable t) {
                    ctx.fireExceptionCaught(t);
                }
            } else {
                // Either read or write occurred before the timeout - set a new
                // timeout with shorter delay.
                allIdleTimeout = schedule(ctx, this, nextDelay, TimeUnit.NANOSECONDS);
            }
        }
    }
}
