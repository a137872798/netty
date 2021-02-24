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

package io.netty.util;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Light-weight object pool based on a thread-local stack.
 *
 * @param <T> the type of the pooled object
 */
public abstract class Recycler<T> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);

    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = new Handle() {
        @Override
        public void recycle(Object object) {
            // NOOP
        }
    };
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);
    /**
     * 就是一个特殊标识位
     */
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();
    /**
     * 每条线程允许缓存多少个元素
     */
    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 4 * 1024; // Use 4k instances as default.
    /**
     * 每条线程 的最大容量
     */
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD;
    /**
     * 初始容量
     */
    private static final int INITIAL_CAPACITY;
    private static final int MAX_SHARED_CAPACITY_FACTOR;
    private static final int MAX_DELAYED_QUEUES_PER_THREAD;
    /**
     * 每个Link节点的 能保存的 回收对象
     */
    private static final int LINK_CAPACITY;
    private static final int RATIO;

    //从系统变量中初始化对应属性
    static {
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        int maxCapacityPerThread = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }

        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;

        MAX_SHARED_CAPACITY_FACTOR = max(2,
                SystemPropertyUtil.getInt("io.netty.recycler.maxSharedCapacityFactor",
                        2));

        MAX_DELAYED_QUEUES_PER_THREAD = max(0,
                SystemPropertyUtil.getInt("io.netty.recycler.maxDelayedQueuesPerThread",
                        // We use the same value as default EventLoop number
                        NettyRuntime.availableProcessors() * 2));

        LINK_CAPACITY = safeFindNextPositivePowerOfTwo(
                max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));

        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        RATIO = safeFindNextPositivePowerOfTwo(SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug("-Dio.netty.recycler.linkCapacity: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
            }
        }

        INITIAL_CAPACITY = min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256);
    }

    private final int maxCapacityPerThread;
    private final int maxSharedCapacityFactor;
    private final int ratioMask;
    private final int maxDelayedQueuesPerThread;

    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
            return new Stack<T>(Recycler.this, Thread.currentThread(), maxCapacityPerThread, maxSharedCapacityFactor,
                    ratioMask, maxDelayedQueuesPerThread);
        }

        /**
         * 如果线程使用完毕后 并且它的runnable被包装过 会触发该方法
         * @param value
         */
        @Override
        protected void onRemoval(Stack<T> value) {
            // Let us remove the WeakOrderQueue from the WeakHashMap directly if its safe to remove some overhead
            if (value.threadRef.get() == Thread.currentThread()) {
                if (DELAYED_RECYCLED.isSet()) {
                    DELAYED_RECYCLED.get().remove(value);
                }
            }
        }
    };

    /**
     * 每条线程允许回收多少量
     */
    protected Recycler() {
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread) {
        this(maxCapacityPerThread, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
        ratioMask = safeFindNextPositivePowerOfTwo(ratio) - 1;
        if (maxCapacityPerThread <= 0) {
            this.maxCapacityPerThread = 0;
            this.maxSharedCapacityFactor = 1;
            this.maxDelayedQueuesPerThread = 0;
        } else {
            this.maxCapacityPerThread = maxCapacityPerThread;
            this.maxSharedCapacityFactor = max(1, maxSharedCapacityFactor);
            this.maxDelayedQueuesPerThread = max(0, maxDelayedQueuesPerThread);
        }
    }

    /**
     * 获取 可回收对象
     *
     * @return
     */
    @SuppressWarnings("unchecked")
    public final T get() {
        // 线程不允许缓存对象 申请一个recycle为noop的对象
        if (maxCapacityPerThread == 0) {
            return newObject((Handle<T>) NOOP_HANDLE);
        }
        //获取该线程的 stack 对象
        Stack<T> stack = threadLocal.get();
        //弹出 handler对象
        DefaultHandle<T> handle = stack.pop();
        if (handle == null) {
            handle = stack.newHandle();
            //这里就是 用户自己定义 需要被回收的类 并设置到 handler 中
            handle.value = newObject(handle);
        }
        return (T) handle.value;
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }

        DefaultHandle<T> h = (DefaultHandle<T>) handle;
        if (h.stack.parent != this) {
            return false;
        }

        h.recycle(o);
        return true;
    }

    final int threadLocalCapacity() {
        return threadLocal.get().elements.length;
    }

    final int threadLocalSize() {
        return threadLocal.get().size;
    }

    protected abstract T newObject(Handle<T> handle);

    public interface Handle<T> {
        void recycle(T object);
    }

    /**
     * @param <T>
     */
    static final class DefaultHandle<T> implements Handle<T> {
        /**
         * 当对象处于取出状态 lastRecycledId/recycleId 为0
         * 当对象归还到栈中 这2个值不为0
         */
        private int lastRecycledId;
        private int recycleId;

        /**
         * 已经被回收过
         */
        boolean hasBeenRecycled;

        /**
         * 属于哪个栈下
         */
        private Stack<?> stack;
        /**
         * 维护的被回收对象
         */
        private Object value;

        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }

        /**
         * 重写 了handler 的 回收方法 也就是压栈
         *
         * @param object
         */
        @Override
        public void recycle(Object object) {
            // 不允许回收其他对象
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }

            //获取关联的 栈对象
            Stack<?> stack = this.stack;
            if (lastRecycledId != recycleId || stack == null) {
                throw new IllegalStateException("recycled already");
            }

            //入栈
            stack.push(this);
        }
    }

    /**
     * stack虽然是本地私有变量 但是实际上可以被其他线程观测到
     * 同时每个线程都是以弱引用的方式维护其他线程创建的stack 这种跨线程的引用不应该导致stack无法被回收 并且这样会间接导致 stack绑定的线程也无法被回收
     * 引用关系为
     * b线程 -> a线程创建的stack -> a对应的stack持有a线程的引用  如果他们之间都是强引用关系 那么他们都无法被释放.
     */
    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
                /**
                 * 将 每个 线程(stack)的 回收容器 与 该stack 对应的 存放其他线程回收 对象的 WeakOrderQueue的映射关系维护起来
                 * @return
                 */
                @Override
                protected Map<Stack<?>, WeakOrderQueue> initialValue() {
                    return new WeakHashMap<Stack<?>, WeakOrderQueue>();
                }
            };

    /**
     * a queue that makes only moderate guarantees about visibility: items are seen in the correct order,
     * but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain
     *
     */
    private static final class WeakOrderQueue {

        static final WeakOrderQueue DUMMY = new WeakOrderQueue();

        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.

        /**
         *
         */
        @SuppressWarnings("serial")
        static final class Link extends AtomicInteger {
            // 每个link可以存储一组对象
            private final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];

            /**
             * 这个 指针代表 当前 link 转移到 stack中转移了 多少
             */
            private int readIndex;
            Link next;
        }

        // This act as a place holder for the head Link but also will reclaim space once finalized.
        // Its important this does not hold any reference to either Stack or WeakOrderQueue.

        /**
         * 链表头部
         */
        static final class Head {
            /**
             * 这个就是 stack的 sharedCapacity  每个 队列对象都共用了 这个值 每个 每当 通过某个queue修改了这个 容量 其他 queue都能感知到
             */
            private final AtomicInteger availableSharedCapacity;

            /**
             * 头节点也具备link的功能  其他Link节点就是连接到这里
             */
            Link link;

            Head(AtomicInteger availableSharedCapacity) {
                this.availableSharedCapacity = availableSharedCapacity;
            }

            /// TODO: In the future when we move to Java9+ we should use java.lang.ref.Cleaner.
            @Override
            protected void finalize() throws Throwable {
                try {
                    super.finalize();
                } finally {
                    // 本对象被GC回收时 会归还容量
                    Link head = link;
                    link = null;
                    while (head != null) {
                        //归还 容量到 stack 的总共享容量
                        reclaimSpace(LINK_CAPACITY);
                        Link next = head.next;
                        // Unlink to help GC and guard against GC nepotism.
                        head.next = null;
                        head = next;
                    }
                }
            }

            /**
             * 归还空间
             */
            void reclaimSpace(int space) {
                assert space >= 0;
                availableSharedCapacity.addAndGet(space);
            }

            /**
             * 占用空间
             *
             * @param space
             * @return
             */
            boolean reserveSpace(int space) {
                return reserveSpace(availableSharedCapacity, space);
            }

            /**
             * 将 availableSharedCapacity 减少space
             *
             * @param availableSharedCapacity
             * @param space
             * @return
             */
            static boolean reserveSpace(AtomicInteger availableSharedCapacity, int space) {
                assert space >= 0;
                for (; ; ) {
                    int available = availableSharedCapacity.get();
                    if (available < space) {
                        return false;
                    }
                    if (availableSharedCapacity.compareAndSet(available, available - space)) {
                        return true;
                    }
                }
            }
        }

        // chain of data items
        /**
         * 该队列包含一个head节点 内部是一个链表结构
         */
        private final Head head;
        /**
         * 对应链表的最后一个节点
         */
        private Link tail;
        // pointer to another queue of delayed items for the same stack
        /**
         * 队列本身又以链表连接 每个队列对应一个线程
         */
        private WeakOrderQueue next;
        /**
         * 同样用弱引用 因为当前stack不应该强引用其他线程,会阻碍其他线程的正常回收
         */
        private final WeakReference<Thread> owner;
        private final int id = ID_GENERATOR.getAndIncrement();

        private WeakOrderQueue() {
            owner = null;
            head = new Head(null);
        }

        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            tail = new Link();

            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key. So just store the enclosed AtomicInteger which should allow to have the
            // Stack itself GCed.
            head = new Head(stack.availableSharedCapacity);
            head.link = tail;
            //记录该队列是由 哪个线程创建的
            owner = new WeakReference<Thread>(thread);
        }

        /**
         * 每个stack可以关联一个queue链表 每个queue对应从某个非stack对应的线程收集到的对象
         *
         * @param stack
         * @param thread
         * @return
         */
        static WeakOrderQueue newQueue(Stack<?> stack, Thread thread) {
            final WeakOrderQueue queue = new WeakOrderQueue(stack, thread);
            // Done outside of the constructor to ensure WeakOrderQueue.this does not escape the constructor and so
            // may be accessed while its still constructed.
            // 与其他线程对应的 WeakOrderQueue 形成链表
            stack.setHead(queue);

            return queue;
        }

        private void setNext(WeakOrderQueue next) {
            assert next != this;
            this.next = next;
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         * 分配一个WeakOrderQueue
         */
        static WeakOrderQueue allocate(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space
            // 在队列首次被创建时 至少会预先分配一个link块的大小
            return Head.reserveSpace(stack.availableSharedCapacity, LINK_CAPACITY)
                    ? newQueue(stack, thread) : null;
        }

        /**
         * 从非stack线程回收到的对象会返回到queue中
         * @param handle
         */
        void add(DefaultHandle<?> handle) {
            //记录 回收的 id
            handle.lastRecycledId = id;

            Link tail = this.tail;
            int writeIndex;

            // 队列被分成了多个link 这里代表某个link的内存已经被使用完了 需要再分配一个link
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {
                // 尝试再申请一个link的大小 如果空间不足 放弃回收本对象
                if (!head.reserveSpace(LINK_CAPACITY)) {
                    // Drop it.
                    return;
                }

                // 这里分配内存都是通过 AtomicInteger 解决并发问题的
                // We allocate a Link so reserve the space
                // 生成一个 link 对象  并设置到新的 tail
                this.tail = tail = tail.next = new Link();

                writeIndex = tail.get();
            }
            // 也是无锁 因为这个queue只有这个线程能操作 冲突块就减小到 仅仅是分配link的逻辑  并且link一次分配了一定量的大小 在没达到这个大小前 继续回收对象也不会造成线程竞争
            tail.elements[writeIndex] = handle;
            // 清除强引用 确保stack可以被正常回收
            handle.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            // 这里是刷新屏障么 也就是next指针也会可见???
            tail.lazySet(writeIndex + 1);
        }

        /**
         * 代表还有数据缓存在队列中
         *
         * @return
         */
        boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }

        // transfer as many items as we can from this queue to the stack, returning true if any were transferred

        /**
         * 将queue的对象归还到stack
         * @param dst
         * @return
         */
        @SuppressWarnings("rawtypes")
        boolean transfer(Stack<?> dst) {
            Link head = this.head.link;
            //代表没有可移动的对象
            if (head == null) {
                return false;
            }

            //已经读到末尾 就移动到下个 link
            if (head.readIndex == LINK_CAPACITY) {
                if (head.next == null) {
                    return false;
                }
                // 上一个link可以被回收了  实际上这里缺少了一步 this.head.reclaimSpace(LINK_CAPACITY);
                this.head.link = head = head.next;
            }

            // 代表对应的数组已经读取到第几个slot
            final int srcStart = head.readIndex;
            // 当前设置到了第几个slot
            int srcEnd = head.get();
            // 代表都已经被转移出去了
            final int srcSize = srcEnd - srcStart;
            if (srcSize == 0) {
                return false;
            }

            final int dstSize = dst.size;
            // 此时有多少元素可以转移
            final int expectedCapacity = dstSize + srcSize;

            // 代表如果将当前queue.link中剩余的元素全部转移过去 会超过stack.element的最大容量 不能全部转移
            if (expectedCapacity > dst.elements.length) {
                //增加数组长度 应该 不能超过某个最大值
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);
                //转移到哪个slot为止
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);
            }

            // srcStart == srcEnd 代表没有足够空间 不需要转移
            if (srcStart != srcEnd) {
                final DefaultHandle[] srcElems = head.elements;
                final DefaultHandle[] dstElems = dst.elements;
                int newDstSize = dstSize;
                for (int i = srcStart; i < srcEnd; i++) {
                    DefaultHandle element = srcElems[i];
                    if (element.recycleId == 0) {
                        //代表 被回收了
                        element.recycleId = element.lastRecycledId;
                        //非0 且不相等 代表在其他地方回收了
                    } else if (element.recycleId != element.lastRecycledId) {
                        throw new IllegalStateException("recycled already");
                    }
                    srcElems[i] = null;

                    //是否放弃对该对象的和回收
                    if (dst.dropHandle(element)) {
                        // Drop the object.
                        continue;
                    }

                    // 当元素从queue中取出来后 重新设置了stack
                    element.stack = dst;
                    dstElems[newDstSize++] = element;
                }

                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    this.head.reclaimSpace(LINK_CAPACITY);
                    this.head.link = head.next;
                }

                head.readIndex = srcEnd;
                if (dst.size == newDstSize) {
                    return false;
                }
                //更新 栈大小
                dst.size = newDstSize;
                return true;
            } else {
                // The destination stack is full already.
                return false;
            }
        }
    }

    /**
     * recycle 被封装后的 组成的 handler就是存放在这个 stack 中
     * Stack 与线程绑定
     *
     * @param <T>
     */
    static final class Stack<T> {

        // we keep a queue of per-thread queues, which is appended to once only, each time a new thread other
        // than the stack owner recycles: when we run out of items in our stack we iterate this collection
        // to scavenge those that can be reused. this permits us to incur minimal thread synchronisation whilst
        // still recycling all items.
        /**
         * 代表 该 stack 是由 哪个  recycle 对象创建的
         */
        final Recycler<T> parent;

        // We store the Thread in a WeakReference as otherwise we may be the only ones that still hold a strong
        // Reference to the Thread itself after it died because DefaultHandle will hold a reference to the Stack.
        //
        // The biggest issue is if we do not use a WeakReference the Thread may not be able to be collected at all if
        // the user will store a reference to the DefaultHandle somewhere and never clear this reference (or not clear
        // it in a timely manner).
        /**
         * 这个stack 绑定的线程
         */
        final WeakReference<Thread> threadRef;

        /**
         * 允许 其他线程 最多回收多少数量的对象
         */
        final AtomicInteger availableSharedCapacity;

        /**
         * 允许几个线程回收本stack创建的对象
         */
        final int maxDelayedQueues;

        private final int maxCapacity;
        private final int ratioMask;
        /**
         * 本线程对应的对象池 无锁操作
         */
        private DefaultHandle<?>[] elements;
        private int size;
        private int handleRecycleCount = -1; // Start with -1 so the first one will be recycled.

        /**
         * cursor 代表上次stack从哪个队列中拉取对象
         */
        private WeakOrderQueue cursor, prev;
        /**
         * queue的头节点 维护其他线程回收的对象
         */
        private volatile WeakOrderQueue head;

        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int ratioMask, int maxDelayedQueues) {
            this.parent = parent;

            threadRef = new WeakReference<Thread>(thread);
            this.maxCapacity = maxCapacity;
            /**
             * 最小的大小也需要满足分配一个Link的大小
             */
            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];
            this.ratioMask = ratioMask;
            this.maxDelayedQueues = maxDelayedQueues;
        }

        // Marked as synchronized to ensure this is serialized.
        synchronized void setHead(WeakOrderQueue queue) {
            queue.setNext(head);
            head = queue;
        }

        /**
         * 扩容 不能超过最大容量  返回 新的总大小
         *
         * @param expectedCapacity
         * @return
         */
        int increaseCapacity(int expectedCapacity) {
            int newCapacity = elements.length;
            int maxCapacity = this.maxCapacity;
            do {
                newCapacity <<= 1;
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity);

            newCapacity = min(newCapacity, maxCapacity);
            if (newCapacity != elements.length) {
                elements = Arrays.copyOf(elements, newCapacity);
            }

            return newCapacity;
        }

        /**
         * 从 stack 中弹出元素  跟保存元素的 顺序相反 最后被回收的对象最先弹出
         *
         * @return
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        DefaultHandle<T> pop() {
            int size = this.size;

            // 当本线程栈中已经没有元素可以复用了 尝试从其他线程获取 (这时可能会有锁竞争)
            if (size == 0) {
                //尝试 从其他线程 获取
                if (!scavenge()) {
                    //其他线程也没有就是返回null
                    return null;
                }
                size = this.size;
            }
            //从本线程获取
            // 这里是不会存在锁竞争的 因为使用的stack是存储在线程私有变量
            size--;
            DefaultHandle ret = elements[size];
            elements[size] = null;
            //每次 回收 id 都是一样的 不一样 代表在 其他的某个地方 进行回收了 那该对象状态就不能确定了(到底在回收后再哪里又被回收了 这里弹出会不会影响到另一个地方)
            if (ret.lastRecycledId != ret.recycleId) {
                throw new IllegalStateException("recycled multiple times");
            }
            //设置成 未回收的样子
            ret.recycleId = 0;
            ret.lastRecycledId = 0;
            this.size = size;
            return ret;
        }

        /**
         * 尝试从其他线程获取缓存对象
         *
         * @return
         */
        boolean scavenge() {
            // continue an existing scavenge, if any
            // 将queue的元素转移到stack中
            if (scavengeSome()) {
                return true;
            }

            // reset our scavenge cursor
            // 将queue 重置 因为 最早的 线程队列 可能又添加了新的元素
            prev = null;
            cursor = head;
            return false;
        }

        boolean scavengeSome() {
            WeakOrderQueue prev;
            WeakOrderQueue cursor = this.cursor;
            //如果stack 还没有指向任何 对象 先指向 head 节点
            if (cursor == null) {
                // 如果cursor是首次设置 那么prev为null
                prev = null;
                cursor = head;
                // 代表其他线程也没有从该stack中回收过对象
                if (cursor == null) {
                    return false;
                }
            } else {
                prev = this.prev;
            }

            boolean success = false;
            do {
                // 首先尝试从当前queue中转移节点
                if (cursor.transfer(this)) {
                    success = true;
                    break;
                }
                //从当前指针 移向下一个 queue 对象 并尝试 继续转移
                WeakOrderQueue next = cursor.next;
                // 顺便检查队列对应的线程是否被回收了 如果线程被回收 将队列移除 便于队列被gc回收 如果线程还存在就有可能继续往内部插入元素
                // 能进入这一行实际上队列中的元素应该已经被取完了
                if (cursor.owner.get() == null) {
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.
                    // 只要该Link 还有数据没有获取完 就 继续转移
                    if (cursor.hasFinalData()) {
                        for (; ; ) {
                            if (cursor.transfer(this)) {
                                success = true;
                            } else {
                                break;
                            }
                        }
                    }

                    if (prev != null) {
                        //从链表中 移除了 cursor 对象
                        prev.setNext(next);
                    }
                } else {
                    // 查找下一个队列
                    prev = cursor;
                }

                cursor = next;

            } while (cursor != null && !success);

            this.prev = prev;
            this.cursor = cursor;
            return success;
        }

        /**
         * 入栈操作
         *
         * @param item
         */
        void push(DefaultHandle<?> item) {
            Thread currentThread = Thread.currentThread();
            //如果是在创建对象的线程回收对象 直接回归到当前栈  这样是无锁化的 (stack.element不需要加锁,始终只有创建stack的线程去访问)
            if (threadRef.get() == currentThread) {
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
                pushNow(item);
            } else {
                // The current Thread is not the one that belongs to the Stack
                // (or the Thread that belonged to the Stack was collected already), we need to signal that the push
                // happens later.
                pushLater(item, currentThread);
            }
        }

        /**
         * 在recycle时直接入栈
         *
         * @param item
         */
        private void pushNow(DefaultHandle<?> item) {
            if ((item.recycleId | item.lastRecycledId) != 0) {
                throw new IllegalStateException("recycled already");
            }
            //这里就是代表被回收了
            item.recycleId = item.lastRecycledId = OWN_THREAD_ID;

            int size = this.size;
            //超过允许保存的最大值 或者 该item超过回收次数了放弃本次回收
            if (size >= maxCapacity || dropHandle(item)) {
                // Hit the maximum capacity or should drop - drop the possibly youngest object.
                return;
            }
            //需要进行扩容
            if (size == elements.length) {
                elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
            }

            elements[size] = item;
            this.size = size + 1;
        }

        /**
         * 本线程对应的对象池(stack.element) 没有空闲对象可用了 尝试从其他线程借用对象
         *
         * @param item
         * @param thread
         */
        private void pushLater(DefaultHandle<?> item, Thread thread) {
            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();
            // 获取当前stack关联的queue
            WeakOrderQueue queue = delayedRecycled.get(this);

            // 存储到push线程对应的queue中 此时stack绑定的线程与push线程不一致 所以不能存储到element中 需要存储到一个线程安全的队列
            if (queue == null) {
                // 只支持一定数量的stack可以从其他线程拉取对象 当超过数量时 设置一个哨兵
                if (delayedRecycled.size() >= maxDelayedQueues) {
                    // Add a dummy queue so we know we should drop the object
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);
                    return;
                }
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                // 为当前stack 创建一个并发队列,专门用于存储从其他线程收集过来的对象
                if ((queue = WeakOrderQueue.allocate(this, thread)) == null) {
                    // drop object
                    return;
                }
                //生成映射关系
                delayedRecycled.put(this, queue);
                // 代表已经为足够多的stack设置了队列 本次操作的stack无匹配的队列 不需要从其他线程回收对象
            } else if (queue == WeakOrderQueue.DUMMY) {
                // drop object
                return;
            }

            //将元素 保存到queue 中
            queue.add(item);
        }

        /**
         * 判断该对象是否不能被回收
         *
         * @param handle
         * @return
         */
        boolean dropHandle(DefaultHandle<?> handle) {
            if (!handle.hasBeenRecycled) {
                if ((++handleRecycleCount & ratioMask) != 0) {
                    // Drop the object.
                    return true;
                }
                handle.hasBeenRecycled = true;
            }
            return false;
        }

        DefaultHandle<T> newHandle() {
            return new DefaultHandle<T>(this);
        }
    }
}
