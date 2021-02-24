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

import io.netty.util.internal.DefaultPriorityQueue;
import io.netty.util.internal.PriorityQueueNode;

import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@SuppressWarnings("ComparableImplementedButEqualsNotOverridden")
/**
 * 定时任务对象  本身实现了优先队列节点接口
 */
final class ScheduledFutureTask<V> extends PromiseTask<V> implements ScheduledFuture<V>, PriorityQueueNode {
    /**
     * 为每个 任务设置唯一id
     */
    private static final AtomicLong nextTaskId = new AtomicLong();

    private static final long START_TIME = System.nanoTime();

    /**
     * 每次获取的 就是时间间隔
     * @return
     */
    static long nanoTime() {
        return System.nanoTime() - START_TIME;
    }

    /**
     * @param delay
     * @return
     */
    static long deadlineNanos(long delay) {
        long deadlineNanos = nanoTime() + delay;
        // Guard against overflow 超过long 最大值
        return deadlineNanos < 0 ? Long.MAX_VALUE : deadlineNanos;
    }

    private final long id = nextTaskId.getAndIncrement();
    /**
     * 下次任务的执行时间
     */
    private long deadlineNanos;
    /* 0 - no repeat, >0 - repeat at fixed rate, <0 - repeat with fixed delay
    *  0代表单次任务 正数代表以指定时间间隔执行 负数是 按执行 延迟 正好对应JDK 的3种模式
    * */
    private final long periodNanos;

    private int queueIndex = INDEX_NOT_IN_QUEUE;

    ScheduledFutureTask(
            AbstractScheduledEventExecutor executor,
            Runnable runnable, V result, long nanoTime) {

        this(executor, toCallable(runnable, result), nanoTime);
    }

    ScheduledFutureTask(
            AbstractScheduledEventExecutor executor,
            Callable<V> callable, long nanoTime, long period) {

        super(executor, callable);
        if (period == 0) {
            throw new IllegalArgumentException("period: 0 (expected: != 0)");
        }
        deadlineNanos = nanoTime;
        periodNanos = period;
    }

    ScheduledFutureTask(
            AbstractScheduledEventExecutor executor,
            Callable<V> callable, long nanoTime) {

        super(executor, callable);
        deadlineNanos = nanoTime;
        periodNanos = 0;
    }

    @Override
    protected EventExecutor executor() {
        return super.executor();
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    public long delayNanos() {
        return Math.max(0, deadlineNanos() - nanoTime());
    }

    public long delayNanos(long currentTimeNanos) {
        return Math.max(0, deadlineNanos() - (currentTimeNanos - START_TIME));
    }

    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(delayNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * 以触发时间作为排序条件
     * @param o
     * @return
     */
    @Override
    public int compareTo(Delayed o) {
        if (this == o) {
            return 0;
        }

        ScheduledFutureTask<?> that = (ScheduledFutureTask<?>) o;
        long d = deadlineNanos() - that.deadlineNanos();
        if (d < 0) {
            return -1;
        } else if (d > 0) {
            return 1;
        } else if (id < that.id) {
            return -1;
        } else if (id == that.id) {
            throw new Error();
        } else {
            return 1;
        }
    }

    /**
     * 执行定时任务
     */
    @Override
    public void run() {
        assert executor().inEventLoop();
        try {
            //如果是单次任务
            if (periodNanos == 0) {
                //设置成不可中断 如果设置失败 代表任务已经在之前被中断了 就不需要再执行了
                if (setUncancellableInternal()) {
                    //执行任务并获取结果
                    V result = task.call();
                    //设置成功结果
                    setSuccessInternal(result);
                }
            } else {
                // check if is done as it may was cancelled
                if (!isCancelled()) {
                    //委托执行
                    task.call();
                    if (!executor().isShutdown()) {
                        //计算下次 触发时间后 重新加入到任务队列
                        long p = periodNanos;
                        if (p > 0) {
                            deadlineNanos += p;
                        } else {
                            deadlineNanos = nanoTime() - p;
                        }
                        if (!isCancelled()) {
                            // scheduledTaskQueue can never be null as we lazy init it before submit the task!
                            Queue<ScheduledFutureTask<?>> scheduledTaskQueue =
                                    ((AbstractScheduledEventExecutor) executor()).scheduledTaskQueue;
                            assert scheduledTaskQueue != null;
                            scheduledTaskQueue.add(this);
                        }
                    }
                }
            }
        } catch (Throwable cause) {
            setFailureInternal(cause);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        //关闭并设置在过程种能否被中断
        boolean canceled = super.cancel(mayInterruptIfRunning);
        //关闭成功的情况下 从任务队列中移除
        if (canceled) {
            ((AbstractScheduledEventExecutor) executor()).removeScheduled(this);
        }
        return canceled;
    }

    /**
     * 关闭 却不移除 对应到在run() 中判断是否被关闭
     * @param mayInterruptIfRunning
     * @return
     */
    boolean cancelWithoutRemove(boolean mayInterruptIfRunning) {
        return super.cancel(mayInterruptIfRunning);
    }

    @Override
    protected StringBuilder toStringBuilder() {
        StringBuilder buf = super.toStringBuilder();
        buf.setCharAt(buf.length() - 1, ',');

        return buf.append(" id: ")
                  .append(id)
                  .append(", deadline: ")
                  .append(deadlineNanos)
                  .append(", period: ")
                  .append(periodNanos)
                  .append(')');
    }

    /**
     * 获取 对于定时任务队列中的 下标  这个值如果可以自己设置还有意义吗 还是在什么地方需要判断这个
     * @param queue
     * @return
     */
    @Override
    public int priorityQueueIndex(DefaultPriorityQueue<?> queue) {
        return queueIndex;
    }

    /**
     * 这个是用于 清除优先队列中 该任务的 因为无效了 index 要设置为-1
     * @param queue The queue for which the index is being set.
     * @param i The index as used by {@link DefaultPriorityQueue}.
     *
     */
    @Override
    public void priorityQueueIndex(DefaultPriorityQueue<?> queue, int i) {
        queueIndex = i;
    }
}
