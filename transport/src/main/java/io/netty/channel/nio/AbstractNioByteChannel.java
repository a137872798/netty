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
package io.netty.channel.nio;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.FileRegion;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.internal.ChannelUtils;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.util.internal.StringUtil;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

import static io.netty.channel.internal.ChannelUtils.WRITE_STATUS_SNDBUF_FULL;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on bytes.
 * channel 端的 channel
 */
public abstract class AbstractNioByteChannel extends AbstractNioChannel {
    private static final ChannelMetadata METADATA = new ChannelMetadata(false, 16);
    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ", " +
            StringUtil.simpleClassName(FileRegion.class) + ')';

    private final Runnable flushTask = new Runnable() {
        @Override
        public void run() {
            // Calling flush0 directly to ensure we not try to flush messages that were added via write(...) in the
            // meantime.
            ((AbstractNioUnsafe) unsafe()).flush0();
        }
    };
    private boolean inputClosedSeenErrorOnRead;

    /**
     * Create a new instance
     *
     * @param parent            the parent {@link Channel} by which this instance was created. May be {@code null}
     * @param ch                the underlying {@link SelectableChannel} on which it operates
     */
    protected AbstractNioByteChannel(Channel parent, SelectableChannel ch) {
        //客户端 channel 注册 read事件
        super(parent, ch, SelectionKey.OP_READ);
    }

    /**
     * Shutdown the input side of the channel.
     */
    protected abstract ChannelFuture shutdownInput();

    protected boolean isInputShutdown0() {
        return false;
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioByteUnsafe();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    final boolean shouldBreakReadReady(ChannelConfig config) {
        return isInputShutdown0() && (inputClosedSeenErrorOnRead || !isAllowHalfClosure(config));
    }

    private static boolean isAllowHalfClosure(ChannelConfig config) {
        return config instanceof SocketChannelConfig &&
                ((SocketChannelConfig) config).isAllowHalfClosure();
    }

    /**
     * client 的 读取事件 也就是 读取数据  server 接受到新连接后 也是创建NioSocketChannel 然后用这里 读取 client 端相同 JDK channel 发送来的数据
     */
    protected class NioByteUnsafe extends AbstractNioUnsafe {

        /**
         * 出现异常情况 关闭读事件
         * @param pipeline
         */
        private void closeOnRead(ChannelPipeline pipeline) {
            //判断JDK channel 是否已经被关闭
            if (!isInputShutdown0()) {
                //如果允许半关闭 好像是说 客户端关闭了 是否要关闭服务端对应的 channel 对象
                if (isAllowHalfClosure(config())) {
                    //这个是针对JDKchannel 的 方法 暂时看不懂  作用是 暂时关闭读取而不关闭channel 能恢复吗???在什么时机回复???
                    //Shutdown the connection for reading without closing the channel
                    shutdownInput();
                    pipeline.fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                } else {
                    //直接进行关闭
                    close(voidPromise());
                }
                //关闭的情况触发用户自定义事件
            } else {
                //已经关闭的 情况 设置标识  下次就会取消 READ 事件
                inputClosedSeenErrorOnRead = true;
                pipeline.fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);
            }
        }

        /**
         * 处理读取异常
         * @param pipeline
         * @param byteBuf
         * @param cause
         * @param close
         * @param allocHandle
         */
        private void handleReadException(ChannelPipeline pipeline, ByteBuf byteBuf, Throwable cause, boolean close,
                RecvByteBufAllocator.Handle allocHandle) {
            if (byteBuf != null) {
                //出现异常的 情况 还是可读的就继续读取
                if (byteBuf.isReadable()) {
                    readPending = false;
                    pipeline.fireChannelRead(byteBuf);
                } else {
                    //不能读就释放
                    byteBuf.release();
                }
            }
            //记录本次读取信息
            allocHandle.readComplete();
            //触发2个事件
            pipeline.fireChannelReadComplete();
            pipeline.fireExceptionCaught(cause);
            if (close || cause instanceof IOException) {
                //如果是IO异常 关闭读事件
                closeOnRead(pipeline);
            }
        }

        /**
         * 客户端读取数据
         */
        @Override
        public final void read() {
            final ChannelConfig config = config();
            //对应到 JDK channel 关闭 或者关闭了读事件 对应  closeOnRead
            if (shouldBreakReadReady(config)) {
                //取消select上的 read 事件
                clearReadPending();
                return;
            }
            final ChannelPipeline pipeline = pipeline();
            final ByteBufAllocator allocator = config.getAllocator();
            final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle();
            allocHandle.reset(config);

            ByteBuf byteBuf = null;
            boolean close = false;
            try {
                do {
                    byteBuf = allocHandle.allocate(allocator);
                    //内层：将数据读取到bytebuf中  外层：更新 读取的 下标
                    allocHandle.lastBytesRead(doReadBytes(byteBuf));
                    //代表没数据可读
                    if (allocHandle.lastBytesRead() <= 0) {
                        // nothing was read. release the buffer.
                        // 释放对象
                        byteBuf.release();
                        byteBuf = null;
                        //这里代表 对端被关闭了 所以 OP_READ 事件返回的 是 -1
                        close = allocHandle.lastBytesRead() < 0;
                        if (close) {
                            // There is nothing left to read as we received an EOF.
                            //代表结束了读取状态
                            readPending = false;
                        }
                        break;
                    }

                    //增加读取的消息数  一次只增加1
                    allocHandle.incMessagesRead(1);
                    readPending = false;
                    //触发 读取 所以每次 触发 ChannelRead的 一定是 bytebuf对象
                    pipeline.fireChannelRead(byteBuf);
                    //调用链触发完后 置空(在 tail 节点会进行释放)
                    byteBuf = null;
                } while (allocHandle.continueReading());

                //触发2个 事件
                //会记录本次 读取的数据
                allocHandle.readComplete();
                //触发handler
                pipeline.fireChannelReadComplete();

                if (close) {
                    closeOnRead(pipeline);
                }
            } catch (Throwable t) {
                handleReadException(pipeline, byteBuf, t, close, allocHandle);
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                // 如果读取被关闭就 取消该事件
                if (!readPending && !config.isAutoRead()) {
                    removeReadOp();
                }
            }
        }
    }

