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
package io.netty.buffer;

import io.netty.util.internal.PlatformDependent;

import java.nio.ByteBuffer;

/**
 * 非池化 非unsafe 直接内存的包装对象
 */
final class WrappedUnpooledUnsafeDirectByteBuf extends UnpooledUnsafeDirectByteBuf {

    /**
     * 根据给定的 内存地址 使用 directBuffer 的 构造器对象 直接创建directBytebuffer 对象 这样不会有问题吗???
     * @param alloc
     * @param memoryAddress
     * @param size
     * @param doFree
     */
    WrappedUnpooledUnsafeDirectByteBuf(ByteBufAllocator alloc, long memoryAddress, int size, boolean doFree) {
        super(alloc, PlatformDependent.directBuffer(memoryAddress, size), size, doFree);
    }

    @Override
    protected void freeDirect(ByteBuffer buffer) {
        //使用unsafe 进行内存释放 而不是使用clean
        PlatformDependent.freeMemory(memoryAddress);
    }
}
