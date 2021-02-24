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
package io.netty.util.concurrent;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-thread singleton {@link EventExecutor}.  It starts the thread automatically and stops it when there is no
 * task pending in the task queue for 1 second.  Please note it is not scalable to schedule large number of tasks to
 * this executor; use a dedicated executor.
 * 父类只是定义了事件处理器的模板 执行任务的逻辑还没有实现 (execute())
 * 一个全局的事件循环组,采用单例模式
 */
public final class GlobalEventExecutor extends AbstractScheduledEventExecutor {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(GlobalEventExecutor.class);

    private static final long SCHEDULE_QUIET_PERIOD_INTERVAL = TimeUnit.SECONDS.toNanos(1);

    public static final GlobalEventExecutor INSTANCE = new GlobalEventExecutor();

    final BlockingQueue<Runnable> taskQueue = new LinkedBlockingQueue<Runnable>();
    final ScheduledFutureTask<Void> quietPeriodTask = new ScheduledFutureTask<Void>(
            this, Executors.<Void>callable(new Runnable() {
        @Override
        public void run() {
            // NOOP
        }
    }, null), ScheduledFutureTask.deadlineNanos(SCHEDULE_QUIET_PERIOD_INTERVAL), -SCHEDULE_QUIET_PERIOD_INTERVAL);

    // because the GlobalEventExecutor is a singleton, tasks submitted to it can come from arbitrary threads and this
    // can trigger the creation of a thread from arbitrary thread groups; for this reason, the thread factory must not
    // be sticky about its thread group
    // visible for testing
    final ThreadFactory threadFactory =
            new DefaultThreadFactory(DefaultThreadFactory.toPoolName(getClass()), false, Thread.NORM_PRIORITY, null);
    private final TaskRunner taskRunner = new TaskRunner();

    private final AtomicBoolean started = new AtomicBoolean();
    volatile Thread thread;

    /**
     * 这个全局事件循环对象是不可以被关闭的  当执行shutdown 会返回一个future
     */
    private final Future<?> terminationFuture = new FailedFuture<Object>(this, new UnsupportedOperationException());

    private GlobalEventExecutor() {
        //设置一个 不断执行的空任务
        scheduledTaskQueue().add(quietPeriodTask);
    }

    /**
     * Take the next {@link Runnable} from the task queue and so will block if no task is currently present.
     *
     * 拉取任务
     * @return {@code null} if the executor thread has been interrupted or waken up.
     */
    Runnable takeTask() {
        BlockingQueue<Runnable> taskQueue = this.taskQueue;
        for (;;) {
            // 除了要执行普通任务外 还尝试从定时任务队列中获取任务
            ScheduledFutureTask<?> scheduledTask = peekScheduledTask();
            if (scheduledTask == null) {
                Runnable task = null;
                try {
                    task = taskQueue.take();
                } catch (InterruptedException e) {
                    // Ignore
                }
                return task;
            } else {
                //存在定时任务的情况 判断是否到了执行时间
                long delayNanos = scheduledTask.delayNanos();
                Runnable task;
                //还没到时间 就按照这个时间在阻塞队列中拉取任务
                if (delayNanos > 0) {
                    try {
                        task = taskQueue.poll(delayNanos, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        // Waken up.
                        return null;
                    }
                } else {
                    task = taskQueue.poll();
                }

                // 在普通的任务队列中阻塞了一定时间后 应当有任务已经准备完成
                if (task == null) {
                    fetchFromScheduledTaskQueue();
                    task = taskQueue.poll();
                }

                if (task != null) {
                    return task;
                }
            }
        }
    }

    /**
     * 从定时任务队列将元素移动到 普通队列
     */
    private void fetchFromScheduledTaskQueue() {
        long nanoTime = AbstractScheduledEventExecutor.nanoTime();
        Runnable scheduledTask = pollScheduledTask(nanoTime);
        while (scheduledTask != null) {
            taskQueue.add(scheduledTask);
            scheduledTask = pollScheduledTask(nanoTime);
        }
    }

    /**
     * Return the number of tasks that are pending for processing.
     *
     * <strong>Be aware that this operation may be expensive as it depends on the internal implementation of the
     * SingleThreadEventExecutor. So use it was care!</strong>
     */
    public int pendingTasks() {
        return taskQueue.size();
    }

    /**
     * Add a task to the task queue, or throws a {@link RejectedExecutionException} if this instance was shutdown
     * before.
     */
    private void addTask(Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        taskQueue.add(task);
    }

    @Override
    public boolean inEventLoop(Thread thread) {
        return thread == this.thread;
    }

    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        return terminationFuture();
    }

    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    @Deprecated
    public void shutdown() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isShuttingDown() {
        return false;
    }

