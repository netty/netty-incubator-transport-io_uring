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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

import static org.junit.Assert.assertEquals;

public class IOUringRemoteIpTest {

    @Test
    public void testRemoteAddress() throws Exception {
        final Promise<SocketAddress> promise = ImmediateEventExecutor.INSTANCE.newPromise();
        EventLoopGroup bossGroup = new IOUringEventLoopGroup(1);
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup)
                    .channel(IOUringServerSocketChannel.class)
                    .childHandler(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelActive(ChannelHandlerContext ctx) {
                            promise.setSuccess(ctx.channel().remoteAddress());
                            ctx.close();
                        }
                    });

            // Start the server.
            ChannelFuture f = b.bind(NetUtil.LOCALHOST, 0).sync();
            Socket socket = new Socket();
            socket.bind(new InetSocketAddress(NetUtil.LOCALHOST, 0));
            socket.connect(f.channel().localAddress());

            InetSocketAddress addr = (InetSocketAddress) promise.get();
            assertEquals(socket.getLocalSocketAddress(), addr);
            socket.close();
            f.channel().close().sync();
        } finally {
            // Shut down all event loops to terminate all threads.
            bossGroup.shutdownGracefully();
        }
    }
}