    /**
     * Write objects to the OS.
     * @param in the collection which contains objects to write.
     * @return The value that should be decremented from the write quantum which starts at
     * {@link ChannelConfig#getWriteSpinCount()}. The typical use cases are as follows:
     * <ul>
     *     <li>0 - if no write was attempted. This is appropriate if an empty {@link ByteBuf} (or other empty content)
     *     is encountered</li>
     *     <li>1 - if a single call to write data was made to the OS</li>
     *     <li>{@link ChannelUtils#WRITE_STATUS_SNDBUF_FULL} - if an attempt to write data was made to the OS, but no
     *     data was accepted</li>
     * </ul>
     * @throws Exception if an I/O exception occurs during write.
     */
    protected final int doWrite0(ChannelOutboundBuffer in) throws Exception {
        Object msg = in.current();
        //这里代表全部flush 了 有种情况 就是 待flush 的数据没有全部写入到nioBuffer 中 那么 虽然nioBufferCnt 为0 了但是 还是存在
        //flushed 的entry
        if (msg == null) {
            // Directly return here so incompleteWrite(...) is not called.
            return 0;
        }
        //代表 还有 flushed 的 entry
        return doWriteInternal(in, in.current());
    }

    /**
     * 处理 剩下的 flushed entry
     * @param in
     * @param msg
     * @return
     * @throws Exception
     */
    private int doWriteInternal(ChannelOutboundBuffer in, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            //这种情况应该不会出现吧
            if (!buf.isReadable()) {
                in.remove();
                return 0;
            }

            //将数据写入到 TCP 缓冲区  其实就是写入到 JDK channel
            //应该是写入 另一端的 channel 就会准备好读事件 然后就能 从channel 中拉数据
            final int localFlushedAmount = doWriteBytes(buf);
            //代表确实写入了   下次就写入不进去了 就会到最下面
            if (localFlushedAmount > 0) {
                in.progress(localFlushedAmount);
                if (!buf.isReadable()) {
                    in.remove();
                }
                return 1;
            }

            //这种不看
        } else if (msg instanceof FileRegion) {
            FileRegion region = (FileRegion) msg;
            if (region.transferred() >= region.count()) {
                in.remove();
                return 0;
            }

            long localFlushedAmount = doWriteFileRegion(region);
            if (localFlushedAmount > 0) {
                in.progress(localFlushedAmount);
                if (region.transferred() >= region.count()) {
                    in.remove();
                }
                return 1;
            }
        } else {
            // Should not reach here.
            throw new Error();
        }
        //代表 send Buf 被写满了 也就是 TCP 缓冲区被写满了
        return WRITE_STATUS_SNDBUF_FULL;
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        int writeSpinCount = config().getWriteSpinCount();
        do {
            Object msg = in.current();
            if (msg == null) {
                // Wrote all messages.
                clearOpWrite();
                // Directly return here so incompleteWrite(...) is not called.
                return;
            }
            writeSpinCount -= doWriteInternal(in, msg);
        } while (writeSpinCount > 0);

