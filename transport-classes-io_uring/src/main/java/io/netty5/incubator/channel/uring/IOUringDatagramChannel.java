/*
 * Copyright 2022 The Netty Project
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

import io.netty5.buffer.Buffer;
import io.netty5.channel.AddressedEnvelope;
import io.netty5.channel.ChannelOption;
import io.netty5.channel.ChannelShutdownDirection;
import io.netty5.channel.DefaultAddressedEnvelope;
import io.netty5.channel.EventLoop;
import io.netty5.channel.FixedReadHandleFactory;
import io.netty5.channel.MaxMessagesWriteHandleFactory;
import io.netty5.channel.ReadHandleFactory;
import io.netty5.channel.WriteHandleFactory;
import io.netty5.channel.socket.DatagramChannel;
import io.netty5.channel.socket.DatagramPacket;
import io.netty5.channel.socket.SocketProtocolFamily;
import io.netty5.channel.unix.Errors;
import io.netty5.channel.unix.UnixChannel;
import io.netty5.channel.unix.UnixChannelUtil;
import io.netty5.util.concurrent.Future;
import io.netty5.util.concurrent.FutureListener;
import io.netty5.util.concurrent.Promise;
import io.netty5.util.concurrent.PromiseCombiner;
import io.netty5.util.internal.SilentDispose;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.ArrayDeque;
import java.util.Deque;

import static java.util.Objects.requireNonNull;

public final class IOUringDatagramChannel extends AbstractIOUringChannel<UnixChannel> implements DatagramChannel {
    private volatile boolean activeOnOpen;
    private volatile int maxDatagramSize;

    private volatile boolean connected;
    private volatile boolean inputShutdown;
    private volatile boolean outputShutdown;

    private final Deque<CachedMsgHdrMemory> msgHdrCache;
    private final PendingData<Promise<Void>> pendingWrites;

    // The maximum number of bytes for an InetAddress / Inet6Address
    private final byte[] inet4AddressArray = new byte[SockaddrIn.IPV4_ADDRESS_LENGTH];
    private final byte[] inet6AddressArray = new byte[SockaddrIn.IPV6_ADDRESS_LENGTH];

    public IOUringDatagramChannel(EventLoop eventLoop) {
        this(null, eventLoop, true, new FixedReadHandleFactory(2048),
                new MaxMessagesWriteHandleFactory(Integer.MAX_VALUE),
                LinuxSocket.newSocketDgram(), false);
    }

    IOUringDatagramChannel(
            UnixChannel parent, EventLoop eventLoop, boolean supportingDisconnect,
            ReadHandleFactory defaultReadHandleFactory, WriteHandleFactory defaultWriteHandleFactory,
            LinuxSocket socket, boolean active) {
        super(parent, eventLoop, supportingDisconnect, defaultReadHandleFactory, defaultWriteHandleFactory,
                socket, null, active);
        msgHdrCache = new ArrayDeque<>();
        pendingWrites = PendingData.newPendingData();
    }

    /**
     * Returns {@code true} if the usage of {@link io.netty5.channel.unix.SegmentedDatagramPacket} is supported.
     *
     * @return {@code true} if supported, {@code false} otherwise.
     */
    public static boolean isSegmentedDatagramPacketSupported() {
        return false; // TODO should be IOUring.isAvailable();
    }

    @Override
    public boolean isActive() {
        return isOpen() && (getActiveOnOpen() && isRegistered() || super.isActive());
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    private NetworkInterface networkInterface() throws SocketException {
        NetworkInterface iface = getNetworkInterface();
        if (iface == null) {
            SocketAddress localAddress = localAddress();
            if (localAddress instanceof InetSocketAddress) {
                return NetworkInterface.getByInetAddress(((InetSocketAddress) localAddress()).getAddress());
            }
        }
        return null;
    }

    @Override
    public Future<Void> joinGroup(InetAddress multicastAddress) {
        try {
            return joinGroup(multicastAddress, networkInterface(), null);
        } catch (IOException | UnsupportedOperationException e) {
            return newFailedFuture(e);
        }
    }

    @Override
    public Future<Void> joinGroup(
            InetAddress multicastAddress, NetworkInterface networkInterface, InetAddress source) {
        requireNonNull(multicastAddress, "multicastAddress");
        requireNonNull(networkInterface, "networkInterface");

        if (socket.protocolFamily() == SocketProtocolFamily.UNIX) {
            return newFailedFuture(new UnsupportedOperationException("Multicast not supported"));
        }

        Promise<Void> promise = newPromise();
        if (executor().inEventLoop()) {
            joinGroup0(multicastAddress, networkInterface, source, promise);
        } else {
            executor().execute(() -> joinGroup0(multicastAddress, networkInterface, source, promise));
        }
        return promise.asFuture();
    }

    private void joinGroup0(InetAddress multicastAddress, NetworkInterface networkInterface,
                            InetAddress source, Promise<Void> promise) {
        assert executor().inEventLoop();

        try {
            socket.joinGroup(multicastAddress, networkInterface, source);
        } catch (IOException e) {
            promise.setFailure(e);
            return;
        }
        promise.setSuccess(null);
    }

    @Override
    public Future<Void> leaveGroup(InetAddress multicastAddress) {
        try {
            return leaveGroup(multicastAddress, networkInterface(), null);
        } catch (IOException | UnsupportedOperationException e) {
            return newFailedFuture(e);
        }
    }

    @Override
    public Future<Void> leaveGroup(
            InetAddress multicastAddress, NetworkInterface networkInterface, InetAddress source) {
        requireNonNull(multicastAddress, "multicastAddress");
        requireNonNull(networkInterface, "networkInterface");

        if (socket.protocolFamily() == SocketProtocolFamily.UNIX) {
            return newFailedFuture(new UnsupportedOperationException("Multicast not supported"));
        }

        Promise<Void> promise = newPromise();
        if (executor().inEventLoop()) {
            leaveGroup0(multicastAddress, networkInterface, source, promise);
        } else {
            executor().execute(() -> leaveGroup0(multicastAddress, networkInterface, source, promise));
        }
        return promise.asFuture();
    }

    private void leaveGroup0(
            final InetAddress multicastAddress, final NetworkInterface networkInterface, final InetAddress source,
            final Promise<Void> promise) {
        assert executor().inEventLoop();

        try {
            socket.leaveGroup(multicastAddress, networkInterface, source);
        } catch (IOException e) {
            promise.setFailure(e);
            return;
        }
        promise.setSuccess(null);
    }

    @Override
    public Future<Void> block(
            InetAddress multicastAddress, NetworkInterface networkInterface,
            InetAddress sourceToBlock) {
        requireNonNull(multicastAddress, "multicastAddress");
        requireNonNull(sourceToBlock, "sourceToBlock");
        requireNonNull(networkInterface, "networkInterface");
        return newFailedFuture(new UnsupportedOperationException("Multicast block not supported"));
    }

    @Override
    public Future<Void> block(
            InetAddress multicastAddress, InetAddress sourceToBlock) {
        try {
            return block(
                    multicastAddress,
                    networkInterface(),
                    sourceToBlock);
        } catch (IOException | UnsupportedOperationException e) {
            return newFailedFuture(e);
        }
    }

    @Override
    protected int nextReadBufferSize() {
        int expectedCapacity = readHandle().prepareRead();
        if (expectedCapacity == 0) {
            return 0;
        }
        int datagramSize = maxDatagramSize;
        if (datagramSize == 0) {
            datagramSize = expectedCapacity;
        } else {
            datagramSize = Math.min(datagramSize, expectedCapacity);
        }
        return datagramSize;
    }

    @Override
    protected Object submitReadForReadBuffer(Buffer buffer, short readId, boolean nonBlocking) {
        try (var itr = buffer.forEachComponent()) {
            var cmp = itr.firstWritable();
            assert cmp != null;
            long address = cmp.writableNativeAddress();
            int writableBytes = cmp.writableBytes();
            int flags = nonBlocking ? Native.MSG_DONTWAIT : 0;
            if (connected) {
                // Call recv(2) because we have a known peer.
                submissionQueue.addRecv(fd().intValue(), address, 0, writableBytes, flags, readId);
                return buffer;
            }
            // Call recvmsg(2) because we need the peer address for each packet.
            short segmentSize = 0;
            CachedMsgHdrMemory msgHdr = nextMsgHdr();
            msgHdr.write(socket, null, address, writableBytes, segmentSize);
            msgHdr.attachment = buffer;
            submissionQueue.addRecvmsg(fd().intValue(), msgHdr.address(), readId);
            return msgHdr;
        }
    }

    @Override
    protected Object prepareCompleted(Object obj, int result) {
        if (obj instanceof CachedMsgHdrMemory) {
            try (CachedMsgHdrMemory msgHdr = (CachedMsgHdrMemory) obj) {
                Buffer buffer = msgHdr.attachment;
                msgHdr.attachment = null;
                buffer.skipWritableBytes(result);
                return msgHdr.read(this, buffer, result);
            }
        }
        Buffer buffer = (Buffer) super.prepareCompleted(obj, result);
        return new DatagramPacket(buffer, localAddress(), remoteAddress());
    }

    /**
     * {@code byte[]} that can be used as temporary storage to encode the ipv4 address
     */
    byte[] inet4AddressArray() {
        return inet4AddressArray;
    }

    /**
     * {@code byte[]} that can be used as temporary storage to encode the ipv6 address
     */
    byte[] inet6AddressArray() {
        return inet6AddressArray;
    }

    @Override
    protected boolean processRead(ReadSink readSink, Object read) {
        DatagramPacket packet = (DatagramPacket) read;
        Buffer buffer = packet.content();
        readSink.processRead(buffer.capacity(), buffer.readableBytes(), packet);
        return false;
    }

    @Override
    protected Object filterOutboundMessage(Object msg) throws Exception {
        if (msg instanceof DatagramPacket) {
            DatagramPacket packet = (DatagramPacket) msg;
            if (UnixChannelUtil.isBufferCopyNeededForWrite(packet.content())) {
                return packet.replace(intoDirectBuffer(packet.content(), true));
            }
            return msg;
        }
        if (msg instanceof Buffer) {
            Buffer buf = (Buffer) msg;
            if (UnixChannelUtil.isBufferCopyNeededForWrite(buf)) {
                return intoDirectBuffer(buf, true);
            }
            return buf;
        }
        if (msg instanceof AddressedEnvelope) {
            AddressedEnvelope<Object, SocketAddress> envelope = (AddressedEnvelope<Object, SocketAddress>) msg;
            // todo check address type matches socket type (e.g. unix domain sockets or not)
            Object content = envelope.content();
            if (content instanceof Buffer && UnixChannelUtil.isBufferCopyNeededForWrite((Buffer) content)) {
                Buffer buf = (Buffer) content;
                buf = intoDirectBuffer(buf, false);
                try {
                    return new DefaultAddressedEnvelope<>(buf, envelope.recipient(), envelope.sender());
                } finally {
                    SilentDispose.dispose(envelope, logger);
                }
            }
            return envelope;
        }
        return super.filterOutboundMessage(msg);
    }

    @Override
    protected void submitAllWriteMessages(WriteSink writeSink) {
        writeSink.consumeEachFlushedMessage(this::submiteWriteMessage);
    }

    private boolean submiteWriteMessage(Object msg, Promise<Void> promise) {
        final Buffer data;
        final SocketAddress remoteAddress;
        if (msg instanceof AddressedEnvelope) {
            @SuppressWarnings("unchecked")
            AddressedEnvelope<Buffer, SocketAddress> envelope = (AddressedEnvelope<Buffer, SocketAddress>) msg;
            data = envelope.content();
            remoteAddress = envelope.recipient();
        } else {
            data = (Buffer) msg;
            remoteAddress = remoteAddress();
        }

        // Since we remove the messages from WriteSink/OutboundBuffer, it falls to us to close the buffer, and complete
        // the promise.
        promise.asFuture().addListener(data, CLOSE_BUFFER);
        short pendingId = pendingWrites.addPending(promise);

        try (var itr = data.forEachComponent()) {
            var cmp = itr.firstReadable();
            assert cmp != null;
            int fd = fd().intValue();
            if (connected) {
                submissionQueue.addSend(fd, cmp.readableNativeAddress(), 0, cmp.readableBytes(), pendingId);
            } else {
                CachedMsgHdrMemory msgHdr = nextMsgHdr();
                msgHdr.write(socket, remoteAddress, cmp.readableNativeAddress(), cmp.readableBytes(), (short) 0);
                submissionQueue.addSendmsg(fd, msgHdr.address(), pendingId);
                promise.asFuture().addListener(msgHdr);
            }
        }
        return writeHandle().lastWrite(data.readableBytes(), data.readableBytes(), 1);
    }

    @NotNull
    private CachedMsgHdrMemory nextMsgHdr() {
        CachedMsgHdrMemory msgHdr = msgHdrCache.pollFirst();
        if (msgHdr == null) {
            msgHdr = new CachedMsgHdrMemory(msgHdrCache);
        }
        return msgHdr;
    }

    @Override
    void writeComplete(int result, short data) {
        Promise<Void> promise = pendingWrites.removePending(data);
        if (result < 0) {
            promise.setFailure(Errors.newIOException("send/sendmsg", result));
        } else {
            promise.setSuccess(null);
        }
    }

    @Override
    protected void doDisconnect() throws Exception {
        super.doDisconnect();
        connected = false;
        cacheAddresses(local, null);
        remote = null;
    }

    @Override
    protected boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress, Buffer initialData) throws Exception {
        boolean connected = super.doConnect(remoteAddress, localAddress, initialData);
        if (connected) {
            this.connected = true;
        }
        return connected;
    }

    @Override
    protected void doShutdown(ChannelShutdownDirection direction) throws Exception {
        switch (direction) {
            case Inbound:
                inputShutdown = true;
                break;
            case Outbound:
                outputShutdown = true;
                break;
            default:
                throw new IllegalArgumentException("Unknown direction: " + direction);
        }
    }

    @Override
    public boolean isShutdown(ChannelShutdownDirection direction) {
        if (!isActive()) {
            return true;
        }
        switch (direction) {
            case Inbound:
                return inputShutdown;
            case Outbound:
                return outputShutdown;
            default:
                throw new AssertionError();
        }
    }

    @Override
    protected void doClose() {
        super.doClose();
        CachedMsgHdrMemory msgHdr;
        while ((msgHdr = msgHdrCache.pollFirst()) != null) {
            msgHdr.close();
        }
    }

    @Override
    protected @Nullable Future<Void> currentWritePromise() {
        if (pendingWrites.isEmpty()) {
            return null;
        }
        PromiseCombiner combiner = new PromiseCombiner(executor());
        pendingWrites.forEach((id, promise) -> combiner.add(promise.asFuture()));
        Promise<Void> allWritesPromise = newPromise();
        combiner.finish(allWritesPromise);
        return allWritesPromise.asFuture();
    }

    @SuppressWarnings("unchecked")
    @Override
    protected <T> T getExtendedOption(ChannelOption<T> option) {
        if (option == ChannelOption.DATAGRAM_CHANNEL_ACTIVE_ON_REGISTRATION) {
            return (T) Boolean.valueOf(activeOnOpen);
        }
        if (option == IOUringChannelOption.MAX_DATAGRAM_PAYLOAD_SIZE) {
            return (T) Integer.valueOf(getMaxDatagramSize());
        }
        return super.getExtendedOption(option);
    }

    @Override
    protected <T> void setExtendedOption(ChannelOption<T> option, T value) {
        if (option == ChannelOption.DATAGRAM_CHANNEL_ACTIVE_ON_REGISTRATION) {
            setActiveOnOpen((Boolean) value);
        } else if (option == IOUringChannelOption.MAX_DATAGRAM_PAYLOAD_SIZE) {
            setMaxDatagramSize((Integer) value);
        }
        super.setExtendedOption(option, value);
    }

    @Override
    protected boolean isExtendedOptionSupported(ChannelOption<?> option) {
        if (option == ChannelOption.DATAGRAM_CHANNEL_ACTIVE_ON_REGISTRATION ||
                option == IOUringChannelOption.MAX_DATAGRAM_PAYLOAD_SIZE) {
            return true;
        }
        return super.isExtendedOptionSupported(option);
    }

    private void setActiveOnOpen(boolean activeOnOpen) {
        if (isRegistered()) {
            throw new IllegalStateException("Can only changed before channel was registered");
        }
        this.activeOnOpen = activeOnOpen;
    }

    boolean getActiveOnOpen() {
        return activeOnOpen;
    }

    private int getMaxDatagramSize() {
        return maxDatagramSize;
    }

    private void setMaxDatagramSize(int maxDatagramSize) {
        this.maxDatagramSize = maxDatagramSize;
    }

    private static class CachedMsgHdrMemory extends MsgHdrMemory
            implements FutureListener<Void>, AutoCloseable {
        private final Deque<CachedMsgHdrMemory> cache;
        private Buffer attachment;
        CachedMsgHdrMemory(Deque<CachedMsgHdrMemory> cache) {
            super(0);
            this.cache = cache;
        }

        @Override
        public void operationComplete(Future<? extends Void> future) {
            close();
        }

        @Override
        public void close() {
            if (!cache.offerFirst(this)) {
                release();
            }
        }

        @Override
        void release() {
            super.release();
            if (attachment != null) {
                attachment.close();
                attachment = null;
            }
        }
    }
}