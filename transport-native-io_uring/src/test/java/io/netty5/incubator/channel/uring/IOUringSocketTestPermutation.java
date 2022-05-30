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

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty5.bootstrap.Bootstrap;
import io.netty5.bootstrap.ServerBootstrap;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelFactory;
import io.netty5.channel.EventLoop;
import io.netty5.channel.EventLoopGroup;
import io.netty5.channel.MultithreadEventLoopGroup;
import io.netty5.channel.epoll.EpollHandler;
import io.netty5.channel.socket.InternetProtocolFamily;
import io.netty5.channel.socket.nio.NioDatagramChannel;
import io.netty5.channel.socket.nio.NioServerSocketChannel;
import io.netty5.channel.socket.nio.NioSocketChannel;
import io.netty5.testsuite.transport.TestsuitePermutation;
import io.netty5.testsuite.transport.TestsuitePermutation.BootstrapComboFactory;
import io.netty5.testsuite.transport.TestsuitePermutation.BootstrapFactory;
import io.netty5.testsuite.transport.socket.SocketTestPermutation;
import io.netty5.util.concurrent.DefaultThreadFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class IOUringSocketTestPermutation extends SocketTestPermutation {

    static final IOUringSocketTestPermutation INSTANCE = new IOUringSocketTestPermutation();

    static final EventLoopGroup IO_URING_BOSS_GROUP =
            new MultithreadEventLoopGroup(BOSSES, new DefaultThreadFactory("testsuite-epoll-boss", true),
                    EpollHandler.newFactory());
    static final EventLoopGroup IO_URING_WORKER_GROUP =
            new MultithreadEventLoopGroup(WORKERS, new DefaultThreadFactory("testsuite-epoll-worker", true),
                    EpollHandler.newFactory());

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(IOUringSocketTestPermutation.class);

    @Override
    public List<BootstrapComboFactory<ServerBootstrap, Bootstrap>> socket() {

        List<TestsuitePermutation.BootstrapComboFactory<ServerBootstrap, Bootstrap>> list =
                combo(serverSocket(), clientSocket());

        list.remove(list.size() - 1); // Exclude NIO x NIO test

        return list;
    }

    @Override
    public List<BootstrapFactory<ServerBootstrap>> serverSocket() {
        List<BootstrapFactory<ServerBootstrap>> toReturn = new ArrayList<>();
        toReturn.add(new BootstrapFactory<ServerBootstrap>() {
            @Override
            public ServerBootstrap newInstance() {
                return new ServerBootstrap().group(IO_URING_BOSS_GROUP, IO_URING_WORKER_GROUP)
                        .channel(IOUringServerSocketChannel.class);
            }
        });
        if (isServerFastOpen()) {
            toReturn.add(new BootstrapFactory<ServerBootstrap>() {
                @Override
                public ServerBootstrap newInstance() {
                    ServerBootstrap serverBootstrap = new ServerBootstrap().group(IO_URING_BOSS_GROUP,
                                    IO_URING_WORKER_GROUP)
                            .channel(IOUringServerSocketChannel.class);
                    serverBootstrap.option(IOUringChannelOption.TCP_FASTOPEN, 5);
                    return serverBootstrap;
                }
            });
        }
        toReturn.add(new BootstrapFactory<ServerBootstrap>() {
            @Override
            public ServerBootstrap newInstance() {
                return new ServerBootstrap().group(nioBossGroup, nioWorkerGroup)
                        .channel(NioServerSocketChannel.class);
            }
        });

        return toReturn;
    }

    @Override
    public List<BootstrapFactory<Bootstrap>> clientSocket() {
        return Arrays.asList(
                new BootstrapFactory<Bootstrap>() {
                    @Override
                    public Bootstrap newInstance() {
                        return new Bootstrap().group(IO_URING_WORKER_GROUP).channel(IOUringSocketChannel.class);
                    }
                },
                new BootstrapFactory<Bootstrap>() {
                    @Override
                    public Bootstrap newInstance() {
                        return new Bootstrap().group(nioWorkerGroup).channel(NioSocketChannel.class);
                    }
                }
        );
    }

    @Override
    public List<TestsuitePermutation.BootstrapComboFactory<Bootstrap, Bootstrap>> datagram(
            final InternetProtocolFamily family) {
        // Make the list of Bootstrap factories.
        List<BootstrapFactory<Bootstrap>> bfs = Arrays.asList(
                () -> new Bootstrap().group(IO_URING_WORKER_GROUP)
                        .channelFactory(new ChannelFactory<Channel>() {
                            @Override
                            public Channel newChannel(EventLoop eventLoop) throws Exception {
                                return new IOUringDatagramChannel(eventLoop);
                            }

                            @Override
                            public String toString() {
                                return InternetProtocolFamily.class.getSimpleName() + ".class";
                            }
                        }),
                () -> new Bootstrap().group(nioWorkerGroup)
                        .channelFactory(new ChannelFactory<Channel>() {
                            @Override
                            public Channel newChannel(EventLoop eventLoop) {
                                return new NioDatagramChannel(eventLoop);
                            }

                            @Override
                            public String toString() {
                                return NioDatagramChannel.class.getSimpleName() + ".class";
                            }
                        })
        );

        return combo(bfs, bfs);
    }

    public boolean isServerFastOpen() {
        return AccessController.doPrivileged(new PrivilegedAction<Integer>() {
            @Override
            public Integer run() {
                int fastopen = 0;
                File file = new File("/proc/sys/net/ipv4/tcp_fastopen");
                if (file.exists()) {
                    BufferedReader in = null;
                    try {
                        in = new BufferedReader(new FileReader(file));
                        fastopen = Integer.parseInt(in.readLine());
                        if (logger.isDebugEnabled()) {
                            logger.debug("{}: {}", file, fastopen);
                        }
                    } catch (Exception e) {
                        logger.debug("Failed to get TCP_FASTOPEN from: {}", file, e);
                    } finally {
                        if (in != null) {
                            try {
                                in.close();
                            } catch (Exception e) {
                                // Ignored.
                            }
                        }
                    }
                } else {
                    if (logger.isDebugEnabled()) {
                        logger.debug("{}: {} (non-existent)", file, fastopen);
                    }
                }
                return fastopen;
            }
        }) == 3;
    }
}
