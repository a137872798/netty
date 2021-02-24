/*
 * Copyright 2014 The Netty Project
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

import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.PlatformDependent;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * A special variant of {@link ThreadLocal} that yields higher access performance when accessed from a
 * {@link FastThreadLocalThread}.
 * <p>
 * Internally, a {@link FastThreadLocal} uses a constant index in an array, instead of using hash code and hash table,
 * to look for a variable.  Although seemingly very subtle, it yields slight performance advantage over using a hash
 * table, and it is useful when accessed frequently.
 * </p><p>
 * To take advantage of this thread-local variable, your thread must be a {@link FastThreadLocalThread} or its subtype.
 * By default, all threads created by {@link DefaultThreadFactory} are {@link FastThreadLocalThread} due to this reason.
 * </p><p>
 * Note that the fast path is only possible on threads that extend {@link FastThreadLocalThread}, because it requires
 * a special field to store the necessary state.  An access by any other kind of thread falls back to a regular
 * {@link ThreadLocal}.
 * </p>
 *
 * @param <V> the type of the thread-local variable
 * @see ThreadLocal
 *
 * 优化了jdk原生的本地线程变量
 */
public class FastThreadLocal<V> {

    private static final int variablesToRemoveIndex = InternalThreadLocalMap.nextVariableIndex();

    /**
     * Removes all {@link FastThreadLocal} variables bound to the current thread.  This operation is useful when you
     * are in a container environment, and you don't want to leave the thread local variables in the threads you do not
     * manage.
     *
     * 当本线程任务执行完毕后 就可以清理所有私有变量了
     */
    public static void removeAll() {
        //获取本线程 对应的容器对象
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap == null) {
            return;
        }

