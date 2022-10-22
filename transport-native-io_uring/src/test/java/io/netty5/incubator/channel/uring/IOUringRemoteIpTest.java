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

import io.netty5.bootstrap.ServerBootstrap;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.EventLoopGroup;
import io.netty5.channel.MultithreadEventLoopGroup;
import io.netty5.util.NetUtil;
import io.netty5.util.concurrent.Future;
import io.netty5.util.concurrent.ImmediateEventExecutor;
import io.netty5.util.concurrent.Promise;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class IOUringRemoteIpTest {

    @BeforeAll
    public static void loadJNI() {
        Assumptions.assumeTrue(IOUring.isAvailable());
    }

    @Test
    public void testRemoteAddressIpv4() throws Exception {
        testRemoteAddress(NetUtil.LOCALHOST4, NetUtil.LOCALHOST4);
    }

    @Test
    public void testRemoteAddressIpv6() throws Exception {
        testRemoteAddress(NetUtil.LOCALHOST6, NetUtil.LOCALHOST6);
    }

    @Test
    public void testRemoteAddressIpv4AndServerAutoDetect() throws Exception {
        testRemoteAddress(null, NetUtil.LOCALHOST4);
    }

    @Test
    public void testRemoteAddressIpv6ServerAutoDetect() throws Exception {
        testRemoteAddress(null, NetUtil.LOCALHOST6);
    }


    private static void testRemoteAddress(InetAddress server, InetAddress client) throws Exception {
        final Promise<SocketAddress> promise = ImmediateEventExecutor.INSTANCE.newPromise();
        EventLoopGroup bossGroup = new MultithreadEventLoopGroup(1, IOUring.newFactory());
        Socket socket = new Socket();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup)
                    .channel(IOUringServerSocketChannel.class)
                    .childHandler(new ChannelHandlerAdapter() {
                        @Override
                        public void channelActive(ChannelHandlerContext ctx) {
                            promise.setSuccess(ctx.channel().remoteAddress());
                            ctx.close();
                        }
                    });

            // Start the server.
            Future<Channel> f;
            InetSocketAddress connectAddress;
            if (server == null) {
                f = b.bind(0).asStage().sync().future();
                connectAddress = new InetSocketAddress(client,
                        ((InetSocketAddress) f.getNow().localAddress()).getPort());
            } else {
                try {
                    f = b.bind(server, 0).asStage().sync().future();
                } catch (Throwable cause) {
                    throw new TestAbortedException("Bind failed, address family not supported ?", cause);
                }
                connectAddress = (InetSocketAddress) f.getNow().localAddress();
            }

            try {
                socket.bind(new InetSocketAddress(client, 0));
            } catch (SocketException e) {
                throw new TestAbortedException("Bind failed, address family not supported ?", e);
            }
            socket.connect(connectAddress);

            InetSocketAddress addr = (InetSocketAddress) promise.asFuture().asStage().get();
            assertEquals(socket.getLocalSocketAddress(), addr);
            f.getNow().close().asStage().sync();
        } finally {
            // Shut down all event loops to terminate all threads.
            bossGroup.shutdownGracefully();

            socket.close();
        }
    }
}
