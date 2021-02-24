/*
 * Copyright 2016 The Netty Project
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
package io.netty.handler.codec;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.MathUtil;

import java.util.AbstractList;
import java.util.RandomAccess;

import static io.netty.util.internal.ObjectUtil.checkNotNull;

/**
 * Special {@link AbstractList} implementation which is used within our codec base classes.
 * 一个 可回收重复使用的List 对象 用来 帮助编解码
 */
final class CodecOutputList extends AbstractList<Object> implements RandomAccess {

    private static final CodecOutputListRecycler NOOP_RECYCLER = new CodecOutputListRecycler() {
        @Override
        public void recycle(CodecOutputList object) {
            // drop on the floor and let the GC handle it.
        }
    };

    /**
     * 使用线程本地变量的方式
     */
    private static final FastThreadLocal<CodecOutputLists> CODEC_OUTPUT_LISTS_POOL =
            new FastThreadLocal<CodecOutputLists>() {
                @Override
                protected CodecOutputLists initialValue() throws Exception {
                    // 16 CodecOutputList per Thread are cached.
                    return new CodecOutputLists(16);
                }
            };

    /**
     * 私有接口 可以回收 CodecOutputList对象
     */
    private interface CodecOutputListRecycler {
        void recycle(CodecOutputList codecOutputList);
    }

    /**
     * 应该是考虑到单个线程可能会需要多个CodecOutputList
     */
    private static final class CodecOutputLists implements CodecOutputListRecycler {
        //代表 可缓存的 List 对象
        private final CodecOutputList[] elements;
        private final int mask;

        private int currentIdx;
        private int count;

        /**
         * 初始化时 内部有16个CodecOutputList
         * @param numElements
         */
        CodecOutputLists(int numElements) {
            //初始化  长度 向上取2的幂次
            elements = new CodecOutputList[MathUtil.safeFindNextPositivePowerOfTwo(numElements)];
            for (int i = 0; i < elements.length; ++i) {
                // Size of 16 should be good enough for the majority of all users as an initial capacity.
                elements[i] = new CodecOutputList(this, 16);
            }
            //记录长度的
            count = elements.length;
            //起始下标
            currentIdx = elements.length;
            //掩码 为总长度 -1
            mask = elements.length - 1;
        }

        /**
         * @return
         */
        public CodecOutputList getOrCreate() {
            // 超量不会被缓存
            if (count == 0) {
                // Return a new CodecOutputList which will not be cached. We use a size of 4 to keep the overhead
                // low.
                return new CodecOutputList(NOOP_RECYCLER, 4);
            }
            --count;

            // 返回某个list
            int idx = (currentIdx - 1) & mask;
            CodecOutputList list = elements[idx];
            currentIdx = idx;
            return list;
        }

        /**
         * 将List 归还到 Lists 对象数组中
         * @param codecOutputList
         */
        @Override
        public void recycle(CodecOutputList codecOutputList) {
            int idx = currentIdx;
            elements[idx] = codecOutputList;
            currentIdx = (idx + 1) & mask;
            ++count;
            assert count <= elements.length;
        }
    }

    /**
     * 从本地线程变量中 获取
     * @return
     */
    static CodecOutputList newInstance() {
        return CODEC_OUTPUT_LISTS_POOL.get().getOrCreate();
    }

    private final CodecOutputListRecycler recycler;
    private int size;
    /**
     * 因为基于本地线程变量来做 所以不需要使用并发容器
     */
    private Object[] array;
    /**
     * 在被回收前是否有插入过数据 也就是代表是否还有数据未处理
     */
    private boolean insertSinceRecycled;

    private CodecOutputList(CodecOutputListRecycler recycler, int size) {
        this.recycler = recycler;
        array = new Object[size];
    }

    @Override
    public Object get(int index) {
        checkIndex(index);
        return array[index];
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean add(Object element) {
        checkNotNull(element, "element");
        try {
            insert(size, element);
        } catch (IndexOutOfBoundsException ignore) {
            // This should happen very infrequently so we just catch the exception and try again.
            expandArray();
            insert(size, element);
        }
        ++ size;
        return true;
    }

    @Override
    public Object set(int index, Object element) {
        checkNotNull(element, "element");
        checkIndex(index);

        Object old = array[index];
        insert(index, element);
        return old;
    }

    @Override
    public void add(int index, Object element) {
        checkNotNull(element, "element");
        checkIndex(index);

        if (size == array.length) {
            expandArray();
        }

        if (index != size) {
            System.arraycopy(array, index, array, index + 1, size - index);
        }

        insert(index, element);
        ++ size;
    }

    @Override
    public Object remove(int index) {
        checkIndex(index);
        Object old = array[index];

        int len = size - index - 1;
        if (len > 0) {
            System.arraycopy(array, index + 1, array, index, len);
        }
        array[-- size] = null;

        return old;
    }

    @Override
    public void clear() {
        // We only set the size to 0 and not null out the array. Null out the array will explicit requested by
        // calling recycle()
        size = 0;
    }

    /**
     * Returns {@code true} if any elements where added or set. This will be reset once {@link #recycle()} was called.
     */
    boolean insertSinceRecycled() {
        return insertSinceRecycled;
    }

    /**
     * Recycle the array which will clear it and null out all entries in the internal storage.
     * 重置属性并进行回收
     */
    void recycle() {
        for (int i = 0 ; i < size; i ++) {
            array[i] = null;
        }
        size = 0;
        insertSinceRecycled = false;

        recycler.recycle(this);
    }

    /**
     * Returns the element on the given index. This operation will not do any range-checks and so is considered unsafe.
     */
    Object getUnsafe(int index) {
        return array[index];
    }

    private void checkIndex(int index) {
        if (index >= size) {
            throw new IndexOutOfBoundsException();
        }
    }

    private void insert(int index, Object element) {
        array[index] = element;
        insertSinceRecycled = true;
    }

    private void expandArray() {
        // double capacity
        int newCapacity = array.length << 1;

        if (newCapacity < 0) {
            throw new OutOfMemoryError();
        }

        Object[] newArray = new Object[newCapacity];
        System.arraycopy(array, 0, newArray, 0, array.length);

        array = newArray;
    }
}
