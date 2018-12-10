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

package io.netty.buffer;

import io.netty.util.Recycler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;

/**
 * 池化直接内存bytebuf  使用的T 为 JDK bytebuffer 对象
 */
final class PooledDirectByteBuf extends PooledByteBuf<ByteBuffer> {

    /**
     * 对应的 recyle对象
     */
    private static final Recycler<PooledDirectByteBuf> RECYCLER = new Recycler<PooledDirectByteBuf>() {
        @Override
        protected PooledDirectByteBuf newObject(Handle<PooledDirectByteBuf> handle) {
            return new PooledDirectByteBuf(handle, 0);
        }
    };

    /**
     * 从 recycle中获取 对象 并开始 重置属性(reuse)
     * @param maxCapacity
     * @return
     */
    static PooledDirectByteBuf newInstance(int maxCapacity) {
        PooledDirectByteBuf buf = RECYCLER.get();
        buf.reuse(maxCapacity);
        return buf;
    }

    private PooledDirectByteBuf(Recycler.Handle<PooledDirectByteBuf> recyclerHandle, int maxCapacity) {
        super(recyclerHandle, maxCapacity);
    }

    /**
     * 对应到 父类的 创建临时对象
     * @param memory
     * @return
     */
    @Override
    protected ByteBuffer newInternalNioBuffer(ByteBuffer memory) {
        return memory.duplicate();
    }

    /**
     * 是否是 直接内存
     * @return
     */
    @Override
    public boolean isDirect() {
        return true;
    }

    /**
     * 因为是对外内存 所以要获取 下标 需要借助 offset
     * @param index
     * @return
     */
    @Override
    protected byte _getByte(int index) {
        return memory.get(idx(index));
    }

    @Override
    protected short _getShort(int index) {
        return memory.getShort(idx(index));
    }

    @Override
    protected short _getShortLE(int index) {
        return ByteBufUtil.swapShort(_getShort(index));
    }

    @Override
    protected int _getUnsignedMedium(int index) {
        index = idx(index);
        return (memory.get(index) & 0xff)     << 16 |
               (memory.get(index + 1) & 0xff) << 8  |
               memory.get(index + 2) & 0xff;
    }

    @Override
    protected int _getUnsignedMediumLE(int index) {
        index = idx(index);
        return memory.get(index)      & 0xff        |
               (memory.get(index + 1) & 0xff) << 8  |
               (memory.get(index + 2) & 0xff) << 16;
    }

    @Override
    protected int _getInt(int index) {
        return memory.getInt(idx(index));
    }

    @Override
    protected int _getIntLE(int index) {
        return ByteBufUtil.swapInt(_getInt(index));
    }

    @Override
    protected long _getLong(int index) {
        return memory.getLong(idx(index));
    }

    @Override
    protected long _getLongLE(int index) {
        return ByteBufUtil.swapLong(_getLong(index));
    }

    /**
     * 获取bytes
     * @param index
     * @param dst
     * @param dstIndex the first index of the destination
     * @param length   the number of bytes to transfer
     *
     * @return
     */
    @Override
    public ByteBuf getBytes(int index, ByteBuf dst, int dstIndex, int length) {
        //检验传入的 下标是否 合法
        checkDstIndex(index, length, dstIndex, dst.capacity());
        //判断 目标 buf 是否是 byte[]
        if (dst.hasArray()) {
            getBytes(index, dst.array(), dst.arrayOffset() + dstIndex, length);
        } else if (dst.nioBufferCount() > 0) {
            for (ByteBuffer bb: dst.nioBuffers(dstIndex, length)) {
                int bbLen = bb.remaining();
                //将数据 读取到 bb 中
                getBytes(index, bb);
                index += bbLen;
            }
        } else {
            dst.setBytes(dstIndex, this, index, length);
        }
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, byte[] dst, int dstIndex, int length) {
        getBytes(index, dst, dstIndex, length, false);
        return this;
    }

    /**
     * 将数据 读取到 dst中
     * @param index
     * @param dst
     * @param dstIndex
     * @param length
     * @param internal
     */
    private void getBytes(int index, byte[] dst, int dstIndex, int length, boolean internal) {
        checkDstIndex(index, length, dstIndex, dst.length);
        ByteBuffer tmpBuf;
        //是否使用临时 nioBuffer对象
        if (internal) {
            tmpBuf = internalNioBuffer();
        } else {
            tmpBuf = memory.duplicate();
        }
        //计算真实下标
        index = idx(index);
        //设置 指针
        tmpBuf.clear().position(index).limit(index + length);
        //将数据 读到 dst 中
        tmpBuf.get(dst, dstIndex, length);
    }

