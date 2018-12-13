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
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Raises a {@link WriteTimeoutException} when a write operation cannot finish in a certain period of time.
 *
 * <pre>
 * // The connection is closed when a write operation cannot finish in 30 seconds.
 *
 * public class MyChannelInitializer extends {@link ChannelInitializer}&lt;{@link Channel}&gt; {
 *     public void initChannel({@link Channel} channel) {
 *         channel.pipeline().addLast("writeTimeoutHandler", new {@link WriteTimeoutHandler}(30);
 *         channel.pipeline().addLast("myHandler", new MyHandler());
 *     }
 * }
 *
 * // Handler should handle the {@link WriteTimeoutException}.
 * public class MyHandler extends {@link ChannelDuplexHandler} {
 *     {@code @Override}
 *     public void exceptionCaught({@link ChannelHandlerContext} ctx, {@link Throwable} cause)
 *             throws {@link Exception} {
 *         if (cause instanceof {@link WriteTimeoutException}) {
 *             // do something
 *         } else {
 *             super.exceptionCaught(ctx, cause);
 *         }
 *     }
 * }
 *
 * {@link ServerBootstrap} bootstrap = ...;
 * ...
 * bootstrap.childHandler(new MyChannelInitializer());
 * ...
 * </pre>
 * @see ReadTimeoutHandler
 * @see IdleStateHandler
 *
 * 一个写超时的对象 每次 写入都会生成一个写入超时对象 当到时时会触发 写入失败 并尝试关闭channel 如果 写入成功 回调该listener 将写入超时
 * 任务从链表中移除
 */
public class WriteTimeoutHandler extends ChannelOutboundHandlerAdapter {
    /**
     * 超时时间的 最小单位
     */
    private static final long MIN_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    /**
     * 该写任务 定义的 超时时间
     */
    private final long timeoutNanos;

    /**
     * A doubly-linked list to track all WriteTimeoutTasks
     * 超时任务对象
     */
    private WriteTimeoutTask lastTask;

    /**
     * 发送心跳包的逻辑是否被关闭
     */
    private boolean closed;

    /**
     * Creates a new instance.
     *
     * @param timeoutSeconds
     *        write timeout in seconds
     */
    public WriteTimeoutHandler(int timeoutSeconds) {
        this(timeoutSeconds, TimeUnit.SECONDS);
    }

    /**
     * Creates a new instance.
     *
     * @param timeout
     *        write timeout
     * @param unit
     *        the {@link TimeUnit} of {@code timeout}
     */
    public WriteTimeoutHandler(long timeout, TimeUnit unit) {
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        //设置写任务的 超时时间
        if (timeout <= 0) {
            timeoutNanos = 0;
        } else {
            timeoutNanos = Math.max(unit.toNanos(timeout), MIN_TIMEOUT_NANOS);
        }
    }

    /**
     * 每次写入一个 数据 都会生成一个 检测 超时的task 任务对象 并构成一个链表 触发超时 时 会抛出异常 当该handler 被移除时删除整个链表
     * @param ctx  选择指定的 ctx 对象调用 写入逻辑 其实写入都是从tail 往前 直到 head 后通过 unsafe 进行写入的
     *             传入的 ctx 就是 handler 自身所包装成的 ctx 就是让用户可以通过它获取 channel unsafe 等对象
     * @param msg 写入的消息体
     * @param promise
     * @throws Exception
     */
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        //存在写入超时时间 才有更新的 必要
        if (timeoutNanos > 0) {
            //获取 promise 如果是 DefalutPromise 就直接使用 否则 将VoidChannelPromise 转换成 DefalutPromise
            promise = promise.unvoid();
            //将自身生成一个 写入超时任务对象 为该写入动作 设置超时时限 超过 报异常 并关闭ctx对象(会通过调用链往下传递close)
            scheduleTimeout(ctx, promise);
        }
        //通过调用链往下传
        ctx.write(msg, promise);
    }

    /**
     * 当handler 被移除时 触发   传入的就是自身handler所包装成的ctx
     * 调用 是 这样 ctx.handle().handlerRemoved(ctx)
     * @param ctx
     * @throws Exception
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        //当本对象被删除是 删除对应的 task链
        WriteTimeoutTask task = lastTask;
        lastTask = null;
        while (task != null) {
            task.scheduledFuture.cancel(false);
            WriteTimeoutTask prev = task.prev;
            task.prev = null;
            task.next = null;
            task = prev;
        }
    }

    /**
     *
     * @param ctx 本handler 所包装成的 ctx
     * @param promise 该channel 所生成的 promise
     */
    private void scheduleTimeout(final ChannelHandlerContext ctx, final ChannelPromise promise) {
        // Schedule a timeout.
        // 生成一个超时任务对象
        final WriteTimeoutTask task = new WriteTimeoutTask(ctx, promise);
        // 指定时间后触发 写入超时任务 就是传播一个异常 以及关闭channel
        task.scheduledFuture = ctx.executor().schedule(task, timeoutNanos, TimeUnit.NANOSECONDS);

        if (!task.scheduledFuture.isDone()) {
            //将该任务 添加到链表中
            addWriteTimeoutTask(task);

            // Cancel the scheduled timeout if the flush promise is complete.
            // 如果写入正常完成就将超时对象删除
            promise.addListener(task);
        }
    }

    private void addWriteTimeoutTask(WriteTimeoutTask task) {
        if (lastTask != null) {
            lastTask.next = task;
            task.prev = lastTask;
        }
        lastTask = task;
    }

    /**
     * 链表操作 将该节点从链表中移除
     * @param task
     */
    private void removeWriteTimeoutTask(WriteTimeoutTask task) {
        if (task == lastTask) {
            // task is the tail of list
            assert task.next == null;
            lastTask = lastTask.prev;
            if (lastTask != null) {
                lastTask.next = null;
            }
        } else if (task.prev == null && task.next == null) {
            // Since task is not lastTask, then it has been removed or not been added.
            return;
        } else if (task.prev == null) {
            // task is the head of list and the list has at least 2 nodes
            task.next.prev = null;
        } else {
            task.prev.next = task.next;
            task.next.prev = task.prev;
        }
        task.prev = null;
        task.next = null;
    }

    /**
     * Is called when a write timeout was detected
     */
    protected void writeTimedOut(ChannelHandlerContext ctx) throws Exception {
        //未关闭的情况下 返回一个 超时异常
        if (!closed) {
            ctx.fireExceptionCaught(WriteTimeoutException.INSTANCE);
            //通过本ctx 传递 close 事件 直到 关闭jdkChannel
            ctx.close();
            closed = true;
        }
    }

    /**
     * 写入超时的 定时任务  该对象本身也作为一个监听器
     */
    private final class WriteTimeoutTask implements Runnable, ChannelFutureListener {

        /**
         * 本handler 包装成的ctx
         */
        private final ChannelHandlerContext ctx;
        /**
         * 本channel 创建的 promise
         */
        private final ChannelPromise promise;

        // WriteTimeoutTask is also a node of a doubly-linked list
        // 双向链表结构
        WriteTimeoutTask prev;
        WriteTimeoutTask next;

        //返回的定时结果
        ScheduledFuture<?> scheduledFuture;

        WriteTimeoutTask(ChannelHandlerContext ctx, ChannelPromise promise) {
            this.ctx = ctx;
            this.promise = promise;
        }

        @Override
        public void run() {
            // Was not written yet so issue a write timeout
            // The promise itself will be failed with a ClosedChannelException once the close() was issued
            // See https://github.com/netty/netty/issues/2159
            // 如果promise 没有因为异常而关闭
            if (!promise.isDone()) {
                try {
                    //传递一个 异常事件 并在handler链上传递一个 close 事件
                    writeTimedOut(ctx);
                } catch (Throwable t) {
                    ctx.fireExceptionCaught(t);
                }
            }
            //将该任务对象从链表中移除
            removeWriteTimeoutTask(this);
        }

        /**
         * 同时满足监听器的功能 写入完成后 将该task 从链表中移除
         * @param future  the source {@link Future} which called this callback
         * @throws Exception
         */
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            // scheduledFuture has already be set when reaching here
            // 将future 对象 关闭 并从链表中移除该对象
            scheduledFuture.cancel(false);
            removeWriteTimeoutTask(this);
        }
    }
}