        try {
            // 代表从0开始清理数据
            Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);
            // 代表在这个位置设置了数据
            if (v != null && v != InternalThreadLocalMap.UNSET) {
                // 在第一个slot中插入的是哨兵对象 相当于是一个位图
                @SuppressWarnings("unchecked")
                Set<FastThreadLocal<?>> variablesToRemove = (Set<FastThreadLocal<?>>) v;
                FastThreadLocal<?>[] variablesToRemoveArray =
                        variablesToRemove.toArray(new FastThreadLocal[0]);
                //转换成 数组 依次 移除
                for (FastThreadLocal<?> tlv: variablesToRemoveArray) {
                    tlv.remove(threadLocalMap);
                }
            }
        } finally {
            // 将map字段置空
            InternalThreadLocalMap.remove();
        }
    }

    /**
     * Returns the number of thread local variables bound to the current thread.
     */
    public static int size() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap == null) {
            return 0;
        } else {
            return threadLocalMap.size();
        }
    }

    /**
     * Destroys the data structure that keeps all {@link FastThreadLocal} variables accessed from
     * non-{@link FastThreadLocalThread}s.  This operation is useful when you are in a container environment, and you
     * do not want to leave the thread local variables in the threads you do not manage.  Call this method when your
     * application is being unloaded from the container.
     *
     * 从JDK ThreadLocal 中移除 该map 对象
     */
    public static void destroy() {
        InternalThreadLocalMap.destroy();
    }

    /**
     * 标记某个槽被使用 便于清理时快速定位到要处理的槽
     * @param threadLocalMap
     * @param variable
     */
    @SuppressWarnings("unchecked")
    private static void addToVariablesToRemove(InternalThreadLocalMap threadLocalMap, FastThreadLocal<?> variable) {
        //哨兵对象所在的槽
        Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);
        Set<FastThreadLocal<?>> variablesToRemove;
        // 初始化哨兵对象
        if (v == InternalThreadLocalMap.UNSET || v == null) {
            variablesToRemove = Collections.newSetFromMap(new IdentityHashMap<FastThreadLocal<?>, Boolean>());
            threadLocalMap.setIndexedVariable(variablesToRemoveIndex, variablesToRemove);
        } else {
            variablesToRemove = (Set<FastThreadLocal<?>>) v;
        }

        variablesToRemove.add(variable);
    }

    /**
     * 从哨兵中移除变量
     */
    private static void removeFromVariablesToRemove(
            InternalThreadLocalMap threadLocalMap, FastThreadLocal<?> variable) {

        Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);

        if (v == InternalThreadLocalMap.UNSET || v == null) {
            return;
        }

        @SuppressWarnings("unchecked")
        Set<FastThreadLocal<?>> variablesToRemove = (Set<FastThreadLocal<?>>) v;
        variablesToRemove.remove(variable);
    }

    /**
     * 每个线程私有变量在创建时 会在本线程对应的InternalThreadLocalMap中被分配一个下标
     */
    private final int index;

    public FastThreadLocal() {
        index = InternalThreadLocalMap.nextVariableIndex();
    }

    /**
     * Returns the current value for the current thread
     *
     * 获取该对象保留的对象
     */
    @SuppressWarnings("unchecked")
    public final V get() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
        // 检测下标对应的位置是否已经设置了数据
        Object v = threadLocalMap.indexedVariable(index);
        //存在值 直接返回
        if (v != InternalThreadLocalMap.UNSET) {
            return (V) v;
        }

        //初始化 同时会在哨兵对象中设置
        V value = initialize(threadLocalMap);
        //在map级别的清理位图中做标记
        registerCleaner(threadLocalMap);
        return value;
    }

    /**
     * 注册清理  这个方法相当于已经被废除了  核心的清理线程逻辑被去掉了
     * @param threadLocalMap
     */
    private void registerCleaner(final InternalThreadLocalMap threadLocalMap) {
        Thread current = Thread.currentThread();
        // 如果本线程设置的runnbale 已经被包装过 也就是在执行完后会自动清理线程私有变量 或者已经在map级别设置了清理标识 就不需要处理
        if (FastThreadLocalThread.willCleanupFastThreadLocals(current) || threadLocalMap.isCleanerFlagSet(index)) {
            return;
        }

        // 在map级别设置清理标识
        threadLocalMap.setCleanerFlag(index);

        // TODO: We need to find a better way to handle this.
        /*
        // We will need to ensure we will trigger remove(InternalThreadLocalMap) so everything will be released
        // and FastThreadLocal.onRemoval(...) will be called.
        ObjectCleaner.register(current, new Runnable() {
            @Override
            public void run() {
                remove(threadLocalMap);

                // It's fine to not call InternalThreadLocalMap.remove() here as this will only be triggered once
                // the Thread is collected by GC. In this case the ThreadLocal will be gone away already.
            }
        });
        */
    }

    /**
     * Returns the current value for the specified thread local map.
     * The specified thread local map must be for the current thread.
     */
    @SuppressWarnings("unchecked")
    public final V get(InternalThreadLocalMap threadLocalMap) {
        Object v = threadLocalMap.indexedVariable(index);
        if (v != InternalThreadLocalMap.UNSET) {
            return (V) v;
        }

        return initialize(threadLocalMap);
    }

    /**
     * 初始化
     * @param threadLocalMap
     * @return
     */
    private V initialize(InternalThreadLocalMap threadLocalMap) {
        V v = null;
        try {
            v = initialValue();
        } catch (Exception e) {
            PlatformDependent.throwException(e);
        }

        threadLocalMap.setIndexedVariable(index, v);
        // 本线程对应私有变量map在这个槽中设置了变量 就要打上一个待移除标记 这样当线程要被回收时 就要做清理工作
        addToVariablesToRemove(threadLocalMap, this);
        return v;
    }

    /**
     * Set the value for the current thread.
     * 将属性保存到 本地线程变量
     */
    public final void set(V value) {
        if (value != InternalThreadLocalMap.UNSET) {
            //获取 本线程的 本地变量 map
            InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
            //为指定容器设置 value
            if (setKnownNotUnset(threadLocalMap, value)) {
                //增加 clean标识
                registerCleaner(threadLocalMap);
            }
        } else {
            //空对象就代表移除
            remove();
        }
    }

    /**
     * Set the value for the specified thread local map. The specified thread local map must be for the current thread.
     */
    public final void set(InternalThreadLocalMap threadLocalMap, V value) {
        if (value != InternalThreadLocalMap.UNSET) {
            setKnownNotUnset(threadLocalMap, value);
        } else {
            remove(threadLocalMap);
        }
    }

    /**
     * @return see {@link InternalThreadLocalMap#setIndexedVariable(int, Object)}.
     * 为指定容器设置value
     */
    private boolean setKnownNotUnset(InternalThreadLocalMap threadLocalMap, V value) {

        if (threadLocalMap.setIndexedVariable(index, value)) {

            addToVariablesToRemove(threadLocalMap, this);
            return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if and only if this thread-local variable is set.
     */
    public final boolean isSet() {
        return isSet(InternalThreadLocalMap.getIfSet());
    }

    /**
     * Returns {@code true} if and only if this thread-local variable is set.
     * The specified thread local map must be for the current thread.
     */
    public final boolean isSet(InternalThreadLocalMap threadLocalMap) {
        return threadLocalMap != null && threadLocalMap.isIndexedVariableSet(index);
    }
    /**
     * Sets the value to uninitialized; a proceeding call to get() will trigger a call to initialValue().
     * 将私有变量从map中移除
     */
    public final void remove() {
        remove(InternalThreadLocalMap.getIfSet());
    }

    /**
     * Sets the value to uninitialized for the specified thread local map;
     * a proceeding call to get() will trigger a call to initialValue().
     * The specified thread local map must be for the current thread.
     * 将线程私有变量从本线程关联的map中移除
     */
    @SuppressWarnings("unchecked")
    public final void remove(InternalThreadLocalMap threadLocalMap) {
        if (threadLocalMap == null) {
            return;
        }

        //重置成 unset
        Object v = threadLocalMap.removeIndexedVariable(index);
        //从哨兵中移除该私有变量
        removeFromVariablesToRemove(threadLocalMap, this);

        //代表原来不是 Unset
        if (v != InternalThreadLocalMap.UNSET) {
            try {
                // 当对象被释放后 可能要做一些清理操作
                onRemoval((V) v);
            } catch (Exception e) {
                PlatformDependent.throwException(e);
            }
        }
    }

    /**
     * Returns the initial value for this thread-local variable.
     */
    protected V initialValue() throws Exception {
        return null;
    }

    /**
     * Invoked when this thread local variable is removed by {@link #remove()}. Be aware that {@link #remove()}
     * is not guaranteed to be called when the `Thread` completes which means you can not depend on this for
     * cleanup of the resources in the case of `Thread` completion.
     */
    protected void onRemoval(@SuppressWarnings("UnusedParameters") V value) throws Exception { }
}