    @Override
    public ByteBuf readBytes(byte[] dst, int dstIndex, int length) {
        checkReadableBytes(length);
        getBytes(readerIndex, dst, dstIndex, length, true);
        readerIndex += length;
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, ByteBuffer dst) {
        getBytes(index, dst, false);
        return this;
    }

    private void getBytes(int index, ByteBuffer dst, boolean internal) {
        checkIndex(index, dst.remaining());
        ByteBuffer tmpBuf;
        if (internal) {
            tmpBuf = internalNioBuffer();
        } else {
            tmpBuf = memory.duplicate();
        }
        index = idx(index);
        tmpBuf.clear().position(index).limit(index + dst.remaining());
        dst.put(tmpBuf);
    }

    @Override
    public ByteBuf readBytes(ByteBuffer dst) {
        int length = dst.remaining();
        checkReadableBytes(length);
        getBytes(readerIndex, dst, true);
        readerIndex += length;
        return this;
    }

    @Override
    public ByteBuf getBytes(int index, OutputStream out, int length) throws IOException {
        getBytes(index, out, length, false);
        return this;
    }

    private void getBytes(int index, OutputStream out, int length, boolean internal) throws IOException {
        checkIndex(index, length);
        if (length == 0) {
            return;
        }
        ByteBufUtil.readBytes(alloc(), internal ? internalNioBuffer() : memory.duplicate(), idx(index), length, out);
    }

    @Override
    public ByteBuf readBytes(OutputStream out, int length) throws IOException {
        checkReadableBytes(length);
        getBytes(readerIndex, out, length, true);
        readerIndex += length;
        return this;
    }

    @Override
    public int getBytes(int index, GatheringByteChannel out, int length) throws IOException {
        return getBytes(index, out, length, false);
    }

    private int getBytes(int index, GatheringByteChannel out, int length, boolean internal) throws IOException {
        checkIndex(index, length);
        if (length == 0) {
            return 0;
        }

        ByteBuffer tmpBuf;
        if (internal) {
            tmpBuf = internalNioBuffer();
        } else {
            tmpBuf = memory.duplicate();
        }
        index = idx(index);
        tmpBuf.clear().position(index).limit(index + length);
        return out.write(tmpBuf);
    }

    @Override
    public int getBytes(int index, FileChannel out, long position, int length) throws IOException {
        return getBytes(index, out, position, length, false);
    }

    private int getBytes(int index, FileChannel out, long position, int length, boolean internal) throws IOException {
        checkIndex(index, length);
        if (length == 0) {
            return 0;
        }

        ByteBuffer tmpBuf = internal ? internalNioBuffer() : memory.duplicate();
        index = idx(index);
        tmpBuf.clear().position(index).limit(index + length);
        return out.write(tmpBuf, position);
    }

    @Override
    public int readBytes(GatheringByteChannel out, int length) throws IOException {
        checkReadableBytes(length);
        int readBytes = getBytes(readerIndex, out, length, true);
        readerIndex += readBytes;
        return readBytes;
    }

    @Override
    public int readBytes(FileChannel out, long position, int length) throws IOException {
        checkReadableBytes(length);
        int readBytes = getBytes(readerIndex, out, position, length, true);
        readerIndex += readBytes;
        return readBytes;
    }

    @Override
    protected void _setByte(int index, int value) {
        memory.put(idx(index), (byte) value);
    }

    @Override
    protected void _setShort(int index, int value) {
        memory.putShort(idx(index), (short) value);
    }

    @Override
    protected void _setShortLE(int index, int value) {
        _setShort(index, ByteBufUtil.swapShort((short) value));
    }

    @Override
    protected void _setMedium(int index, int value) {
        index = idx(index);
        memory.put(index, (byte) (value >>> 16));
        memory.put(index + 1, (byte) (value >>> 8));
        memory.put(index + 2, (byte) value);
    }

    @Override
    protected void _setMediumLE(int index, int value) {
        index = idx(index);
        memory.put(index, (byte) value);
        memory.put(index + 1, (byte) (value >>> 8));
        memory.put(index + 2, (byte) (value >>> 16));
    }

    @Override
    protected void _setInt(int index, int value) {
        memory.putInt(idx(index), value);
    }

    @Override
    protected void _setIntLE(int index, int value) {
        _setInt(index, ByteBufUtil.swapInt(value));
    }

    @Override
    protected void _setLong(int index, long value) {
        memory.putLong(idx(index), value);
    }

