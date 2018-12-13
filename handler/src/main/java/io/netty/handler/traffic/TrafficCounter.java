/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.traffic;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;


/**
 * Counts the number of read and written bytes for rate-limiting traffic.
 * <p>
 * It computes the statistics for both inbound and outbound traffic periodically at the given
 * {@code checkInterval}, and calls the {@link AbstractTrafficShapingHandler#doAccounting(TrafficCounter)} method back.
 * If the {@code checkInterval} is {@code 0}, no accounting will be done and statistics will only be computed at each
 * receive or write operation.
 * </p>
 *
 * 流量计数器对象
 */
public class TrafficCounter {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(TrafficCounter.class);

    /**
     * @return the time in ms using nanoTime, so not real EPOCH time but elapsed time in ms.
     */
    public static long milliSecondFromNano() {
        return System.nanoTime() / 1000000;
    }

    /**
     * Current written bytes
     */
    private final AtomicLong currentWrittenBytes = new AtomicLong();

    /**
     * Current read bytes
     */
    private final AtomicLong currentReadBytes = new AtomicLong();

    /**
     * Last writing time during current check interval
     */
    private long writingTime;

    /**
     * Last reading delay during current check interval
     */
    private long readingTime;

    /**
     * Long life written bytes
     */
    private final AtomicLong cumulativeWrittenBytes = new AtomicLong();

    /**
     * Long life read bytes
     */
    private final AtomicLong cumulativeReadBytes = new AtomicLong();

    /**
     * Last Time where cumulative bytes where reset to zero: this time is a real EPOC time (informative only)
     */
    private long lastCumulativeTime;

    /**
     * Last writing bandwidth
     */
    private long lastWriteThroughput;

    /**
     * Last reading bandwidth
     */
    private long lastReadThroughput;

    /**
     * Last Time Check taken
     * 最后一次检查时间 当 更新了配置时 就会更新成修改配置的时间
     */
    final AtomicLong lastTime = new AtomicLong();

    /**
     * Last written bytes number during last check interval
     */
    private volatile long lastWrittenBytes;

    /**
     * Last read bytes number during last check interval
     */
    private volatile long lastReadBytes;

    /**
     * Last future writing time during last check interval
     */
    private volatile long lastWritingTime;

    /**
     * Last reading time during last check interval
     */
    private volatile long lastReadingTime;

    /**
     * Real written bytes
     */
    private final AtomicLong realWrittenBytes = new AtomicLong();

    /**
     * Real writing bandwidth
     */
    private long realWriteThroughput;

    /**
     * Delay between two captures
     */
    final AtomicLong checkInterval = new AtomicLong(
            AbstractTrafficShapingHandler.DEFAULT_CHECK_INTERVAL);

    // default 1 s

    /**
     * Name of this Monitor
     */
    final String name;

    /**
     * The associated TrafficShapingHandler
     */
    final AbstractTrafficShapingHandler trafficShapingHandler;

    /**
     * Executor that will run the monitor
     */
    final ScheduledExecutorService executor;
    /**
     * Monitor created once in start()
     */
    Runnable monitor;
    /**
     * used in stop() to cancel the timer
     */
    volatile ScheduledFuture<?> scheduledFuture;

    /**
     * Is Monitor active
     */
    volatile boolean monitorActive;

