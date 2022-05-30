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
package io.netty5.incubator.channel.uring.example;

import io.netty5.bootstrap.ServerBootstrap;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelInitializer;
import io.netty5.channel.ChannelPipeline;
import io.netty5.channel.EventLoopGroup;
import io.netty5.channel.MultithreadEventLoopGroup;
import io.netty5.channel.socket.SocketChannel;
import io.netty5.incubator.channel.uring.IOUring;
import io.netty5.incubator.channel.uring.IOUringHandler;
import io.netty5.incubator.channel.uring.IOUringServerSocketChannel;
import io.netty5.util.concurrent.Future;

import java.util.concurrent.ExecutionException;

//temporary prototype example
public class EchoIOUringServer {
    static {
        System.setProperty("log4j.configurationFile", "log4j2.xml");
    }

    private static final int PORT = Integer.parseInt(System.getProperty("port", "8080"));

    public static void main(String[] args) {
        System.out.println(IOUring.isAvailable());

        EventLoopGroup bossGroup = new MultithreadEventLoopGroup(1, IOUringHandler.newFactory());
        EventLoopGroup workerGroup = new MultithreadEventLoopGroup(1, IOUringHandler.newFactory());
        final EchoIOUringServerHandler serverHandler = new EchoIOUringServerHandler();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channelFactory(IOUringServerSocketChannel::new)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast(serverHandler);
                        }
                    });

            // Start the server.
            Future<Channel> f = b.bind(PORT).sync();

            // Wait until the server socket is closed.
            f.get().closeFuture().sync();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        } finally {
            // Shut down all event loops to terminate all threads.
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