    @Override
    protected void _setLongLE(int index, long value) {
        _setLong(index, ByteBufUtil.swapLong(value));
    }

    @Override
    public ByteBuf setBytes(int index, ByteBuf src, int srcIndex, int length) {
        checkSrcIndex(index, length, srcIndex, src.capacity());
        if (src.hasArray()) {
            setBytes(index, src.array(), src.arrayOffset() + srcIndex, length);
        } else if (src.nioBufferCount() > 0) {
            for (ByteBuffer bb: src.nioBuffers(srcIndex, length)) {
                int bbLen = bb.remaining();
                setBytes(index, bb);
                index += bbLen;
            }
        } else {
            src.getBytes(srcIndex, this, index, length);
        }
        return this;
    }

    @Override
    public ByteBuf setBytes(int index, byte[] src, int srcIndex, int length) {
        checkSrcIndex(index, length, srcIndex, src.length);
        ByteBuffer tmpBuf = internalNioBuffer();
        index = idx(index);
        tmpBuf.clear().position(index).limit(index + length);
        tmpBuf.put(src, srcIndex, length);
        return this;
    }

    @Override
    public ByteBuf setBytes(int index, ByteBuffer src) {
        checkIndex(index, src.remaining());
        ByteBuffer tmpBuf = internalNioBuffer();
        if (src == tmpBuf) {
            src = src.duplicate();
        }

        index = idx(index);
        tmpBuf.clear().position(index).limit(index + src.remaining());
        tmpBuf.put(src);
        return this;
    }

    @Override
    public int setBytes(int index, InputStream in, int length) throws IOException {
        checkIndex(index, length);
        byte[] tmp = ByteBufUtil.threadLocalTempArray(length);
        int readBytes = in.read(tmp, 0, length);
        if (readBytes <= 0) {
            return readBytes;
        }
        ByteBuffer tmpBuf = internalNioBuffer();
        tmpBuf.clear().position(idx(index));
        tmpBuf.put(tmp, 0, readBytes);
        return readBytes;
    }

    @Override
    public int setBytes(int index, ScatteringByteChannel in, int length) throws IOException {
        checkIndex(index, length);
        ByteBuffer tmpBuf = internalNioBuffer();
        index = idx(index);
        tmpBuf.clear().position(index).limit(index + length);
        try {
            return in.read(tmpBuf);
        } catch (ClosedChannelException ignored) {
            return -1;
        }
    }

    @Override
    public int setBytes(int index, FileChannel in, long position, int length) throws IOException {
        checkIndex(index, length);
        ByteBuffer tmpBuf = internalNioBuffer();
        index = idx(index);
        tmpBuf.clear().position(index).limit(index + length);
        try {
            return in.read(tmpBuf, position);
        } catch (ClosedChannelException ignored) {
            return -1;
        }
    }

    /**
     * 创建 一个 副本对象
     * @param index
     * @param length
     * @return
     */
    @Override
    public ByteBuf copy(int index, int length) {
        checkIndex(index, length);
        //分配一个 新的 bytebuf 对象
        ByteBuf copy = alloc().directBuffer(length, maxCapacity());
        copy.writeBytes(this, index, length);
        return copy;
    }

    @Override
    public int nioBufferCount() {
        return 1;
    }

    /**
     * 创建 分片对象
     * @param index
     * @param length
     * @return
     */
    @Override
    public ByteBuffer nioBuffer(int index, int length) {
        checkIndex(index, length);
        index = idx(index);
        return ((ByteBuffer) memory.duplicate().position(index).limit(index + length)).slice();
    }

    @Override
    public ByteBuffer[] nioBuffers(int index, int length) {
        return new ByteBuffer[] { nioBuffer(index, length) };
    }

    @Override
    public ByteBuffer internalNioBuffer(int index, int length) {
        checkIndex(index, length);
        index = idx(index);
        return (ByteBuffer) internalNioBuffer().clear().position(index).limit(index + length);
    }

    @Override
    public boolean hasArray() {
        return false;
    }

    @Override
    public byte[] array() {
        throw new UnsupportedOperationException("direct buffer");
    }

    @Override
    public int arrayOffset() {
        throw new UnsupportedOperationException("direct buffer");
    }

    /**
     * 这样代表 该 bytbuf 不是 由 unsafe 创建的
     * @return
     */
    @Override
    public boolean hasMemoryAddress() {
        return false;
    }

    @Override
    public long memoryAddress() {
        throw new UnsupportedOperationException();
    }
}