    /**
     * Class to implement monitoring at fix delay
     *
     * 监控任务对象
     */
    private final class TrafficMonitoringTask implements Runnable {
        @Override
        public void run() {
            if (!monitorActive) {
                return;
            }
            //指定时间 记录 流量信息 并 重置
            resetAccounting(milliSecondFromNano());
            if (trafficShapingHandler != null) {
                //对记录的 流量信息 做处理
                trafficShapingHandler.doAccounting(TrafficCounter.this);
            }
            //这是单次任务 每次触发后设置下一次任务
            scheduledFuture = executor.schedule(this, checkInterval.get(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Start the monitoring process.
     * 启动计数器对象 也就是开启一个 记录流量信息的 定时任务
     */
    public synchronized void start() {
        //已经在启动状态 直接返回
        if (monitorActive) {
            return;
        }
        //记录启动时间
        lastTime.set(milliSecondFromNano());
        //获取时间间隔
        long localCheckInterval = checkInterval.get();
        // if executor is null, it means it is piloted by a GlobalChannelTrafficCounter, so no executor
        // 这应该是个 定时任务
        if (localCheckInterval > 0 && executor != null) {
            //启动监控 并按指定 间隔执行
            monitorActive = true;
            monitor = new TrafficMonitoringTask();
            scheduledFuture =
                executor.schedule(monitor, localCheckInterval, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Stop the monitoring process.
     * 停止监控动作
     */
    public synchronized void stop() {
        if (!monitorActive) {
            return;
        }
        monitorActive = false;
        //记录 当前流量信息
        resetAccounting(milliSecondFromNano());
        if (trafficShapingHandler != null) {
            //触发 处理信息的动作
            trafficShapingHandler.doAccounting(this);
        }
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
    }

    /**
     * Reset the accounting on Read and Write.
     *
     * 重置 属性
     * @param newLastTime the milliseconds unix timestamp that we should be considered up-to-date for.
     *                    这个是 当前时间的 纳秒级别
     */
    synchronized void resetAccounting(long newLastTime) {
        //代表上次检测到现在的时间间隔
        long interval = newLastTime - lastTime.getAndSet(newLastTime);
        if (interval == 0) {
            //不需要做任何处理
            // nothing to do
            return;
        }
        if (logger.isDebugEnabled() && interval > checkInterval() << 1) {
            logger.debug("Acct schedule not ok: " + interval + " > 2*" + checkInterval() + " from " + name);
        }
        //将当前 记录的数据全部清零
        lastReadBytes = currentReadBytes.getAndSet(0);
        lastWrittenBytes = currentWrittenBytes.getAndSet(0);

        //计算单位流量 这样就变成 最后次check 到 更改配置后的 单位流量

        lastReadThroughput = lastReadBytes * 1000 / interval;
        // nb byte / checkInterval in ms * 1000 (1s)
        lastWriteThroughput = lastWrittenBytes * 1000 / interval;
        // nb byte / checkInterval in ms * 1000 (1s)
        realWriteThroughput = realWrittenBytes.getAndSet(0) * 1000 / interval;

        //设置 读取 和 写入时长 这里的 last 和非last 暂时没搞懂
        lastWritingTime = Math.max(lastWritingTime, writingTime);
        lastReadingTime = Math.max(lastReadingTime, readingTime);
    }

    /**
     * Constructor with the {@link AbstractTrafficShapingHandler} that hosts it, the {@link ScheduledExecutorService}
     * to use, its name, the checkInterval between two computations in milliseconds.
     *
     * @param executor
     *            the underlying executor service for scheduling checks, might be null when used
     * from {@link GlobalChannelTrafficCounter}.
     * @param name
     *            the name given to this monitor.
     * @param checkInterval
     *            the checkInterval in millisecond between two computations.
     */
    public TrafficCounter(ScheduledExecutorService executor, String name, long checkInterval) {
        if (name == null) {
            throw new NullPointerException("name");
        }

        trafficShapingHandler = null;
        this.executor = executor;
        this.name = name;

        init(checkInterval);
    }

    /**
     * Constructor with the {@link AbstractTrafficShapingHandler} that hosts it, the Timer to use, its
     * name, the checkInterval between two computations in millisecond.
     *
     * @param trafficShapingHandler
     *            the associated AbstractTrafficShapingHandler.
     * @param executor
     *            the underlying executor service for scheduling checks, might be null when used
     * from {@link GlobalChannelTrafficCounter}.
     * @param name
     *            the name given to this monitor.
     * @param checkInterval
     *            the checkInterval in millisecond between two computations.
     */
    public TrafficCounter(
            AbstractTrafficShapingHandler trafficShapingHandler, ScheduledExecutorService executor,
            String name, long checkInterval) {

        if (trafficShapingHandler == null) {
            throw new IllegalArgumentException("trafficShapingHandler");
        }
        if (name == null) {
            throw new NullPointerException("name");
        }

        this.trafficShapingHandler = trafficShapingHandler;
        this.executor = executor;
        this.name = name;

        init(checkInterval);
    }

    private void init(long checkInterval) {
        // absolute time: informative only
        lastCumulativeTime = System.currentTimeMillis();
        writingTime = milliSecondFromNano();
        readingTime = writingTime;
        lastWritingTime = writingTime;
        lastReadingTime = writingTime;
        configure(checkInterval);
    }

    /**
     * Change checkInterval between two computations in millisecond.
     *
     * 修改检查时间间隔
     * @param newCheckInterval The new check interval (in milliseconds)
     */
    public void configure(long newCheckInterval) {
        //去除个位数的时间
        long newInterval = newCheckInterval / 10 * 10;
        //时间间隔发生变化
        if (checkInterval.getAndSet(newInterval) != newInterval) {
            //代表需要停止计数
            if (newInterval <= 0) {
                stop();
                // No more active monitoring
                //记录最后的执行时间
                lastTime.set(milliSecondFromNano());
            } else {
                // Start if necessary
                //重启
                start();
            }
        }
    }

    /**
     * Computes counters for Read.
     *
     * @param recv
     *            the size in bytes to read
     */
    void bytesRecvFlowControl(long recv) {
        //当前读取到多少个 bytes 这个长度是 msg 中获取的size
        currentReadBytes.addAndGet(recv);
        //积累量增加
        cumulativeReadBytes.addAndGet(recv);
    }

    /**
     * Computes counters for Write.
     *
     * @param write
     *            the size in bytes to write
     */
    void bytesWriteFlowControl(long write) {
        currentWrittenBytes.addAndGet(write);
        cumulativeWrittenBytes.addAndGet(write);
    }

    /**
     * Computes counters for Real Write.
     *
     * @param write
     *            the size in bytes to write
     */
    void bytesRealWriteFlowControl(long write) {
        realWrittenBytes.addAndGet(write);
    }

    /**
     * @return the current checkInterval between two computations of traffic counter
     *         in millisecond.
     */
    public long checkInterval() {
        return checkInterval.get();
    }

    /**
     * @return the Read Throughput in bytes/s computes in the last check interval.
     */
    public long lastReadThroughput() {
        return lastReadThroughput;
    }

    /**
     * @return the Write Throughput in bytes/s computes in the last check interval.
     */
    public long lastWriteThroughput() {
        return lastWriteThroughput;
    }

    /**
     * @return the number of bytes read during the last check Interval.
     */
    public long lastReadBytes() {
        return lastReadBytes;
    }

    /**
     * @return the number of bytes written during the last check Interval.
     */
    public long lastWrittenBytes() {
        return lastWrittenBytes;
    }

    /**
     * @return the current number of bytes read since the last checkInterval.
     */
    public long currentReadBytes() {
        return currentReadBytes.get();
    }

    /**
     * @return the current number of bytes written since the last check Interval.
     */
    public long currentWrittenBytes() {
        return currentWrittenBytes.get();
    }

    /**
     * @return the Time in millisecond of the last check as of System.currentTimeMillis().
     */
    public long lastTime() {
        return lastTime.get();
    }

    /**
     * @return the cumulativeWrittenBytes
     */
    public long cumulativeWrittenBytes() {
        return cumulativeWrittenBytes.get();
    }

    /**
     * @return the cumulativeReadBytes
     */
    public long cumulativeReadBytes() {
        return cumulativeReadBytes.get();
    }

    /**
     * @return the lastCumulativeTime in millisecond as of System.currentTimeMillis()
     * when the cumulative counters were reset to 0.
     */
    public long lastCumulativeTime() {
        return lastCumulativeTime;
    }

    /**
     * @return the realWrittenBytes
     */
    public AtomicLong getRealWrittenBytes() {
        return realWrittenBytes;
    }

    /**
     * @return the realWriteThroughput
     */
    public long getRealWriteThroughput() {
        return realWriteThroughput;
    }

    /**
     * Reset both read and written cumulative bytes counters and the associated absolute time
     * from System.currentTimeMillis().
     */
    public void resetCumulativeTime() {
        lastCumulativeTime = System.currentTimeMillis();
        cumulativeReadBytes.set(0);
        cumulativeWrittenBytes.set(0);
    }

    /**
     * @return the name of this TrafficCounter.
     */
    public String name() {
        return name;
    }

    /**
     * Returns the time to wait (if any) for the given length message, using the given limitTraffic and the max wait
     * time.
     *
     * @param size
     *            the recv size
     * @param limitTraffic
     *            the traffic limit in bytes per second.
     * @param maxTime
     *            the max time in ms to wait in case of excess of traffic.
     * @return the current time to wait (in ms) if needed for Read operation.
     */
    @Deprecated
    public long readTimeToWait(final long size, final long limitTraffic, final long maxTime) {
        return readTimeToWait(size, limitTraffic, maxTime, milliSecondFromNano());
    }

    /**
     * Returns the time to wait (if any) for the given length message, using the given limitTraffic and the max wait
     * time.
     *
     * 计算 需要 限时多久才能继续读取
     * @param size
     *            the recv size
     * @param limitTraffic
     *            the traffic limit in bytes per second
     * @param maxTime
     *            the max time in ms to wait in case of excess of traffic.
     * @param now the current time
     * @return the current time to wait (in ms) if needed for Read operation.
     */
    public long readTimeToWait(final long size, final long limitTraffic, final long maxTime, final long now) {
        //记录 获取的 bytes 信息
        bytesRecvFlowControl(size);
        if (size == 0 || limitTraffic == 0) {
            return 0;
        }
        //获取 最后一次记录的时间
        final long lastTimeCheck = lastTime.get();
        //获取 记录的 btyes 总数
        long sum = currentReadBytes.get();
        //获取 本次读取耗时
        long localReadingTime = readingTime;
        //上次读了多少数据
        long lastRB = lastReadBytes;
        //代表距离上次 检测的 时间间隔
        final long interval = now - lastTimeCheck;
        //读取间隔???
        long pastDelay = Math.max(lastReadingTime - lastTimeCheck, 0);
        //如果超过了 最小间隔时间
        if (interval > AbstractTrafficShapingHandler.MINIMAL_WAIT) {
            // Enough interval time to compute shaping
            long time = sum * 1000 / limitTraffic - interval + pastDelay;
            if (time > AbstractTrafficShapingHandler.MINIMAL_WAIT) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Time: " + time + ':' + sum + ':' + interval + ':' + pastDelay);
                }
                if (time > maxTime && now + time - localReadingTime > maxTime) {
                    time = maxTime;
                }
                readingTime = Math.max(localReadingTime, now + time);
                return time;
            }
            readingTime = Math.max(localReadingTime, now);
            return 0;
        }
        // take the last read interval check to get enough interval time
        long lastsum = sum + lastRB;
        long lastinterval = interval + checkInterval.get();
        long time = lastsum * 1000 / limitTraffic - lastinterval + pastDelay;
        if (time > AbstractTrafficShapingHandler.MINIMAL_WAIT) {
            if (logger.isDebugEnabled()) {
                logger.debug("Time: " + time + ':' + lastsum + ':' + lastinterval + ':' + pastDelay);
            }
            if (time > maxTime && now + time - localReadingTime > maxTime) {
                time = maxTime;
            }
            readingTime = Math.max(localReadingTime, now + time);
            return time;
        }
        readingTime = Math.max(localReadingTime, now);
        return 0;
    }

    /**
     * Returns the time to wait (if any) for the given length message, using the given limitTraffic and
     * the max wait time.
     *
     * @param size
     *            the write size
     * @param limitTraffic
     *            the traffic limit in bytes per second.
     * @param maxTime
     *            the max time in ms to wait in case of excess of traffic.
     * @return the current time to wait (in ms) if needed for Write operation.
     */
    @Deprecated
    public long writeTimeToWait(final long size, final long limitTraffic, final long maxTime) {
        return writeTimeToWait(size, limitTraffic, maxTime, milliSecondFromNano());
    }

    /**
     * Returns the time to wait (if any) for the given length message, using the given limitTraffic and
     * the max wait time.
     *
     * @param size
     *            the write size
     * @param limitTraffic
     *            the traffic limit in bytes per second.
     * @param maxTime
     *            the max time in ms to wait in case of excess of traffic.
     * @param now the current time
     * @return the current time to wait (in ms) if needed for Write operation.
     */
    public long writeTimeToWait(final long size, final long limitTraffic, final long maxTime, final long now) {
        bytesWriteFlowControl(size);
        if (size == 0 || limitTraffic == 0) {
            return 0;
        }
        final long lastTimeCheck = lastTime.get();
        long sum = currentWrittenBytes.get();
        long lastWB = lastWrittenBytes;
        long localWritingTime = writingTime;
        long pastDelay = Math.max(lastWritingTime - lastTimeCheck, 0);
        final long interval = now - lastTimeCheck;
        if (interval > AbstractTrafficShapingHandler.MINIMAL_WAIT) {
            // Enough interval time to compute shaping
            long time = sum * 1000 / limitTraffic - interval + pastDelay;
            if (time > AbstractTrafficShapingHandler.MINIMAL_WAIT) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Time: " + time + ':' + sum + ':' + interval + ':' + pastDelay);
                }
                if (time > maxTime && now + time - localWritingTime > maxTime) {
                    time = maxTime;
                }
                writingTime = Math.max(localWritingTime, now + time);
                return time;
            }
            writingTime = Math.max(localWritingTime, now);
            return 0;
        }
        // take the last write interval check to get enough interval time
        long lastsum = sum + lastWB;
        long lastinterval = interval + checkInterval.get();
        long time = lastsum * 1000 / limitTraffic - lastinterval + pastDelay;
        if (time > AbstractTrafficShapingHandler.MINIMAL_WAIT) {
            if (logger.isDebugEnabled()) {
                logger.debug("Time: " + time + ':' + lastsum + ':' + lastinterval + ':' + pastDelay);
            }
            if (time > maxTime && now + time - localWritingTime > maxTime) {
                time = maxTime;
            }
            writingTime = Math.max(localWritingTime, now + time);
            return time;
        }
        writingTime = Math.max(localWritingTime, now);
        return 0;
    }

    @Override
    public String toString() {
        return new StringBuilder(165).append("Monitor ").append(name)
                .append(" Current Speed Read: ").append(lastReadThroughput >> 10).append(" KB/s, ")
                .append("Asked Write: ").append(lastWriteThroughput >> 10).append(" KB/s, ")
                .append("Real Write: ").append(realWriteThroughput >> 10).append(" KB/s, ")
                .append("Current Read: ").append(currentReadBytes.get() >> 10).append(" KB, ")
                .append("Current asked Write: ").append(currentWrittenBytes.get() >> 10).append(" KB, ")
                .append("Current real Write: ").append(realWrittenBytes.get() >> 10).append(" KB").toString();
    }
}
