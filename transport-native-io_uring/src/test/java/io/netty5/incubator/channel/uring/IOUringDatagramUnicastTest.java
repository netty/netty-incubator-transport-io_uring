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
package io.netty5.incubator.channel.uring;

import io.netty5.bootstrap.Bootstrap;
import io.netty5.buffer.api.Buffer;
import io.netty5.buffer.api.BufferAllocator;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.ChannelOption;
import io.netty5.channel.FixedRecvBufferAllocator;
import io.netty5.channel.SimpleChannelInboundHandler;
import io.netty5.channel.socket.DatagramChannel;
import io.netty5.channel.socket.DatagramPacket;
import io.netty5.channel.socket.InternetProtocolFamily;
import io.netty5.channel.unix.SegmentedDatagramPacket;
import io.netty5.testsuite.transport.TestsuitePermutation;
import io.netty5.testsuite.transport.socket.DatagramUnicastTest;
import io.netty5.util.ReferenceCountUtil;
import io.netty5.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.netty5.buffer.api.DefaultBufferAllocators.offHeapAllocator;
import static java.util.Arrays.stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class IOUringDatagramUnicastTest extends DatagramUnicastTest {

    @BeforeAll
    public static void loadJNI() {
        assumeTrue(IOUring.isAvailable());
    }

    @Override
    protected List<TestsuitePermutation.BootstrapComboFactory<Bootstrap, Bootstrap>> newFactories() {
        return IOUringSocketTestPermutation.INSTANCE.datagram(InternetProtocolFamily.IPv4);
    }

    @Test
    @Timeout(8)
    public void testRecvMsgDontBlock(TestInfo testInfo) throws Throwable {
        run(testInfo, new Runner<Bootstrap, Bootstrap>() {
            @Override
            public void run(Bootstrap bootstrap, Bootstrap bootstrap2) throws Throwable {
                testRecvMsgDontBlock(bootstrap, bootstrap2);
            }
        });
    }

    public void testRecvMsgDontBlock(Bootstrap sb, Bootstrap cb) throws Throwable {
        Channel sc = null;
        Channel cc = null;

        try {
            cb.handler(new SimpleChannelInboundHandler<Object>() {
                public void messageReceived(ChannelHandlerContext ctx, Object msg) {
                    // NOOP.
                }
            });
            cc = cb.bind(newSocketAddress()).sync().get();

            CountDownLatch readLatch = new CountDownLatch(1);
            CountDownLatch readCompleteLatch = new CountDownLatch(1);
            sc = sb.handler(new ChannelHandler() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            readLatch.countDown();
                            ReferenceCountUtil.release(msg);
                        }

                        @Override
                        public void channelReadComplete(ChannelHandlerContext ctx) {
                            readCompleteLatch.countDown();
                        }
                    }).option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvBufferAllocator(2048))
                    .option(ChannelOption.MAX_MESSAGES_PER_READ, 2).bind(newSocketAddress()).sync().get();
            InetSocketAddress addr = sendToAddress((InetSocketAddress) sc.localAddress());
            cc.writeAndFlush(new DatagramPacket(BufferAllocator.offHeapPooled().allocate(512)
                    .fill((byte) 0), addr)).sync();

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
    public void testSendSegmentedDatagramPacket(TestInfo testInfo) throws Throwable {
        run(testInfo, new Runner<Bootstrap, Bootstrap>() {
            @Override
            public void run(Bootstrap bootstrap, Bootstrap bootstrap2) throws Throwable {
                testSendSegmentedDatagramPacket(bootstrap, bootstrap2);
            }
        });
    }

    public void testSendSegmentedDatagramPacket(Bootstrap sb, Bootstrap cb) throws Throwable {
        testSendSegmentedDatagramPacket(sb, cb, false);
    }

    private void testSendSegmentedDatagramPacket(Bootstrap sb, Bootstrap cb, boolean composite)
            throws Throwable {
        if (!IOUring.isAvailable()) {
            // Only supported for the native epoll transport.
            return;
        }
        assumeTrue(IOUringDatagramChannel.isSegmentedDatagramPacketSupported());
        Channel sc = null;
        Channel cc = null;

        try {
            cb.handler(new SimpleChannelInboundHandler<Object>() {
                @Override
                public void messageReceived(ChannelHandlerContext ctx, Object msgs) {
                    // Nothing will be sent.
                }
            });

            cc = cb.bind(newSocketAddress()).sync().get();

            final int numBuffers = 16;
            final int segmentSize = 512;
            int bufferCapacity = numBuffers * segmentSize;
            final CountDownLatch latch = new CountDownLatch(numBuffers);
            AtomicReference<Throwable> errorRef = new AtomicReference<Throwable>();
            sc = sb.handler(new SimpleChannelInboundHandler<DatagramPacket>() {
                @Override
                public void messageReceived(ChannelHandlerContext ctx, DatagramPacket packet) {
                    if (packet.content().readableBytes() == segmentSize) {
                        latch.countDown();
                    }
                }
            }).bind(newSocketAddress()).sync().get();


            InetSocketAddress addr = sendToAddress((InetSocketAddress) sc.localAddress());
            final Buffer buffer;
            if (composite) {
                Buffer[] components = new Buffer[numBuffers];
                for (int i = 0; i < numBuffers; i++) {
                    components[i] = offHeapAllocator().allocate(segmentSize);
                    components[i].fill((byte) 0);
                    components[i].skipWritable(segmentSize);
                }
                buffer = offHeapAllocator().compose(stream(components).map(Buffer::send).collect(Collectors.toList()));
            } else {
                buffer = offHeapAllocator().allocate(bufferCapacity);
                buffer.fill((byte) 0);
                buffer.skipWritable(bufferCapacity);
            }
            cc.writeAndFlush(new SegmentedDatagramPacket(buffer, segmentSize, addr)).sync();

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

    @Override
    protected boolean isConnected(Channel channel) {
        return ((DatagramChannel) channel).isConnected();
    }

    @Override
    protected Channel setupClientChannel(Bootstrap bootstrap, byte[] bytes, CountDownLatch countDownLatch,
                                         AtomicReference<Throwable> errorRef) throws Throwable {
        cb.handler(new SimpleChannelInboundHandler<DatagramPacket>() {

            @Override
            public void messageReceived(ChannelHandlerContext ctx, DatagramPacket msg) {
                try {
                    Buffer buf = msg.content();
                    assertEquals(bytes.length, buf.readableBytes());
                    for (int i = 0; i < bytes.length; i++) {
                        assertEquals(bytes[i], buf.getByte(buf.readerOffset() + i));
                    }

                    assertEquals(ctx.channel().localAddress(), msg.recipient());
                } finally {
                    countDownLatch.countDown();
                }
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                errorRef.compareAndSet(null, cause);
            }
        });
        return cb.bind(newSocketAddress()).sync().get();
    }

    @Override
    protected Channel setupServerChannel(Bootstrap bootstrap, byte[] bytes, SocketAddress sender,
                                         CountDownLatch countDownLatch, AtomicReference<Throwable> errorRef,
                                         boolean echo) throws Throwable {
        sb.handler(new SimpleChannelInboundHandler<DatagramPacket>() {

            @Override
            public void messageReceived(ChannelHandlerContext ctx, DatagramPacket msg) {
                try {
                    if (sender == null) {
                        assertNotNull(msg.sender());
                    } else {
                        assertEquals(sender, msg.sender());
                    }

                    Buffer buf = msg.content();
                    assertEquals(bytes.length, buf.readableBytes());
                    for (int i = 0; i < bytes.length; i++) {
                        assertEquals(bytes[i], buf.getByte(buf.readerOffset() + i));
                    }

                    assertEquals(ctx.channel().localAddress(), msg.recipient());

                    if (echo) {
                        ctx.writeAndFlush(new DatagramPacket(buf.copy(), msg.sender()));
                    }
                } finally {
                    countDownLatch.countDown();
                }
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                errorRef.compareAndSet(null, cause);
            }
        });
        return sb.bind(newSocketAddress()).sync().get();
    }

    @Override
    protected boolean supportDisconnect() {
        return false;
    }

    @Override
    protected Future<Void> write(Channel cc, Buffer buf, SocketAddress remote) {
        return cc.write(new DatagramPacket(buf, (InetSocketAddress) remote));
    }
}