    @Override
    public boolean isShutdown() {
        return false;
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return false;
    }

    /**
     * Waits until the worker thread of this executor has no tasks left in its task queue and terminates itself.
     * Because a new worker thread will be started again when a new task is submitted, this operation is only useful
     * when you want to ensure that the worker thread is terminated <strong>after</strong> your application is shut
     * down and there's no chance of submitting a new task afterwards.
     *
     * @return {@code true} if and only if the worker thread has been terminated
     */
    public boolean awaitInactivity(long timeout, TimeUnit unit) throws InterruptedException {
        if (unit == null) {
            throw new NullPointerException("unit");
        }

        final Thread thread = this.thread;
        if (thread == null) {
            throw new IllegalStateException("thread was not started");
        }
        thread.join(unit.toMillis(timeout));
        return !thread.isAlive();
    }

    /**
     * 这个 机制也跟 eventLoop一样 必须在独占线程中才能执行
     * @param task
     */
    @Override
    public void execute(Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }

        addTask(task);
        if (!inEventLoop()) {
            startThread();
        }
    }

    /**
     * 首次从外部设置任务 会激活线程
     */
    private void startThread() {
        //已经启动就不用处理
        if (started.compareAndSet(false, true)) {
            final Thread t = threadFactory.newThread(taskRunner);
            // Set to null to ensure we not create classloader leaks by holds a strong reference to the inherited
            // classloader.
            // See:
            // - https://github.com/netty/netty/issues/7290
            // - https://bugs.openjdk.java.net/browse/JDK-7008595
            AccessController.doPrivileged(new PrivilegedAction<Void>() {
                @Override
                public Void run() {
                    t.setContextClassLoader(null);
                    return null;
                }
            });

            // Set the thread before starting it as otherwise inEventLoop() may return false and so produce
            // an assert error.
            // See https://github.com/netty/netty/issues/4357
            thread = t;
            //执行run 方法
            t.start();
        }
    }

    /**
     * 定义了事件循环的模板 当某个线程执行该任务时 就会不断的循环获取任务并处理
     */
    final class TaskRunner implements Runnable {
        @Override
        public void run() {
            for (;;) {
                //不断从任务队列中拉取任务并执行
                Runnable task = takeTask();
                if (task != null) {
                    try {
                        task.run();
                    } catch (Throwable t) {
                        logger.warn("Unexpected exception from the global event executor: ", t);
                    }

                    // 插入这个特殊的任务是为了 至少能够每隔1秒检测一次普通任务队列 因为队列会等待下一个任务的时间
                    if (task != quietPeriodTask) {
                        continue;
                    }
                }

                Queue<ScheduledFutureTask<?>> scheduledTaskQueue = GlobalEventExecutor.this.scheduledTaskQueue;
                // Terminate if there is no task in the queue (except the noop task).
                // 每当任务执行完后 本对象循环会结束 进而回收线程 当插入新任务时 又会分配新的线程
                if (taskQueue.isEmpty() && (scheduledTaskQueue == null || scheduledTaskQueue.size() == 1)) {
                    // Mark the current thread as stopped.
                    // The following CAS must always success and must be uncontended,
                    // because only one thread should be running at the same time.
                    //这里的 cas 一定会成功 因为这就是独占线程在执行
                    boolean stopped = started.compareAndSet(true, false);
                    assert stopped;

                    // Check if there are pending entries added by execute() or schedule*() while we do CAS above.
                    if (taskQueue.isEmpty() && (scheduledTaskQueue == null || scheduledTaskQueue.size() == 1)) {
                        // A) No new task was added and thus there's nothing to handle
                        //    -> safe to terminate because there's nothing left to do
                        // B) A new thread started and handled all the new tasks.
                        //    -> safe to terminate the new thread will take care the rest
                        break;
                    }

                    // There are pending tasks added again.
                    if (!started.compareAndSet(false, true)) {
                        // startThread() started a new thread and set 'started' to true.
                        // -> terminate this thread so that the new thread reads from taskQueue exclusively.
                        break;
                    }

                    // New tasks were added, but this worker was faster to set 'started' to true.
                    // i.e. a new worker thread was not started by startThread().
                    // -> keep this thread alive to handle the newly added entries.
                }
            }
        }
    }
}
