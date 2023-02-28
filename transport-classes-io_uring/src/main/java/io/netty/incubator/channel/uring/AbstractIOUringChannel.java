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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ChannelPromiseNotifier;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.EventLoop;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.ChannelInputShutdownReadComplete;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.channel.unix.Buffer;
import io.netty.channel.unix.Errors;
import io.netty.channel.unix.FileDescriptor;
import io.netty.channel.unix.UnixChannel;
import io.netty.channel.unix.UnixChannelUtil;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static io.netty.channel.unix.Errors.*;
import static io.netty.channel.unix.UnixChannelUtil.*;
import static io.netty.util.internal.ObjectUtil.*;

abstract class AbstractIOUringChannel extends AbstractChannel implements UnixChannel {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractIOUringChannel.class);
    private static final ChannelMetadata METADATA = new ChannelMetadata(false);
    final LinuxSocket socket;
    protected volatile boolean active;

    // Different masks for outstanding I/O operations.
    private static final int POLL_IN_SCHEDULED = 1;
    private static final int POLL_OUT_SCHEDULED = 1 << 2;
    private static final int POLL_RDHUP_SCHEDULED = 1 << 3;
    private static final int WRITE_SCHEDULED = 1 << 4;
    private static final int READ_SCHEDULED = 1 << 5;
    private static final int CONNECT_SCHEDULED = 1 << 6;

    // A byte is enough for now.
    private byte ioState;

    // It's possible that multiple read / writes are issued. We need to keep track of these.
    // Let's limit the amount of pending writes and reads by Short.MAX_VALUE. Maybe Byte.MAX_VALUE would also be good
    // enough but let's be a bit more flexible for now.
    private short numOutstandingWrites;
    private short numOutstandingReads;

    private ChannelPromise delayedClose;
    private boolean inputClosedSeenErrorOnRead;

    /**
     * The future of the current connection attempt.  If not null, subsequent connection attempts will fail.
     */
    private ChannelPromise connectPromise;
    private ScheduledFuture<?> connectTimeoutFuture;
    private SocketAddress requestedRemoteAddress;
    private ByteBuffer remoteAddressMemory;
    private MsgHdrMemoryArray msgHdrMemoryArray;

    private IOUringSubmissionQueue submissionQueue;

    private volatile SocketAddress local;
    private volatile SocketAddress remote;

    AbstractIOUringChannel(final Channel parent, LinuxSocket socket) {
        this(parent, socket, true);
    }

    AbstractIOUringChannel(final Channel parent, LinuxSocket socket, boolean active) {
        super(parent);
        this.socket = checkNotNull(socket, "fd");

        if (active) {
            // Directly cache the remote and local addresses
            // See https://github.com/netty/netty/issues/2359
            this.active = true;
            this.local = socket.localAddress();
            this.remote = socket.remoteAddress();
        }

        if (parent != null) {
            logger.trace("Create Channel Socket: {}", socket.intValue());
        } else {
            logger.trace("Create Server Socket: {}", socket.intValue());
        }
    }

    AbstractIOUringChannel(Channel parent, LinuxSocket fd, SocketAddress remote) {
        super(parent);
        this.socket = checkNotNull(fd, "fd");
        this.active = true;

        // Directly cache the remote and local addresses
        // See https://github.com/netty/netty/issues/2359
        this.remote = remote;
        this.local = fd.localAddress();
    }

    public boolean isOpen() {
        return socket.isOpen();
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    public FileDescriptor fd() {
        return socket;
    }

    @Override
    protected abstract AbstractUringUnsafe newUnsafe();

    AbstractUringUnsafe ioUringUnsafe() {
        return (AbstractUringUnsafe) unsafe();
    }

    @Override
    protected boolean isCompatible(final EventLoop loop) {
        return loop instanceof IOUringEventLoop;
    }

    protected final ByteBuf newDirectBuffer(ByteBuf buf) {
        return newDirectBuffer(buf, buf);
    }

    protected final ByteBuf newDirectBuffer(Object holder, ByteBuf buf) {
        final int readableBytes = buf.readableBytes();
        if (readableBytes == 0) {
            ReferenceCountUtil.release(holder);
            return Unpooled.EMPTY_BUFFER;
        }

        final ByteBufAllocator alloc = alloc();
        if (alloc.isDirectBufferPooled()) {
            return newDirectBuffer0(holder, buf, alloc, readableBytes);
        }

        final ByteBuf directBuf = ByteBufUtil.threadLocalDirectBuffer();
        if (directBuf == null) {
            return newDirectBuffer0(holder, buf, alloc, readableBytes);
        }

        directBuf.writeBytes(buf, buf.readerIndex(), readableBytes);
        ReferenceCountUtil.safeRelease(holder);
        return directBuf;
    }

    private static ByteBuf newDirectBuffer0(Object holder, ByteBuf buf, ByteBufAllocator alloc, int capacity) {
        final ByteBuf directBuf = alloc.directBuffer(capacity);
        directBuf.writeBytes(buf, buf.readerIndex(), capacity);
        ReferenceCountUtil.safeRelease(holder);
        return directBuf;
    }

    @Override
    protected void doDisconnect() throws Exception {
    }

    IOUringSubmissionQueue submissionQueue() {
        return submissionQueue;
    }

    private void freeRemoteAddressMemory() {
        if (remoteAddressMemory != null) {
            Buffer.free(remoteAddressMemory);
            remoteAddressMemory = null;
        }
    }

    private void freeMsgHdrArray() {
        if (msgHdrMemoryArray != null) {
            msgHdrMemoryArray.release();
            msgHdrMemoryArray = null;
        }
    }

    boolean ioScheduled() {
        return ioState != 0;
    }

    @Override
    protected void doClose() throws Exception {
        freeRemoteAddressMemory();
        freeMsgHdrArray();
        active = false;

        // Even if we allow half closed sockets we should give up on reading. Otherwise we may allow a read attempt on a
        // socket which has not even been connected yet. This has been observed to block during unit tests.
        // inputClosedSeenErrorOnRead = true;
        try {
            ChannelPromise promise = connectPromise;
            if (promise != null) {
                // Use tryFailure() instead of setFailure() to avoid the race against cancel().
                promise.tryFailure(new ClosedChannelException());
                connectPromise = null;
            }

            cancelConnectTimeoutFuture();
        } finally {
            if (submissionQueue != null) {
                if (socket.markClosed()) {
                    submissionQueue.addClose(fd().intValue(), (short) 0);
                }
            } else {
                // This one was never registered just use a syscall to close.
                socket.close();
            }
        }
    }

    @Override
    protected void doBeginRead() {
        if ((ioState & POLL_IN_SCHEDULED) == 0) {
            ioUringUnsafe().schedulePollIn();
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) {
        if ((ioState & WRITE_SCHEDULED) != 0) {
            return;
        }
        if (scheduleWrite(in) > 0) {
            ioState |= WRITE_SCHEDULED;
        }
    }

    private int scheduleWrite(ChannelOutboundBuffer in) {
        if (delayedClose != null || numOutstandingWrites == Short.MAX_VALUE) {
            return 0;
        }
        if (in == null) {
            return 0;
        }

        int msgCount = in.size();
        if (msgCount == 0) {
            return 0;
        }
        Object msg = in.current();

        if (msgCount > 1) {
            numOutstandingWrites = (short) ioUringUnsafe().scheduleWriteMultiple(in);
        } else if (msg instanceof ByteBuf && ((ByteBuf) msg).nioBufferCount() > 1 ||
                    (msg instanceof ByteBufHolder && ((ByteBufHolder) msg).content().nioBufferCount() > 1)) {
            // We also need some special handling for CompositeByteBuf
            numOutstandingWrites = (short) ioUringUnsafe().scheduleWriteMultiple(in);
        } else {
            numOutstandingWrites = (short) ioUringUnsafe().scheduleWriteSingle(msg);
        }
        // Ensure we never overflow
        assert numOutstandingWrites > 0;
        return numOutstandingWrites;
    }

    private void schedulePollOut() {
        assert (ioState & POLL_OUT_SCHEDULED) == 0;
        IOUringSubmissionQueue submissionQueue = submissionQueue();
        submissionQueue.addPollOut(socket.intValue());
        ioState |= POLL_OUT_SCHEDULED;
    }

    final void schedulePollRdHup() {
        assert (ioState & POLL_RDHUP_SCHEDULED) == 0;
        IOUringSubmissionQueue submissionQueue = submissionQueue();
        submissionQueue.addPollRdHup(fd().intValue());
        ioState |= POLL_RDHUP_SCHEDULED;
    }

    final void resetCachedAddresses() {
        local = socket.localAddress();
        remote = socket.remoteAddress();
    }

    abstract class AbstractUringUnsafe extends AbstractUnsafe {
        private IOUringRecvByteAllocatorHandle allocHandle;

        /**
         * Schedule the write of multiple messages in the {@link ChannelOutboundBuffer} and returns the number of
         * {@link #writeComplete(int, int)} calls that are expected because of the scheduled write.
         */
        protected abstract int scheduleWriteMultiple(ChannelOutboundBuffer in);

        /**
         * Schedule the write of a single message and returns the number of {@link #writeComplete(int, int)} calls
         * that are expected because of the scheduled write
         */
        protected abstract int scheduleWriteSingle(Object msg);

        @Override
        public void close(ChannelPromise promise) {
            if ((ioState & (WRITE_SCHEDULED | READ_SCHEDULED)) == 0) {
                // force close can ignore any CONNECT_SCHEDULED
                forceClose(promise);
            } else {
                if (delayedClose == null || delayedClose.isVoid()) {
                    // We have a write operation pending that should be completed asap.
                    // We will do the actual close operation one this write result is returned as otherwise
                    // we may get into trouble as we may close the fd while we did not process the write yet.
                    delayedClose = promise;
                } else {
                    if (promise.isVoid()) {
                        return;
                    }
                    delayedClose.addListener(new ChannelPromiseNotifier(promise));
                }
            }
        }

        private void forceClose(ChannelPromise promise) {
            super.close(promise);
        }

        @Override
        protected final void flush0() {
            // Flush immediately only when there's no pending flush.
            // If there's a pending flush operation, event loop will call forceFlush() later,
            // and thus there's no need to call it now.
            if ((ioState & POLL_OUT_SCHEDULED) == 0) {
                super.flush0();
            }
        }

        private void fulfillConnectPromise(ChannelPromise promise, Throwable cause) {
            if (promise == null) {
                // Closed via cancellation and the promise has been notified already.
                return;
            }

            // Use tryFailure() instead of setFailure() to avoid the race against cancel().
            promise.tryFailure(cause);
            closeIfClosed();
        }

        private void fulfillConnectPromise(ChannelPromise promise, boolean wasActive) {
            if (promise == null) {
                // Closed via cancellation and the promise has been notified already.
                return;
            }
            active = true;

            if (local == null) {
                local = socket.localAddress();
            }
            computeRemote();

            // Register POLLRDHUP
            schedulePollRdHup();

            // Get the state as trySuccess() may trigger an ChannelFutureListener that will close the Channel.
            // We still need to ensure we call fireChannelActive() in this case.
            boolean active = isActive();

            // trySuccess() will return false if a user cancelled the connection attempt.
            boolean promiseSet = promise.trySuccess();

            // Regardless if the connection attempt was cancelled, channelActive() event should be triggered,
            // because what happened is what happened.
            if (!wasActive && active) {
                pipeline().fireChannelActive();
            }

            // If a user cancelled the connection attempt, close the channel, which is followed by channelInactive().
            if (!promiseSet) {
                close(voidPromise());
            }
        }

        final IOUringRecvByteAllocatorHandle newIOUringHandle(RecvByteBufAllocator.ExtendedHandle handle) {
            return new IOUringRecvByteAllocatorHandle(handle);
        }

        @Override
        public final IOUringRecvByteAllocatorHandle recvBufAllocHandle() {
            if (allocHandle == null) {
                allocHandle = newIOUringHandle((RecvByteBufAllocator.ExtendedHandle) super.recvBufAllocHandle());
            }
            return allocHandle;
        }

        final void shutdownInput(boolean rdHup) {
            logger.trace("shutdownInput Fd: {}", fd().intValue());
            if (!socket.isInputShutdown()) {
                if (isAllowHalfClosure(config())) {
                    try {
                        socket.shutdown(true, false);
                    } catch (IOException ignored) {
                        // We attempted to shutdown and failed, which means the input has already effectively been
                        // shutdown.
                        fireEventAndClose(ChannelInputShutdownEvent.INSTANCE);
                        return;
                    } catch (NotYetConnectedException ignore) {
                        // We attempted to shutdown and failed, which means the input has already effectively been
                        // shutdown.
                    }
                    pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
                } else {
                    close(voidPromise());
                }
            } else if (!rdHup) {
                inputClosedSeenErrorOnRead = true;
                pipeline().fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE);
            }
        }

        private void fireEventAndClose(Object evt) {
            pipeline().fireUserEventTriggered(evt);
            close(voidPromise());
        }

        final void schedulePollIn() {
            assert (ioState & POLL_IN_SCHEDULED) == 0;
            if (!isActive() || shouldBreakIoUringInReady(config())) {
                return;
            }
            ioState |= POLL_IN_SCHEDULED;
            IOUringSubmissionQueue submissionQueue = submissionQueue();
            submissionQueue.addPollIn(socket.intValue());
        }

        final void processDelayedClose() {
            ChannelPromise promise = delayedClose;
            if (promise != null && (ioState & (READ_SCHEDULED | WRITE_SCHEDULED | CONNECT_SCHEDULED)) == 0) {
                delayedClose = null;
                forceClose(promise);
            }
        }

        final void readComplete(int res, int data) {
            assert numOutstandingReads > 0;
            if (--numOutstandingReads == 0) {
                ioState &= ~READ_SCHEDULED;
            }

            readComplete0(res, data, numOutstandingReads);
        }

        /**
         * Called once a read was completed.
         */
        protected abstract void readComplete0(int res, int data, int outstandingCompletes);

        /**
         * Called once POLLRDHUP event is ready to be processed
         */
        final void pollRdHup(int res) {
            ioState &= ~POLL_RDHUP_SCHEDULED;
            if (res == Native.ERRNO_ECANCELED_NEGATIVE) {
                return;
            }

            // Mark that we received a POLLRDHUP and so need to continue reading until all the input ist drained.
            recvBufAllocHandle().rdHupReceived();

            if (isActive()) {
                scheduleFirstReadIfNeeded();
            } else {
                // Just to be safe make sure the input marked as closed.
                shutdownInput(true);
            }
        }

        /**
         * Called once POLLIN event is ready to be processed
         */
        final void pollIn(int res) {
            ioState &= ~POLL_IN_SCHEDULED;

            if (res == Native.ERRNO_ECANCELED_NEGATIVE) {
                return;
            }

            scheduleFirstReadIfNeeded();
        }

        private void scheduleFirstReadIfNeeded() {
            if ((ioState & READ_SCHEDULED) == 0) {
                scheduleFirstRead();
            }
        }

        private void scheduleFirstRead() {
            // This is a new "read loop" so we need to reset the allocHandle.
            final ChannelConfig config = config();
            final IOUringRecvByteAllocatorHandle allocHandle = recvBufAllocHandle();
            allocHandle.reset(config);
            scheduleRead();
        }

        protected final void scheduleRead() {
            // Only schedule another read if the fd is still open.
            if (delayedClose == null && fd().isOpen() && (ioState & READ_SCHEDULED) == 0) {
                numOutstandingReads = (short) scheduleRead0();
                if (numOutstandingReads > 0) {
                    ioState |= READ_SCHEDULED;
                }
            }
        }

        /**
         * Schedule a read and returns the number of {@link #readComplete(int, int)} calls that are expected because of
         * the scheduled read.
         */
        protected abstract int scheduleRead0();

        /**
         * Called once POLLOUT event is ready to be processed
         */
        final void pollOut(int res) {
            ioState &= ~POLL_OUT_SCHEDULED;

            if (res == Native.ERRNO_ECANCELED_NEGATIVE) {
                return;
            }
            // pending connect
            if (connectPromise != null) {
                // Note this method is invoked by the event loop only if the connection attempt was
                // neither cancelled nor timed out.

                assert eventLoop().inEventLoop();

                boolean connectStillInProgress = false;
                try {
                    boolean wasActive = isActive();
                    if (!socket.finishConnect()) {
                        connectStillInProgress = true;
                        return;
                    }
                    fulfillConnectPromise(connectPromise, wasActive);
                } catch (Throwable t) {
                    fulfillConnectPromise(connectPromise, annotateConnectException(t, requestedRemoteAddress));
                } finally {
                    if (!connectStillInProgress) {
                        // Check for null as the connectTimeoutFuture is only created if a connectTimeoutMillis > 0
                        // is used
                        // See https://github.com/netty/netty/issues/1770
                        cancelConnectTimeoutFuture();
                        connectPromise = null;
                    } else {
                        // The connect was not done yet, register for POLLOUT again
                        schedulePollOut();
                    }
                }
            } else if (!socket.isOutputShutdown()) {
                // Try writing again
                super.flush0();
            }
        }

        /**
         * Called once a write was completed.
         */
        final void writeComplete(int res, int data) {
            if ((ioState & CONNECT_SCHEDULED) != 0) {
                // The writeComplete(...) callback was called because of a sendmsg(...) result that was used for
                // TCP_FASTOPEN_CONNECT.
                freeMsgHdrArray();
                if (res > 0) {
                    // Connect complete!
                    outboundBuffer().removeBytes(res);

                    // Explicit pass in 0 as this is returned by a connect(...) call when it was successful.
                    connectComplete(0);
                } else if (res == ERRNO_EINPROGRESS_NEGATIVE || res == 0) {
                    // This happens when we (as a client) have no pre-existing cookie for doing a fast-open connection.
                    // In this case, our TCP connection will be established normally, but no data was transmitted at this time.
                    // We'll just transmit the data with normal writes later.
                    // Let's submit a normal connect.
                    submitConnect((InetSocketAddress) requestedRemoteAddress);
                } else {
                    // There was an error, handle it as a normal connect error.
                    connectComplete(res);
                }
                return;
            }
            assert numOutstandingWrites > 0;
            --numOutstandingWrites;

            boolean writtenAll = writeComplete0(res, data, numOutstandingWrites);
            if (!writtenAll && (ioState & POLL_OUT_SCHEDULED) == 0) {
                // We were not able to write everything, let's register for POLLOUT
                schedulePollOut();
            }

            // We only reset this once we are done with calling removeBytes(...) as otherwise we may trigger a write
            // while still removing messages internally in removeBytes(...) which then may corrupt state.
            if (numOutstandingWrites == 0) {
                ioState &= ~WRITE_SCHEDULED;

                // If we could write all and we did not schedule a pollout yet let us try to write again
                if (writtenAll && (ioState & POLL_OUT_SCHEDULED) == 0) {
                    doWrite(unsafe().outboundBuffer());
                }
            }
        }

        /**
         * Called once a write was completed.
         */
        abstract boolean writeComplete0(int res, int data, int outstanding);

        /**
         * Connect was completed.
         */
        void connectComplete(int res) {
            ioState &= ~CONNECT_SCHEDULED;
            freeRemoteAddressMemory();

            if (res == ERRNO_EINPROGRESS_NEGATIVE) {
                // connect not complete yet need to wait for poll_out event
                schedulePollOut();
            } else {
                try {
                    if (res == 0) {
                        fulfillConnectPromise(connectPromise, active);
                    } else {
                        try {
                            Errors.throwConnectException("io_uring connect", res);
                        } catch (Throwable cause) {
                            fulfillConnectPromise(connectPromise, cause);
                        }
                    }
                } finally {
                    // Check for null as the connectTimeoutFuture is only created if a connectTimeoutMillis > 0 is
                    // used
                    // See https://github.com/netty/netty/issues/1770
                    cancelConnectTimeoutFuture();
                    connectPromise = null;
                }
            }
        }

        @Override
        public void connect(
                final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }

            if (delayedClose != null) {
                promise.tryFailure(annotateConnectException(new ClosedChannelException(), remoteAddress));
                return;
            }
            try {
                if (connectPromise != null) {
                    throw new ConnectionPendingException();
                }
                if (localAddress instanceof InetSocketAddress) {
                    checkResolvable((InetSocketAddress) localAddress);
                }

                if (remoteAddress instanceof InetSocketAddress) {
                    checkResolvable((InetSocketAddress) remoteAddress);
                }

                if (remote != null) {
                    // Check if already connected before trying to connect. This is needed as connect(...) will not return -1
                    // and set errno to EISCONN if a previous connect(...) attempt was setting errno to EINPROGRESS and finished
                    // later.
                    throw new AlreadyConnectedException();
                }

                if (localAddress != null) {
                    socket.bind(localAddress);
                }

                InetSocketAddress inetSocketAddress = (InetSocketAddress) remoteAddress;

                ByteBuf initialData = null;
                if (IOUring.isTcpFastOpenClientSideAvailable() &&
                        config().getOption(ChannelOption.TCP_FASTOPEN_CONNECT) == Boolean.TRUE) {
                    ChannelOutboundBuffer outbound = unsafe().outboundBuffer();
                    outbound.addFlush();
                    Object curr;
                    if ((curr = outbound.current()) instanceof ByteBuf) {
                        initialData = (ByteBuf) curr;
                    }
                }
                if (initialData != null) {
                    AbstractIOUringChannel.this.msgHdrMemoryArray = new MsgHdrMemoryArray(1);
                    MsgHdrMemory hdr = msgHdrMemoryArray.hdr(0);
                    hdr.write(socket, inetSocketAddress, initialData.memoryAddress(), initialData.readableBytes(), (short) 0);
                    final IOUringSubmissionQueue ioUringSubmissionQueue = submissionQueue();
                    ioUringSubmissionQueue.addSendmsg(socket.intValue(), hdr.address(), Native.MSG_FASTOPEN, (short) 0);
                } else {
                    submitConnect(inetSocketAddress);
                }

                ioState |= CONNECT_SCHEDULED;
            } catch (Throwable t) {
                closeIfClosed();
                promise.tryFailure(annotateConnectException(t, remoteAddress));
                return;
            }
            connectPromise = promise;
            requestedRemoteAddress = remoteAddress;
            // Schedule connect timeout.
            int connectTimeoutMillis = config().getConnectTimeoutMillis();
            if (connectTimeoutMillis > 0) {
                connectTimeoutFuture = eventLoop().schedule(new Runnable() {
                    @Override
                    public void run() {
                        ChannelPromise connectPromise = AbstractIOUringChannel.this.connectPromise;
                        if (connectPromise != null && !connectPromise.isDone() &&
                                connectPromise.tryFailure(new ConnectTimeoutException(
                                        "connection timed out: " + remoteAddress))) {
                            close(voidPromise());
                        }
                    }
                }, connectTimeoutMillis, TimeUnit.MILLISECONDS);
            }

            promise.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) {
                    if (future.isCancelled()) {
                        cancelConnectTimeoutFuture();
                        connectPromise = null;
                        close(voidPromise());
                    }
                }
            });
        }
    }

    private void submitConnect(InetSocketAddress inetSocketAddress) {
        remoteAddressMemory = Buffer.allocateDirectWithNativeOrder(Native.SIZEOF_SOCKADDR_STORAGE);
        long remoteAddressMemoryAddress = Buffer.memoryAddress(remoteAddressMemory);

        SockaddrIn.write(socket.isIpv6(), remoteAddressMemoryAddress, inetSocketAddress);

        final IOUringSubmissionQueue ioUringSubmissionQueue = submissionQueue();
        ioUringSubmissionQueue.addConnect(socket.intValue(), remoteAddressMemoryAddress,
                Native.SIZEOF_SOCKADDR_STORAGE, (short) 0);
    }

    @Override
    protected Object filterOutboundMessage(Object msg) {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            return UnixChannelUtil.isBufferCopyNeededForWrite(buf)? newDirectBuffer(buf) : buf;
        }

        throw new UnsupportedOperationException("unsupported message type");
    }

    @Override
    protected void doRegister() throws Exception {
        IOUringEventLoop eventLoop = (IOUringEventLoop) eventLoop();
        eventLoop.add(this);
        submissionQueue = eventLoop.getRingBuffer().ioUringSubmissionQueue();
    }

    @Override
    protected final void doDeregister() {
        IOUringSubmissionQueue submissionQueue = submissionQueue();

        if (submissionQueue != null) {
            if ((ioState & (POLL_IN_SCHEDULED | POLL_OUT_SCHEDULED | POLL_RDHUP_SCHEDULED)) == 0) {
                ((IOUringEventLoop) eventLoop()).remove(this);
                return;
            }
            if ((ioState & POLL_IN_SCHEDULED) != 0) {
                submissionQueue.addPollRemove(socket.intValue(), Native.POLLIN);
            }
            if ((ioState & POLL_OUT_SCHEDULED) != 0) {
                submissionQueue.addPollRemove(socket.intValue(), Native.POLLOUT);
            }
            if ((ioState & POLL_RDHUP_SCHEDULED) != 0) {
                submissionQueue.addPollRemove(socket.intValue(), Native.POLLRDHUP);
            }
        }
    }

    @Override
    protected void doBind(final SocketAddress local) throws Exception {
        if (local instanceof InetSocketAddress) {
            checkResolvable((InetSocketAddress) local);
        }
        socket.bind(local);
        this.local = socket.localAddress();
    }

    protected static void checkResolvable(InetSocketAddress addr) {
        if (addr.isUnresolved()) {
            throw new UnresolvedAddressException();
        }
    }

    @Override
    protected SocketAddress localAddress0() {
        return local;
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return remote;
    }

    private static boolean isAllowHalfClosure(ChannelConfig config) {
        return config instanceof SocketChannelConfig &&
               ((SocketChannelConfig) config).isAllowHalfClosure();
    }

    private void cancelConnectTimeoutFuture() {
        if (connectTimeoutFuture != null) {
            connectTimeoutFuture.cancel(false);
            connectTimeoutFuture = null;
        }
    }

    private void computeRemote() {
        if (requestedRemoteAddress instanceof InetSocketAddress) {
            remote = computeRemoteAddr((InetSocketAddress) requestedRemoteAddress, socket.remoteAddress());
        }
    }

    private boolean shouldBreakIoUringInReady(ChannelConfig config) {
        return socket.isInputShutdown() && (inputClosedSeenErrorOnRead || !isAllowHalfClosure(config));
    }

    public void clearPollFlag(int pollMask) {
        if (pollMask == Native.POLLIN) {
            ioState &= ~POLL_IN_SCHEDULED;
        } else if (pollMask == Native.POLLOUT) {
            ioState &= ~POLL_OUT_SCHEDULED;
        } else if (pollMask == Native.POLLRDHUP) {
            ioState &= ~POLL_RDHUP_SCHEDULED;
        }
     }
}
