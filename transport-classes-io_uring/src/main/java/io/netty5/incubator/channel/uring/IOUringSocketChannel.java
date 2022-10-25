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
import io.netty5.channel.AbstractChannel;
import io.netty5.channel.AdaptiveReadHandleFactory;
import io.netty5.channel.ChannelPipeline;
import io.netty5.channel.ChannelShutdownDirection;
import io.netty5.channel.EventLoop;
import io.netty5.channel.FileRegion;
import io.netty5.channel.ReadHandleFactory;
import io.netty5.channel.WriteHandleFactory;
import io.netty5.channel.socket.SocketChannel;
import io.netty5.channel.socket.SocketChannelWriteHandleFactory;
import io.netty5.channel.unix.Errors;
import io.netty5.channel.unix.IovArray;
import io.netty5.channel.unix.UnixChannelUtil;
import io.netty5.util.concurrent.Future;
import io.netty5.util.concurrent.FutureListener;
import io.netty5.util.concurrent.Promise;
import io.netty5.util.internal.StringUtil;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.WritableByteChannel;

import static io.netty5.channel.unix.Limits.IOV_MAX;
import static io.netty5.channel.unix.Limits.SSIZE_MAX;
import static java.util.Objects.requireNonNull;

public final class IOUringSocketChannel extends AbstractIOUringChannel<IOUringServerSocketChannel>
        implements SocketChannel {
    private final IovArray writeIovs;
    private Promise<Void> writePromise;
    private boolean moreWritesPending;

    public IOUringSocketChannel(EventLoop eventLoop) {
        this(null, eventLoop, true, new AdaptiveReadHandleFactory(),
                new SocketChannelWriteHandleFactory(Integer.MAX_VALUE, SSIZE_MAX),
                LinuxSocket.newSocketStream(), null, false);
    }

    IOUringSocketChannel(
            IOUringServerSocketChannel parent, EventLoop eventLoop, boolean supportingDisconnect,
            ReadHandleFactory defaultReadHandleFactory, WriteHandleFactory defaultWriteHandleFactory,
            LinuxSocket socket, SocketAddress remote, boolean active) {
        super(parent, eventLoop, supportingDisconnect, defaultReadHandleFactory, defaultWriteHandleFactory,
                socket, remote, active);
        writeIovs = new IovArray();
    }

    @Override
    protected boolean processRead(ReadSink readSink, Object read) {
        Buffer buffer = (Buffer) read;
        if (buffer.readableBytes() == 0) {
            // Reading zero bytes means we got EOF, and we should close the channel.
            buffer.close();
            return true;
        }
        readSink.processRead(buffer.capacity(), buffer.readableBytes(), buffer);
        return false;
    }

    @Override
    protected void submitAllWriteMessages(WriteSink writeSink) {
        // We need to submit all messages as a single IO, in order to ensure ordering.
        // If we already have an outstanding write promise, we can't write anymore until it completes.
        if (writePromise != null) {
            writeSink.complete(0, 0, 0, false);
            return;
        }
        writePromise = executor().newPromise();
        writeIovs.clear();
        writeSink.consumeEachFlushedMessage(this::submitWriteMessage);
        submissionQueue.addWritev(fd().intValue(), writeIovs.memoryAddress(0), writeIovs.count(), (short) 0);
        // Super-class will submit our IOs immediately after this, so the writeIovs can be reused in the next iteration.
    }

    @Override
    protected Object filterOutboundMessage(Object msg) throws Exception {
        // todo file descriptors???
//        if (socket.protocolFamily() == SocketProtocolFamily.UNIX && msg instanceof FileDescriptor) {
//            return msg;
//        }
        if (msg instanceof Buffer) {
            Buffer buf = (Buffer) msg;
            if (UnixChannelUtil.isBufferCopyNeededForWrite(buf)) {
                return intoDirectBuffer(buf, true);
            }
            return buf;
        }

        if (msg instanceof FileRegion) {
            return msg;
        }

        if (msg instanceof RegionWriter) {
            return msg;
        }

        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg));
    }

    private boolean submitWriteMessage(Object msg, Promise<Void> promise) {
        if (msg instanceof Buffer) {
            Buffer buf = (Buffer) msg;
            if (buf.readableBytes() + writeIovs.size() < writeIovs.maxBytes() &&
                    buf.countReadableComponents() + writeIovs.count() < IOV_MAX) {
                writePromise.asFuture().cascadeTo(promise);
                writeIovs.addReadable(buf);
            } else {
                return false;
            }
        } else if (msg instanceof FileRegion) {
            FileRegion region = (FileRegion) msg;
            RegionWriter writer = new RegionWriter(region, promise);
            writer.enqueueWrites();
            writePromise.asFuture().addListener(writer);
        } else if (msg instanceof RegionWriter) {
            // Continuation of previous file region.
            RegionWriter writer = (RegionWriter) msg;
            writer.enqueueWrites();
            writePromise.asFuture().addListener(writer);
        } else {
            // Should never reach here
            throw new AssertionError("Unrecognized message: " + msg);
        }
        return true;
    }

    @Override
    void writeComplete(int result, short data) {
        if (result < 0) {
            writePromise.setFailure(Errors.newIOException("write/flush", result));
        } else {
            writePromise.setSuccess(null);
        }
        writePromise = null;
        if (moreWritesPending) {
            moreWritesPending = false;
            writeFlushedNow();
        }
    }

    @Override
    protected boolean isWriteFlushedScheduled() {
        // Avoid queueing new write IOs if we already have a write IO scheduled.
        // We only do one write at a time, because on TCP we have to do the writes in-order,
        // and operations in io_uring can complete out-of-order.
        moreWritesPending = true;
        return writePromise != null;
    }

    @Override
    protected ChannelPipeline newChannelPipeline() {
        return new IOUringSocketPipeline(this);
    }

    @Override
    protected void doShutdown(ChannelShutdownDirection direction) throws Exception {
        requireNonNull(direction, "direction");
        switch (direction) {
            case Inbound:
                try {
                    socket.shutdown(true, false);
                } catch (NotYetConnectedException ignore) {
                    // We attempted to shutdown and failed, which means the input has already effectively been
                    // shutdown.
                }
                break;
            case Outbound:
                socket.shutdown(false, true);
                break;
            default:
                throw new AssertionError("unhandled direction: " + direction);
        }
    }

    @Override
    public boolean isShutdown(ChannelShutdownDirection direction) {
        requireNonNull(direction, "direction");
        switch (direction) {
            case Inbound:
                return socket.isInputShutdown();
            case Outbound:
                return socket.isOutputShutdown();
            default:
                throw new AssertionError("unhandled direction: " + direction);
        }
    }

    @Override
    protected void doClose() {
        super.doClose();
        writeIovs.release();
    }

    @Override
    protected @Nullable Future<Void> currentWritePromise() {
        if (writePromise == null) {
            return null;
        }
        return writePromise.asFuture();
    }

    private final class RegionWriter implements WritableByteChannel, FutureListener<Void> {
        private final FileRegion region;
        private final Promise<Void> promise;
        private long position;

        RegionWriter(FileRegion region, Promise<Void> promise) {
            this.region = region;
            this.position = region.position();
            this.promise = promise;
        }

        public void enqueueWrites() {
            try {
                region.transferTo(this, position);
            } catch (IOException e) {
                promise.setFailure(e);
            }
        }

        @Override
        public void operationComplete(Future<? extends Void> future) throws Exception {
            if (future.isSuccess()) {
                if (region.count() == region.transferred()) {
                    region.release();
                    promise.setSuccess(null);
                } else {
                    // Enqueue some more writes
                    IOUringSocketPipeline pipeline = (IOUringSocketPipeline) pipeline();
                    pipeline.writeTransportDirect(this, promise); // Do this to reuse the promise.
                    executor().execute(pipeline::flush); // Schedule a flush to get the continuation going ASAP.
                }
            } else {
                promise.tryFailure(new IOException("Write failures on channel", future.cause()));
            }
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            if (src.remaining() + writeIovs.size() < writeIovs.maxBytes() && writeIovs.count() + 1 < IOV_MAX) {
                Buffer buffer = bufferAllocator().copyOf(src);
                writeIovs.addReadable(buffer);
                writePromise.asFuture().addListener(buffer, CLOSE_BUFFER);
                position += buffer.readableBytes();
                return buffer.readableBytes();
            }
            return 0;
        }

        @Override
        public boolean isOpen() {
            return IOUringSocketChannel.this.isOpen();
        }

        @Override
        public void close() throws IOException {
            throw new UnsupportedOperationException();
        }
    }

    private static final class IOUringSocketPipeline extends DefaultAbstractChannelPipeline {
        IOUringSocketPipeline(AbstractChannel<?, ?, ?> channel) {
            super(channel);
        }

        void writeTransportDirect(Object msg, Promise<Void> promise) {
            writeTransport(msg, promise);
        }
    }
}
