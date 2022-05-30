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
import io.netty5.channel.EventLoopGroup;
import io.netty5.channel.socket.InternetProtocolFamily;
import io.netty5.channel.socket.ServerSocketChannel;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static io.netty5.channel.unix.NativeInetAddress.address;
import static io.netty5.incubator.channel.uring.LinuxSocket.newSocketStream;

public final class IOUringServerSocketChannel extends AbstractIOUringServerChannel implements ServerSocketChannel {
    private final IOUringServerSocketChannelConfig config;

    @Override
    Channel newChildChannel(int fd, long acceptedAddressMemoryAddress, long acceptedAddressLengthMemoryAddress) throws Exception {
        final InetSocketAddress address;
        if (socket.isIpv6()) {
            byte[] ipv6Array = registration.ioUringHandler().inet6AddressArray();
            byte[] ipv4Array = registration.ioUringHandler().inet4AddressArray();
            address = SockaddrIn.readIPv6(acceptedAddressMemoryAddress, ipv6Array, ipv4Array);
        } else {
            byte[] addressArray = registration.ioUringHandler().inet4AddressArray();
            address = SockaddrIn.readIPv4(acceptedAddressMemoryAddress, addressArray);
        }

        return new IOUringSocketChannel(this, childEventLoopGroup().next(), new LinuxSocket(fd), address);
    }

    public IOUringServerSocketChannel(EventLoop eventLoop, EventLoopGroup childEventLoopGroup) {
        this(eventLoop, childEventLoopGroup, (InternetProtocolFamily) null);
    }

    public IOUringServerSocketChannel(EventLoop eventLoop, EventLoopGroup childEventLoopGroup,
                                    InternetProtocolFamily protocolFamily) {
        super(eventLoop, childEventLoopGroup, newSocketStream(protocolFamily == InternetProtocolFamily.IPv6), false);
        config = new IOUringServerSocketChannelConfig(this);
    }

    public IOUringServerSocketChannel(EventLoop eventLoop, EventLoopGroup childEventLoopGroup, int fd) {
        // Must call this constructor to ensure this object's local address is configured correctly.
        // The local address can only be obtained from a Socket object.
        this(eventLoop, childEventLoopGroup, new LinuxSocket(fd));
    }

    IOUringServerSocketChannel(EventLoop eventLoop, EventLoopGroup childEventLoopGroup, LinuxSocket fd) {
        super(eventLoop, childEventLoopGroup, fd);
        config = new IOUringServerSocketChannelConfig(this);
    }

    IOUringServerSocketChannel(EventLoop eventLoop, EventLoopGroup childEventLoopGroup, LinuxSocket fd, boolean active) {
        super(eventLoop, childEventLoopGroup, fd, active);
        config = new IOUringServerSocketChannelConfig(this);
    }

    @Override
    public IOUringServerSocketChannelConfig config() {
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

    @Override
    public void doBind(SocketAddress localAddress) throws Exception {
        super.doBind(localAddress);
        socket.listen(config.getBacklog());
        active = true;
    }

    @Override
    public EventLoopGroup childEventLoopGroup() {
        return super.childEventLoopGroup();
    }
}
