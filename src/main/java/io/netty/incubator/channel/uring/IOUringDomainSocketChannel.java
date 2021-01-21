package io.netty.incubator.channel.uring;

import io.netty.channel.Channel;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.channel.unix.DomainSocketChannel;
import io.netty.channel.unix.DomainSocketChannelConfig;

import java.net.SocketAddress;

public class IOUringDomainSocketChannel extends AbstractIOUringStreamChannel implements DomainSocketChannel {
    private final IOUringDomainSocketChannelConfig config;

    public IOUringDomainSocketChannel() {
        super(null, LinuxSocket.newSocketDomain(), false);
        this.config = new IOUringDomainSocketChannelConfig(this);
    }

    IOUringDomainSocketChannel(Channel parent, LinuxSocket fd, SocketAddress remote) {
        super(parent, fd, remote);
        this.config = new IOUringDomainSocketChannelConfig(this);
    }

    @Override
    public DomainSocketChannelConfig config() {
        return config;
    }

    @Override
    public DomainSocketAddress remoteAddress() {
        return (DomainSocketAddress) super.remoteAddress();
    }

    @Override
    public DomainSocketAddress localAddress() {
        return (DomainSocketAddress) super.localAddress();
    }
}