        incompleteWrite(writeSpinCount < 0);
    }

    /**
     * 对获取到的数据 做 过滤处理
     * @param msg
     * @return
     */
    @Override
    protected final Object filterOutboundMessage(Object msg) {
        //如果数据是 bytebuf 类型
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            //如果是 直接内存 直接返回
            if (buf.isDirect()) {
                return msg;
            }

            //否则封装成直接内存
            return newDirectBuffer(buf);
        }

        //在rocketMq中有看到这个东西 不懂是啥
        if (msg instanceof FileRegion) {
            return msg;
        }

        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }

    /**
     * 判断是否要设置 注册 OP_WRITE
     * @param setOpWrite
     */
    protected final void incompleteWrite(boolean setOpWrite) {
        // Did not write completely.
        if (setOpWrite) {
            setOpWrite();
        } else {
            // It is possible that we have set the write OP, woken up by NIO because the socket is writable, and then
            // use our write quantum. In this case we no longer want to set the write OP because the socket is still
            // writable (as far as we know). We will find out next time we attempt to write if the socket is writable
            // and set the write OP if necessary.
            //清除 写事件监听 看来 这个会对性能有一定影响 不然不会这么急着去除
            clearOpWrite();

            // Schedule flush again later so other tasks can be picked up in the meantime
            eventLoop().execute(flushTask);
        }
    }

    /**
     * Write a {@link FileRegion}
     *
     * @param region        the {@link FileRegion} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract long doWriteFileRegion(FileRegion region) throws Exception;

    /**
     * Read bytes into the given {@link ByteBuf} and return the amount.
     */
    protected abstract int doReadBytes(ByteBuf buf) throws Exception;

    /**
     * Write bytes form the given {@link ByteBuf} to the underlying {@link java.nio.channels.Channel}.
     * @param buf           the {@link ByteBuf} from which the bytes should be written
     * @return amount       the amount of written bytes
     */
    protected abstract int doWriteBytes(ByteBuf buf) throws Exception;

    /**
     * 注册 OP_WRITE 事件
     */
    protected final void setOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        if (!key.isValid()) {
            return;
        }
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) == 0) {
            key.interestOps(interestOps | SelectionKey.OP_WRITE);
        }
    }

    /**
     * 准备清理 channel 上注册的写事件
     */
    protected final void clearOpWrite() {
        final SelectionKey key = selectionKey();
        // Check first if the key is still valid as it may be canceled as part of the deregistration
        // from the EventLoop
        // See https://github.com/netty/netty/issues/2104
        // 如果selectionKey 已经无效了
        if (!key.isValid()) {
            return;
        }
        //如果存在 写事件 就移除
        final int interestOps = key.interestOps();
        if ((interestOps & SelectionKey.OP_WRITE) != 0) {
            key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
        }
    }
}
