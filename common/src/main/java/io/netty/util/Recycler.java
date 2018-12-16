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

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Light-weight object pool based on a thread-local stack.
 *
 * @param <T> the type of the pooled object
 *
 *           netty 的回收对象 保证资源重复利用
 *           缓存对象是容易发生内存泄漏的 一种解决方式 就是通过WeakReference 对对象进行包装 当对象不再被引用时 能够自动 释放
 *           被WeakReference 包裹的 对象 在该对象没有被长期引用的情况下就会被回收 做缓存的 对象 一般作用域大 所以难以被回收
 *           如果想让它 在长时间没有被引用的情况下 被自动回收就需要借助 WeakReference 对象
 *
 *           原先判断一个对象是否可回收是通过可达性检测 使用了 WeakReference对象就是使得 即使该对象目前是可达的 针对缓存对象
 *           因为缓存对象 一般是 可以在任何时候被访问到的 但是没有 真正使用它 那么这时 由于被 WeakReference 包裹 该对象 还是会被GC 回收 这样就避免了 内存泄漏
 *           可达性检测 通过对象 引向map map底层数组中每个元素 又包含了某个对象的引用 这样就出现了 内存泄漏
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
     * 生成 唯一id
     */
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();
    /**
     * 每条线程 初始最大容量
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

    //在本地线程中创建了一个栈对象 这个对象就是保存 所有的Recycle 回收的对象
    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
            //将当前线程 与 stack 绑定起来 该线程 是被 WeakReference 包裹的  这样是 便于线程被销毁如果 线程 被过多对象 引用会使得该 线程无法 被GC 回收
            //就会造成 大损耗
            return new Stack<T>(Recycler.this, Thread.currentThread(), maxCapacityPerThread, maxSharedCapacityFactor,
                    ratioMask, maxDelayedQueuesPerThread);
        }

        //当 该对象被移除后
        @Override
        protected void onRemoval(Stack<T> value) {
            // Let us remove the WeakOrderQueue from the WeakHashMap directly if its safe to remove some overhead
            if (value.threadRef.get() == Thread.currentThread()) {
                //将针对 本 stack 延迟回收的 对象也移除
               if (DELAYED_RECYCLED.isSet()) {
                   DELAYED_RECYCLED.get().remove(value);
               }
            }
        }
    };

    /**
     * 每条线程 允许回收多少量
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
     * @return
     */
    @SuppressWarnings("unchecked")
    public final T get() {
        //如果 该线程不能 维护Recycle 对象 返回空对象
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
     * 将 需要被回收的对象封装成 handle 并压入栈中
     * @param <T>
     */
    static final class DefaultHandle<T> implements Handle<T> {
        //每次被回收 这2个值都会变成 一个 自增 id
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
         * @param object
         */
        @Override
        public void recycle(Object object) {
            //该handler 被创建时 就绑定在某个对象上 如果 回收的对象不是本对象 直接抛出异常
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
     * 延迟回收对象 该Stack 对象使用 WeakReference 引用 当该 stack 没有被使用时 就可能会回收该对象 会无视可达性检测
     */
    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
                /**
                 * 将 每个 线程(stack)的 回收容器 与 该stack 对应的 存放其他线程回收 对象的 WeakOrderQueue
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
     * 存储其他线程 回收到的对象  在创建 Recycle 的时候 会绑定一个 Stack 对象 其他线程 使用该recycle 对象的时候就不会保存到stack 中 而是保存在这个队列中
     *
     * 应该是 一个 其他线程 代表 一个WeakOrderQueue
     */
    private static final class WeakOrderQueue {

        static final WeakOrderQueue DUMMY = new WeakOrderQueue();

        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.
        /**
         */
        @SuppressWarnings("serial")
        static final class Link extends AtomicInteger {
            //每个 handler 代表一个回收的对象
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
             * 头节点 也具备 link 的 功能  其他 Link 节点就是 连接到这里
             */
            Link link;

            Head(AtomicInteger availableSharedCapacity) {
                this.availableSharedCapacity = availableSharedCapacity;
            }

            /// TODO: In the future when we move to Java9+ we should use java.lang.ref.Cleaner.
            @Override
            protected void finalize() throws Throwable {
                try {
                    //Head 被销毁时  清除整个队列
                    super.finalize();
                } finally {
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
             * @param space
             * @return
             */
            boolean reserveSpace(int space) {
                return reserveSpace(availableSharedCapacity, space);
            }

            /**
             * 将 availableSharedCapacity 减少space
             * @param availableSharedCapacity
             * @param space
             * @return
             */
            static boolean reserveSpace(AtomicInteger availableSharedCapacity, int space) {
                assert space >= 0;
                for (;;) {
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
         * 该队列包含一个  head 节点
         */
        private final Head head;
        /**
         * 尾节点
         */
        private Link tail;
        // pointer to another queue of delayed items for the same stack
        /**
         * 针对 stack 的 每个 外部线程对应的 WeakOrderQueue 又以链表形式连接
         */
        private WeakOrderQueue next;
        /**
         * 记录 该队列是 哪个线程 创建的  使用 WeakReference 使得 该 Thread 能够被顺利的回收 那么应该是有个地方 对该线程是否有效做判断
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
            // 没有直接使用stack 做引用这样能帮助 stack 被gc 回收
            head = new Head(stack.availableSharedCapacity);
            head.link = tail;
            //记录该队列是由 哪个线程创建的
            owner = new WeakReference<Thread>(thread);
        }

        /**
         * 为 stack 保存 队列 也就是设置到 stack 的queue 链中
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
            // 从stack 的 可分配 空间中 使用 部分大小 生成 队列 容量不够 不再生成队列
            return Head.reserveSpace(stack.availableSharedCapacity, LINK_CAPACITY)
                    ? newQueue(stack, thread) : null;
        }

        /**
         * 为队列 增加 回收对象  就是 从 其他线程 添加 回收对象到stack 中 时 触发
         * @param handle
         */
        void add(DefaultHandle<?> handle) {
            //记录 回收的 id
            handle.lastRecycledId = id;

            Link tail = this.tail;
            int writeIndex;
            //代表 放不下了  每个Link 对象一开始创建的 时候 都是0
            //应该 是每个link 只能保存 一定数量的  handler 然后超过 限度 就创建一个新的 link 然后又能存放 一个handler 数组
            //也就是 link 存满了 是要归还空间的  然后 link中的 handler 还存在???
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {
                //归还 一个 Link 的空间 归还失败 就 放弃添加
                if (!head.reserveSpace(LINK_CAPACITY)) {
                    // Drop it.
                    return;
                }
                // We allocate a Link so reserve the space
                // 生成一个 link 对象  并设置到新的 tail
                this.tail = tail = tail.next = new Link();

                writeIndex = tail.get();
            }
            //保存在这个新的link 中
            tail.elements[writeIndex] = handle;
            //帮助 stack 被回收吗???
            handle.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            // 这不知道啥意思  最后才增加???
            tail.lazySet(writeIndex + 1);
        }

        /**
         * 还有数据 在回收状态没有被取出
         * @return
         */
        boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }

        // transfer as many items as we can from this queue to the stack, returning true if any were transferred
        /**
         * 将queue 中的  元素 移动到stack 中
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
                //把head的 link变成当前link 了???
                this.head.link = head = head.next;
            }

            //应该是移动到哪里了
            final int srcStart = head.readIndex;
            //当前终点
            int srcEnd = head.get();
            //代表没有可读
            final int srcSize = srcEnd - srcStart;
            if (srcSize == 0) {
                return false;
            }

            //因为元素要 移动到stack 中 所以 这里是 估计 大小
            final int dstSize = dst.size;
            final int expectedCapacity = dstSize + srcSize;

            //超过 数组长度
            if (expectedCapacity > dst.elements.length) {
                //增加数组长度 应该 不能超过某个最大值
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);
                //代表 可以添加多少
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);
            }

            //开始添加
            if (srcStart != srcEnd) {
                //代表需要 移动的元素
                final DefaultHandle[] srcElems = head.elements;
                //目标数组
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
                    //这里才 设置element 的 stack 对象 现在还不理解 为什么这样做
                    element.stack = dst;
                    dstElems[newDstSize ++] = element;
                }

                //以Link 级别的 大小 进行recycle 是为了 帮助GC 回收??? 如果不使用Link 链表的方式 直接将所有元素都存放到queue 中 会使得整个 queue 的回收困难???
                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    this.head.reclaimSpace(LINK_CAPACITY);
                    this.head.link = head.next;
                }

                //代表当前 读取到了 stack 的哪里
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
         * 允许 其他线程 最多 回收 多少数量的 对象
         */
        final AtomicInteger availableSharedCapacity;

        final int maxDelayedQueues;

        private final int maxCapacity;
        private final int ratioMask;
        private DefaultHandle<?>[] elements;
        private int size;
        private int handleRecycleCount = -1; // Start with -1 so the first one will be recycled.
        /**
         * 应该是 当前的
         */
        private WeakOrderQueue cursor, prev;
        /**
         * queue 的头节点 每次 给 stack 设置queue 时 会设置成新的 head 节点
         */
        private volatile WeakOrderQueue head;

        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int ratioMask, int maxDelayedQueues) {
            this.parent = parent;
            /**
             * 使用 Thread 就是为了 保证 该线程能顺利被GC 回收 那么如果该线程 已经被 回收了 那 该Stack 对象 又该怎么处理
             */
            threadRef = new WeakReference<Thread>(thread);
            this.maxCapacity = maxCapacity;
            /**
             * 最小的 大小 也 需要满足 分配一个 LinkCapacity 的大小
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
         * @return
         */
        @SuppressWarnings({ "unchecked", "rawtypes" })
        DefaultHandle<T> pop() {
            int size = this.size;
            //首次 size 就是 0  也就是 如果 stack 中 存在 元素 是 优先 回收 该对象的
            if (size == 0) {
                //尝试 从其他线程 获取
                if (!scavenge()) {
                    //其他线程也没有就是返回null
                    return null;
                }
                size = this.size;
            }
            //弹出的 正常情况
            size --;
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
         * 尝试 获取 从其他线程添加的 回收对象
         * @return
         */
        boolean scavenge() {
            // continue an existing scavenge, if any
            // 如果将 queue 中 的 元素 转移到 stack 成功 就 返回true
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
            //当前 指向queue 对象的 前一个 对象
            WeakOrderQueue prev;
            //当前指向的 queue 对象
            WeakOrderQueue cursor = this.cursor;
            //如果stack 还没有指向任何 对象 先指向 head 节点
            if (cursor == null) {
                //如果当前指向head 节点 就不存在 prev 节点
                prev = null;
                cursor = head;
                //不存在 head 节点 代表 没有从其他线程回收的 对象
                if (cursor == null) {
                    return false;
                }
            } else {
                prev = this.prev;
            }

            boolean success = false;
            do {
                //将当前节点中的  元素转移到 stack 中 一旦成功 退出循环
                if (cursor.transfer(this)) {
                    success = true;
                    break;
                }
                //从当前指针 移向下一个 queue 对象 并尝试 继续转移
                WeakOrderQueue next = cursor.next;
                //如果下个对象的  thread 被回收了 将 cursor 数据 转移后 从链表中去除 该元素
                if (cursor.owner.get() == null) {
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.
                    // 只要该Link 还有数据没有获取完 就 继续转移
                    if (cursor.hasFinalData()) {
                        for (;;) {
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
                    //这里是 正常的 将指针 下移
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
         * @param item
         */
        void push(DefaultHandle<?> item) {
            Thread currentThread = Thread.currentThread();
            //如果是在 创建Stack 的线程 回收对象
            if (threadRef.get() == currentThread) {
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
                // 直接入栈就可以
                pushNow(item);
            } else {
                // The current Thread is not the one that belongs to the Stack
                // (or the Thread that belonged to the Stack was collected already), we need to signal that the push
                // happens later.
                pushLater(item, currentThread);
            }
        }

        /**
         * 进行入栈操作  在本线程中
         * @param item
         */
        private void pushNow(DefaultHandle<?> item) {
            //已经被回收了  每次 回收 都会将 recycleId 自增1
            if ((item.recycleId | item.lastRecycledId) != 0) {
                throw new IllegalStateException("recycled already");
            }
            //这里就是代表被回收了
            item.recycleId = item.lastRecycledId = OWN_THREAD_ID;

            int size = this.size;
            //超过 允许 保存的最大值 或者 该item 超过 回收次数了 放弃本次 回收
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
         * 回收从其他线程拿到的 对象
         * @param item
         * @param thread
         */
        private void pushLater(DefaultHandle<?> item, Thread thread) {
            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();
            //尝试 从 map 中获取  stack 关联的 queue 对象
            WeakOrderQueue queue = delayedRecycled.get(this);
            if (queue == null) {
                //不能再存放了 就 保存一个空的 对象 假装完成了回收
                if (delayedRecycled.size() >= maxDelayedQueues) {
                    // Add a dummy queue so we know we should drop the object
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);
                    return;
                }
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                // 分配 指定 空间来生成queue 对象
                if ((queue = WeakOrderQueue.allocate(this, thread)) == null) {
                    // drop object
                    return;
                }
                //生成映射关系
                delayedRecycled.put(this, queue);
                //如果 存在 映射 并且是 空对象 直接返回 假装完成添加
            } else if (queue == WeakOrderQueue.DUMMY) {
                // drop object
                return;
            }

            //将元素 保存到queue 中
            queue.add(item);
        }

        /**
         * 判断该对象是否不能被回收
         * @param handle
         * @return
         */
        boolean dropHandle(DefaultHandle<?> handle) {
            if (!handle.hasBeenRecycled) {
                //未回收的 情况 如果 回收次数 超过某个值 放弃对该对象的回收
                if ((++handleRecycleCount & ratioMask) != 0) {
                    // Drop the object.
                    return true;
                }
                //代表 成功回收
                handle.hasBeenRecycled = true;
            }
            //已经被回收的情况 直接返回false  就不需要判断了
            return false;
        }

        DefaultHandle<T> newHandle() {
            return new DefaultHandle<T>(this);
        }
    }
}
