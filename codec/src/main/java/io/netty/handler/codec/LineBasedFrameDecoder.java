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
package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ByteProcessor;

import java.util.List;

/**
 * A decoder that splits the received {@link ByteBuf}s on line endings.
 * <p>
 * Both {@code "\n"} and {@code "\r\n"} are handled.
 * For a more general delimiter-based decoder, see {@link DelimiterBasedFrameDecoder}.
 *
 * 基于换行符进行 解码
 */
public class LineBasedFrameDecoder extends ByteToMessageDecoder {

    /** Maximum length of a frame we're willing to decode.
     *  允许一次解析的最大长度
     * */
    private final int maxLength;
    /** Whether or not to throw an exception as soon as we exceed maxLength.
     *  异常时 是否直接 提示
     * */
    private final boolean failFast;
    /**
     * 是否 要 过滤掉换行符
     */
    private final boolean stripDelimiter;

    /** True if we're discarding input because we're already over maxLength.
     *  是否处在丢弃状态
     * */
    private boolean discarding;
    /**
     * 需要丢弃的长度
     */
    private int discardedBytes;

    /** Last scan position.
     *  上次扫描的终点
     * */
    private int offset;

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     */
    public LineBasedFrameDecoder(final int maxLength) {
        this(maxLength, true, false);
    }

    /**
     * Creates a new decoder.
     * @param maxLength  the maximum length of the decoded frame.
     *                   A {@link TooLongFrameException} is thrown if
     *                   the length of the frame exceeds this value.
     * @param stripDelimiter  whether the decoded frame should strip out the
     *                        delimiter or not
     * @param failFast  If <tt>true</tt>, a {@link TooLongFrameException} is
     *                  thrown as soon as the decoder notices the length of the
     *                  frame will exceed <tt>maxFrameLength</tt> regardless of
     *                  whether the entire frame has been read.
     *                  If <tt>false</tt>, a {@link TooLongFrameException} is
     *                  thrown after the entire frame that exceeds
     *                  <tt>maxFrameLength</tt> has been read.
     */
    public LineBasedFrameDecoder(final int maxLength, final boolean stripDelimiter, final boolean failFast) {
        this.maxLength = maxLength;
        this.failFast = failFast;
        this.stripDelimiter = stripDelimiter;
    }

    @Override
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        Object decoded = decode(ctx, in);
        if (decoded != null) {
            out.add(decoded);
        }
    }

    /**
     * Create a frame out of the {@link ByteBuf} and return it.
     *
     * @param   ctx             the {@link ChannelHandlerContext} which this {@link ByteToMessageDecoder} belongs to
     * @param   buffer          the {@link ByteBuf} from which to read data
     * @return  frame           the {@link ByteBuf} which represent the frame or {@code null} if no frame could
     *                          be created.
     *                          解码 逻辑  一旦超过 maxLength 已经不返回结果了 fastfail 只是确定在 什么时机提示异常
     */
    protected Object decode(ChannelHandlerContext ctx, ByteBuf buffer) throws Exception {
        //找到 分隔符的 终点
        final int eol = findEndOfLine(buffer);
        //不需要丢弃的正常情况
        if (!discarding) {
            //检测到了 换行符
            if (eol >= 0) {
                final ByteBuf frame;
                //代表本次消息体长度
                final int length = eol - buffer.readerIndex();
                //分隔符大小
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;

                /**
                 * 代表消息过大需要丢弃
                 */
                if (length > maxLength) {
                    //设置到 分隔符的 末尾
                    buffer.readerIndex(eol + delimLength);
                    fail(ctx, length);
                    return null;
                }

                //需要丢弃分隔符 就跳过分隔符的长度
                if (stripDelimiter) {
                    frame = buffer.readRetainedSlice(length);
                    buffer.skipBytes(delimLength);
                } else {
                    //返回副本对象
                    frame = buffer.readRetainedSlice(length + delimLength);
                }

                return frame;
            } else {
                //没有找到换行符
                final int length = buffer.readableBytes();
                //本次 长度已经超过 最大值
                if (length > maxLength) {
                    //将本次长度全部丢弃
                    discardedBytes = length;
                    //移动到末尾
                    buffer.readerIndex(buffer.writerIndex());
                    discarding = true;
                    //重置偏移量
                    offset = 0;
                    if (failFast) {
                        fail(ctx, "over " + discardedBytes);
                    }
                }
                return null;
            }
            //代表上次有丢弃过数据
        } else {
            if (eol >= 0) {
                //加上上次获取的数据 获得 总长度
                final int length = discardedBytes + eol - buffer.readerIndex();
                final int delimLength = buffer.getByte(eol) == '\r'? 2 : 1;
                //将指针移动到末尾
                buffer.readerIndex(eol + delimLength);
                discardedBytes = 0;
                //解除丢弃状态
                discarding = false;
                if (!failFast) {
                    //现在 才 触发异常
                    fail(ctx, length);
                }
            } else {
                //本次也没有找到分隔符  这时 已经是 超过maxLength 了
                //增加 丢弃量
                discardedBytes += buffer.readableBytes();
                buffer.readerIndex(buffer.writerIndex());
                // We skip everything in the buffer, we need to set the offset to 0 again.
                offset = 0;
            }
            return null;
        }
    }

    private void fail(final ChannelHandlerContext ctx, int length) {
        fail(ctx, String.valueOf(length));
    }

    //将异常事件传递
    private void fail(final ChannelHandlerContext ctx, String length) {
        ctx.fireExceptionCaught(
                new TooLongFrameException(
                        "frame length (" + length + ") exceeds the allowed maximum (" + maxLength + ')'));
    }

    /**
     * Returns the index in the buffer of the end of line found.
     * Returns -1 if no end of line was found in the buffer.
     *
     * 寻找换行符的 下标
     */
    private int findEndOfLine(final ByteBuf buffer) {
        int totalLength = buffer.readableBytes();
        //本次 起点要加上 上次扫描的位置 总长度 要减去上次扫描的位置 查找 分隔符
        int i = buffer.forEachByte(buffer.readerIndex() + offset, totalLength - offset, ByteProcessor.FIND_LF);
        if (i >= 0) {
            //代表找到了那么 这次就会读取一个完整的数据 下次 按照 读指针所在位置开始 找就好 因为获取数据的同时
            //读指针也在一个 完整数据的终点
            offset = 0;
            //如果有 \r 标识 在往前一格
            if (i > 0 && buffer.getByte(i - 1) == '\r') {
                i--;
            }
        } else {
            //因为 本次 不会 解析出任何 数据 所以 下次 的起点 就要从本次 扫描的末尾开始
            offset = totalLength;
        }
        return i;
    }
}
