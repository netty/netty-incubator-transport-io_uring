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
import io.netty5.buffer.Buffer;
import io.netty5.buffer.BufferAllocator;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.ChannelOption;
import io.netty5.channel.FixedReadHandleFactory;
import io.netty5.channel.SimpleChannelInboundHandler;
import io.netty5.channel.socket.DatagramChannel;
import io.netty5.channel.socket.DatagramPacket;
import io.netty5.channel.socket.SocketProtocolFamily;
import io.netty5.channel.unix.Errors;
import io.netty5.channel.unix.SegmentedDatagramPacket;
import io.netty5.testsuite.transport.TestsuitePermutation;
import io.netty5.testsuite.transport.socket.DatagramUnicastTest;
import io.netty5.util.ReferenceCountUtil;
import io.netty5.util.Send;
import io.netty5.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.netty5.buffer.DefaultBufferAllocators.offHeapAllocator;
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
        return IOUringSocketTestPermutation.INSTANCE.datagram(SocketProtocolFamily.INET);
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
                @Override
                protected void messageReceived(ChannelHandlerContext ctx, Object msg) throws Exception {
                    // NOOP.
                }
            });
            cc = cb.bind(newSocketAddress()).asStage().get();

            CountDownLatch readLatch = new CountDownLatch(1);
            CountDownLatch readCompleteLatch = new CountDownLatch(1);
            sc = sb.handler(new ChannelHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    readLatch.countDown();
                    ReferenceCountUtil.release(msg);
                }

                @Override
                public void channelReadComplete(ChannelHandlerContext ctx) {
                    readCompleteLatch.countDown();
                }
            }).option(ChannelOption.READ_HANDLE_FACTORY, new FixedReadHandleFactory(2048))
                    .bind(newSocketAddress()).asStage().get();
            InetSocketAddress addr = sendToAddress((InetSocketAddress) sc.localAddress());
            cc.writeAndFlush(new DatagramPacket(offHeapAllocator().copyOf(new byte[512]), addr)).asStage().sync();

            readLatch.await();
            readCompleteLatch.await();
        } finally {
            if (cc != null) {
                cc.close().asStage().sync();
            }
            if (sc != null) {
                sc.close().asStage().sync();
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
        if (!cb.group().isCompatible(AbstractIOUringChannel.class)) {
            // Only supported for the native epoll transport.
            return;
        }
        assumeTrue(IOUringDatagramChannel.isSegmentedDatagramPacketSupported());
        Channel sc = null;
        Channel cc = null;

        try {
            cb.handler(new SimpleChannelInboundHandler<Object>() {
                @Override
                protected void messageReceived(ChannelHandlerContext ctx, Object msg) throws Exception {
                    // Nothing will be sent.
                }
            });

            cc = cb.bind(newSocketAddress()).asStage().get();

            final int numBuffers = 16;
            final int segmentSize = 512;
            int bufferCapacity = numBuffers * segmentSize;
            final CountDownLatch latch = new CountDownLatch(numBuffers);
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            sc = sb.handler(new SimpleChannelInboundHandler<DatagramPacket>() {
                @Override
                public void messageReceived(ChannelHandlerContext ctx, DatagramPacket packet) {
                    if (packet.content().readableBytes() == segmentSize) {
                        latch.countDown();
                    }
                }
            }).bind(newSocketAddress()).asStage().get();

            InetSocketAddress addr = sendToAddress((InetSocketAddress) sc.localAddress());
            BufferAllocator allocator = offHeapAllocator();
            final Buffer buffer;
            if (composite) {
                List<Send<Buffer>> list = new ArrayList<>();
                for (int i = 0; i < numBuffers; i++) {
                    list.add(allocator.allocate(segmentSize).fill((byte) 0).skipWritableBytes(segmentSize).send());
                }
                buffer = allocator.compose(list);
            } else {
                buffer = allocator.allocate(segmentSize).fill((byte) 0).skipWritableBytes(segmentSize);
            }
            var future = cc.writeAndFlush(new SegmentedDatagramPacket(buffer, segmentSize, addr)).asStage().await();
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
                cc.close().asStage().sync();
            }
            if (sc != null) {
                sc.close().asStage().sync();
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
            public void channelExceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                errorRef.compareAndSet(null, cause);
            }
        });
        return cb.bind(newSocketAddress()).asStage().get();
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
                        buf.makeReadOnly();
                        ctx.writeAndFlush(new DatagramPacket(buf.copy(true), msg.sender()));
                    }
                } finally {
                    countDownLatch.countDown();
                }
            }

            @Override
            public void channelExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                errorRef.compareAndSet(null, cause);
            }
        });
        return sb.bind(newSocketAddress()).asStage().get();
    }

    @Override
    protected boolean supportDisconnect() {
        return false;
    }

    @Override
    protected Future<Void> write(Channel cc, Buffer buf, SocketAddress remote) {
        return cc.write(new DatagramPacket(buf, remote));
    }
}
