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
package io.netty.util.concurrent;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base class for {@link EventExecutor} implementations.
 * AbstractExecutorService 只是定义了提交任务的模板  指定提交任务后返回一个future对象 使用者可以通过监听future得到结果
 * ThreadPoolExecutorService代表基于线程池实现任务执行者
 */
public abstract class AbstractEventExecutor extends AbstractExecutorService implements EventExecutor {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractEventExecutor.class);

    /**
     * 优雅关闭的时间
     */
    static final long DEFAULT_SHUTDOWN_QUIET_PERIOD = 2;
    /**
     * shutdown 超时时间
     */
    static final long DEFAULT_SHUTDOWN_TIMEOUT = 15;

    /**
     * 事件循环组
     */
    private final EventExecutorGroup parent;

    private final Collection<EventExecutor> selfCollection = Collections.<EventExecutor>singleton(this);

    protected AbstractEventExecutor() {
        this(null);
    }

    protected AbstractEventExecutor(EventExecutorGroup parent) {
        this.parent = parent;
    }

    @Override
    public EventExecutorGroup parent() {
        return parent;
    }

    @Override
    public EventExecutor next() {
        return this;
    }

    @Override
    public boolean inEventLoop() {
        return inEventLoop(Thread.currentThread());
    }

    @Override
    public Iterator<EventExecutor> iterator() {
        return selfCollection.iterator();
    }

    /**
     * 这里 重写了 优雅关闭 传入指定参数 又回到下层的 SingleThreadEventExecutor 执行
     * @return
     */
    @Override
    public Future<?> shutdownGracefully() {
        return shutdownGracefully(DEFAULT_SHUTDOWN_QUIET_PERIOD, DEFAULT_SHUTDOWN_TIMEOUT, TimeUnit.SECONDS);
    }

    /**
     * @deprecated {@link #shutdownGracefully(long, long, TimeUnit)} or {@link #shutdownGracefully()} instead.
     */
    @Override
    @Deprecated
    public abstract void shutdown();

    /**
     * @deprecated {@link #shutdownGracefully(long, long, TimeUnit)} or {@link #shutdownGracefully()} instead.
     */
    @Override
    @Deprecated
    public List<Runnable> shutdownNow() {
        shutdown();
        return Collections.emptyList();
    }

    /**
     * 返回一个 默认的promise对象
     * @param <V>
     * @return
     */
    @Override
    public <V> Promise<V> newPromise() {
        return new DefaultPromise<V>(this);
    }

    /**
     * 返回一个可以设置进度的promise对象  根据当前进度不同 监听器会做不同的处理
     * @param <V>
     * @return
     */
    @Override
    public <V> ProgressivePromise<V> newProgressivePromise() {
        return new DefaultProgressivePromise<V>(this);
    }

    /**
     * 返回future的同时  这个future已经设置了成功的结果
     * @param result
     * @param <V>
     * @return
     */
    @Override
    public <V> Future<V> newSucceededFuture(V result) {
        return new SucceededFuture<V>(this, result);
    }

    /**
     * @param cause
     * @param <V>
     * @return
     */
    @Override
    public <V> Future<V> newFailedFuture(Throwable cause) {
        return new FailedFuture<V>(this, cause);
    }

    /**
     * 把runnable 封装成 future 对象
     * @param task
     * @return
     */
    @Override
    public Future<?> submit(Runnable task) {
        return (Future<?>) super.submit(task);
    }

    /**
     * 指定返回类型的 future 对象
     * @param task
     * @param result
     * @param <T>
     * @return
     */
    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return (Future<T>) super.submit(task, result);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return (Future<T>) super.submit(task);
    }

    // 修改父类返回的future对象 增加了添加回调钩子的接口  原本future只能通过get() 阻塞等待结果

    /**
     * @param runnable
     * @param value
     * @param <T>
     * @return
     */
    @Override
    protected final <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new PromiseTask<T>(this, runnable, value);
    }

    @Override
    protected final <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new PromiseTask<T>(this, callable);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay,
                                       TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    /**
     * Try to execute the given {@link Runnable} and just log if it throws a {@link Throwable}.
     *
     * 也就是忽略抛出的异常
     */
    protected static void safeExecute(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            logger.warn("A task raised an exception. Task: {}", task, t);
        }
    }
}
