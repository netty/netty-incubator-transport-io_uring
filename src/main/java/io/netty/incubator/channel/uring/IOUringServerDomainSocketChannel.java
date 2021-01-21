package io.netty.incubator.channel.uring;

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.channel.unix.ServerDomainSocketChannel;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.File;
import java.net.SocketAddress;

public class IOUringServerDomainSocketChannel extends AbstractIOUringServerChannel implements ServerDomainSocketChannel {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(
            IOUringServerDomainSocketChannel.class);
    private final IOUringServerSocketChannelConfig config;

    protected IOUringServerDomainSocketChannel() {
        super(LinuxSocket.newSocketDomain(), false);
        this.config = new IOUringServerSocketChannelConfig(this);
    }

    @Override
    Channel newChildChannel(int fd, long acceptedAddressMemoryAddress, long acceptedAddressLengthMemoryAddress) throws Exception {
        return new IOUringDomainSocketChannel(this, new LinuxSocket(fd), null);
    }

    @Override
    public ChannelConfig config() {
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

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        super.doBind(localAddress);
        socket.listen(config.getBacklog());
        active = true;
    }

    @Override
    protected void doClose() throws Exception {
        try {
            super.doClose();
        } finally {
            DomainSocketAddress local = this.localAddress();
            if (local != null) {
                // Delete the socket file if possible.
                File socketFile = new File(local.path());
                boolean success = socketFile.delete();
                if (!success && logger.isDebugEnabled()) {
                    logger.debug("Failed to delete a domain socket file: {}", local.path());
                }
            }
        }
    }
}
