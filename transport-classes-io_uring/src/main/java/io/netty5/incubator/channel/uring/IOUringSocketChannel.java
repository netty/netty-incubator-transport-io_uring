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

import io.netty5.channel.Channel;
import io.netty5.channel.EventLoop;
import io.netty5.channel.socket.InternetProtocolFamily;
import io.netty5.channel.socket.ServerSocketChannel;
import io.netty5.channel.socket.SocketChannel;

import java.net.InetSocketAddress;

import static io.netty5.incubator.channel.uring.LinuxSocket.newSocketStream;

public final class IOUringSocketChannel extends AbstractIOUringStreamChannel implements SocketChannel {
    private final IOUringSocketChannelConfig config;

    public IOUringSocketChannel(EventLoop eventLoop) {
        this(eventLoop, null);
    }

    public IOUringSocketChannel(EventLoop eventLoop, InternetProtocolFamily protocolFamily) {
        super(eventLoop, newSocketStream(protocolFamily == InternetProtocolFamily.IPv6), false);
        config = new IOUringSocketChannelConfig(this);
    }

    public IOUringSocketChannel(EventLoop eventLoop, int fd) {
        super(eventLoop, fd);
        config = new IOUringSocketChannelConfig(this);
    }

    IOUringSocketChannel(EventLoop eventLoop, LinuxSocket fd, boolean active) {
        super(eventLoop, fd, active);
        config = new IOUringSocketChannelConfig(this);
    }

    IOUringSocketChannel(Channel parent, EventLoop eventLoop, LinuxSocket fd, InetSocketAddress remoteAddress) {
        super(parent, eventLoop, fd, remoteAddress);
        config = new IOUringSocketChannelConfig(this);
    }

    @Override
    public ServerSocketChannel parent() {
        return (ServerSocketChannel) super.parent();
    }

    @Override
    public IOUringSocketChannelConfig config() {
        return config;
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) super.remoteAddress();
    }

    @Override
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) super.localAddress();
    }
}
