/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.incubator.channel.uring;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.channel.unix.Errors;
import io.netty.channel.unix.SegmentedDatagramPacket;
import io.netty.testsuite.transport.TestsuitePermutation;
import io.netty.testsuite.transport.socket.DatagramUnicastTest;
import io.netty.util.ReferenceCountUtil;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

public class IOUringDatagramUnicastTest extends DatagramUnicastTest {

    @BeforeClass
    public static void loadJNI() {
        assumeTrue(IOUring.isAvailable());
    }

    @Override
    protected List<TestsuitePermutation.BootstrapComboFactory<Bootstrap, Bootstrap>> newFactories() {
        return IOUringSocketTestPermutation.INSTANCE.datagram(InternetProtocolFamily.IPv4);
    }

    @Test(timeout = 8000)
    public void testRecvMsgDontBlock() throws Throwable {
        run();
    }

    public void testRecvMsgDontBlock(Bootstrap sb, Bootstrap cb) throws Throwable {
        Channel sc = null;
        Channel cc = null;

        try {
            cb.handler(new SimpleChannelInboundHandler<Object>() {
                public void channelRead0(ChannelHandlerContext ctx, Object msgs) {
                    // NOOP.
                }
            });
            cc = cb.bind(newSocketAddress()).sync().channel();

            CountDownLatch readLatch = new CountDownLatch(1);
            CountDownLatch readCompleteLatch = new CountDownLatch(1);
            sc = sb.handler(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    readLatch.countDown();
                    ReferenceCountUtil.release(msg);
                }

                @Override
                public void channelReadComplete(ChannelHandlerContext ctx) {
                    readCompleteLatch.countDown();
                }
            }).option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(2048))
                    .option(ChannelOption.MAX_MESSAGES_PER_READ, 2).bind(newSocketAddress()).sync().channel();
            InetSocketAddress addr = sendToAddress((InetSocketAddress) sc.localAddress());
            cc.writeAndFlush(new DatagramPacket(Unpooled.directBuffer().writeZero(512), addr)).sync();

            readLatch.await();
            readCompleteLatch.await();
        } finally {
            if (cc != null) {
                cc.close().sync();
            }
            if (sc != null) {
                sc.close().sync();
            }
        }
    }

    @Test
    public void testSendSegmentedDatagramPacket() throws Throwable {
        run();
    }

    public void testSendSegmentedDatagramPacket(Bootstrap sb, Bootstrap cb) throws Throwable {
        testSendSegmentedDatagramPacket(sb, cb, false);
    }

    private void testSendSegmentedDatagramPacket(Bootstrap sb, Bootstrap cb, boolean composite)
            throws Throwable {
        if (!(cb.group() instanceof IOUringEventLoopGroup)) {
            // Only supported for the native epoll transport.
            return;
        }
        Assume.assumeTrue(IOUringDatagramChannel.isSegmentedDatagramPacketSupported());
        Channel sc = null;
        Channel cc = null;

        try {
            cb.handler(new SimpleChannelInboundHandler<Object>() {
                @Override
                public void channelRead0(ChannelHandlerContext ctx, Object msgs)  {
                    // Nothing will be sent.
                }
            });

            cc = cb.bind(newSocketAddress()).sync().channel();

            final int numBuffers = 16;
            final int segmentSize = 512;
            int bufferCapacity = numBuffers * segmentSize;
            final CountDownLatch latch = new CountDownLatch(numBuffers);
            AtomicReference<Throwable> errorRef = new AtomicReference<Throwable>();
            sc = sb.handler(new SimpleChannelInboundHandler<DatagramPacket>() {
                @Override
                public void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                    if (packet.content().readableBytes() == segmentSize) {
                        latch.countDown();
                    }
                }
            }).bind(newSocketAddress()).sync().channel();

            InetSocketAddress addr = sendToAddress((InetSocketAddress) sc.localAddress());
            final ByteBuf buffer;
            if (composite) {
                CompositeByteBuf compositeBuffer = Unpooled.compositeBuffer();
                for (int i = 0; i < numBuffers; i++) {
                    compositeBuffer.addComponent(true,
                            Unpooled.directBuffer(segmentSize).writeZero(segmentSize));
                }
                buffer = compositeBuffer;
            } else {
                buffer = Unpooled.directBuffer(bufferCapacity).writeZero(bufferCapacity);
            }
            ChannelFuture future = cc.writeAndFlush(new SegmentedDatagramPacket(buffer, segmentSize, addr))
                    .await();
            if (future.cause() instanceof Errors.NativeIoException) {
                Errors.NativeIoException e = (Errors.NativeIoException) future.cause();
                if (e.getMessage().contains("Invalid argument")) {
                    // IO uring version not supports GSO :/
                    return;
                }
                future.sync();
            }

            if (!latch.await(10, TimeUnit.SECONDS)) {
                Throwable error = errorRef.get();
                if (error != null) {
                    throw error;
                }
                fail();
            }
        } finally {
            if (cc != null) {
                cc.close().sync();
            }
            if (sc != null) {
                sc.close().sync();
            }
        }
    }
}
