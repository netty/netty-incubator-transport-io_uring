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

import io.netty5.buffer.api.Buffer;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelOutboundBuffer;
import io.netty5.channel.ChannelPipeline;
import io.netty5.channel.EventLoop;
import io.netty5.channel.socket.DuplexChannel;
import io.netty5.channel.unix.IovArray;
import io.netty5.channel.unix.Limits;
import io.netty5.util.concurrent.Future;
import io.netty5.util.concurrent.Promise;
import io.netty5.util.internal.UnstableApi;
import io.netty5.util.internal.logging.InternalLogger;
import io.netty5.util.internal.logging.InternalLoggerFactory;

import java.net.SocketAddress;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

import static io.netty5.channel.unix.Errors.ioResult;
import static io.netty5.incubator.channel.uring.AbstractIOUringServerChannel.isSoErrorZero;

abstract class AbstractIOUringStreamChannel extends AbstractIOUringChannel implements DuplexChannel {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractIOUringStreamChannel.class);

    protected AbstractIOUringStreamChannel(Channel parent, EventLoop eventLoop, int fd) {
        this(parent, eventLoop, new LinuxSocket(fd));
    }

    protected AbstractIOUringStreamChannel(EventLoop eventLoop, int fd) {
        this(eventLoop, new LinuxSocket(fd));
    }

    AbstractIOUringStreamChannel(EventLoop eventLoop, LinuxSocket fd) {
        this(eventLoop, fd, isSoErrorZero(fd));
    }

    AbstractIOUringStreamChannel(Channel parent, EventLoop eventLoop, LinuxSocket fd) {
        super(parent, eventLoop, fd, true);
    }

    AbstractIOUringStreamChannel(Channel parent, EventLoop eventLoop, LinuxSocket fd, SocketAddress remote) {
        super(parent, eventLoop, fd, remote);
    }

    protected AbstractIOUringStreamChannel(EventLoop eventLoop, LinuxSocket fd, boolean active) {
        super(null, eventLoop, fd, active);
    }

    @Override
    protected AbstractUringUnsafe newUnsafe() {
        return new IOUringStreamUnsafe();
    }

    @UnstableApi
    @Override
    protected final void doShutdownOutput() throws Exception {
        socket.shutdown(false, true);
    }

    private void shutdownInput0(Promise<Void> promise) {
        try {
            socket.shutdown(true, false);
            promise.setSuccess(null);
        } catch (Throwable cause) {
            promise.setFailure(cause);
        }
    }

    @Override
    public boolean isOutputShutdown() {
        return socket.isOutputShutdown();
    }

    @Override
    public boolean isInputShutdown() {
        return socket.isInputShutdown();
    }

    @Override
    public boolean isShutdown() {
        return socket.isShutdown();
    }

    @Override
    public Future<Void> shutdownOutput() {
        return shutdownOutput(newPromise());
    }

    @Override
    public Future<Void> shutdownOutput(final Promise<Void> promise) {
        EventLoop loop = executor();
        if (loop.inEventLoop()) {
            ((AbstractUnsafe) unsafe()).shutdownOutput(promise);
        } else {
            loop.execute(() -> ((AbstractUnsafe) unsafe()).shutdownOutput(promise));
        }

        return promise.asFuture();
    }

    @Override
    public Future<Void> shutdownInput() {
        return shutdownInput(newPromise());
    }

    @Override
    public Future<Void> shutdownInput(final Promise<Void> promise) {
        Executor closeExecutor = ((IOUringStreamUnsafe) unsafe()).prepareToClose();
        if (closeExecutor != null) {
            closeExecutor.execute(() -> shutdownInput0(promise));
        } else {
            EventLoop loop = executor();
            if (loop.inEventLoop()) {
                shutdownInput0(promise);
            } else {
                loop.execute(() -> shutdownInput0(promise));
            }
        }
        return promise.asFuture();
    }

    @Override
    public Future<Void> shutdown() {
        return shutdown(newPromise());
    }

    @Override
    public Future<Void> shutdown(Promise<Void> promise) {
        Future<Void> shutdownOutputFuture = shutdownOutput();
        if (shutdownOutputFuture.isDone()) {
            shutdownOutputDone(promise, shutdownOutputFuture);
        } else {
            shutdownOutputFuture.addListener(promise, this::shutdownOutputDone);
        }
        return promise.asFuture();
    }

    private void shutdownOutputDone(Promise<Void> promise, Future<?> shutdownOutputFuture) {
        Future<Void> shutdownInputFuture = shutdownInput();
        if (shutdownInputFuture.isDone()) {
            shutdownDone(shutdownOutputFuture, shutdownInputFuture, promise);
        } else {
            shutdownInputFuture.addListener(shutdownInputFuture1 ->
                    shutdownDone(shutdownOutputFuture, shutdownInputFuture1, promise));
        }
    }

    private static void shutdownDone(Future<?> shutdownOutputFuture,
                                     Future<?> shutdownInputFuture,
                                     Promise<Void> promise) {
        Throwable shutdownOutputCause = shutdownOutputFuture.cause();
        Throwable shutdownInputCause = shutdownInputFuture.cause();
        if (shutdownOutputCause != null) {
            if (shutdownInputCause != null) {
                logger.debug("Exception suppressed because a previous exception occurred.",
                        shutdownInputCause);
            }
            promise.setFailure(shutdownOutputCause);
        } else if (shutdownInputCause != null) {
            promise.setFailure(shutdownInputCause);
        } else {
            promise.setSuccess(null);
        }
    }

    @Override
    protected void doRegister() throws Exception {
        super.doRegister();
        if (active) {
            // Register for POLLRDHUP if this channel is already considered active.
            schedulePollRdHup();
        }
    }

    private final class IOUringStreamUnsafe extends AbstractUringUnsafe {

        // Overridden here just to be able to access this method from AbstractEpollStreamChannel
        @Override
        protected Executor prepareToClose() {
            return super.prepareToClose();
        }

        private Buffer readBuffer;
        private IovArray iovArray;

        @Override
        protected int scheduleWriteMultiple(ChannelOutboundBuffer in) {
            assert iovArray == null;
            int numElements = Math.min(in.size(), Limits.IOV_MAX);
            iovArray = new IovArray(ByteBuffer.allocateDirect(numElements * IovArray.IOV_SIZE));
            try {
                int offset = iovArray.count();
                in.forEachFlushedMessage(iovArray);
                submissionQueue().addWritev(socket.intValue(),
                        iovArray.memoryAddress(offset), iovArray.count() - offset, (short) 0);
            } catch (Exception e) {
                iovArray.release();
                iovArray = null;

                // This should never happen, anyway fallback to single write.
                scheduleWriteSingle(in.current());
            }
            return 1;
        }

        @Override
        protected int scheduleWriteSingle(Object msg) {
            assert iovArray == null;
            Buffer buf = (Buffer) msg;
            IOUringSubmissionQueue submissionQueue = submissionQueue();
            submissionQueue.addWrite(socket.intValue(), buf.forEachReadable().first().readableNativeAddress(),
                    buf.readerOffset(), buf.writerOffset(), (short) 0);
            return 1;
        }

        @Override
        protected int scheduleRead0() {
            assert readBuffer == null;

            final IOUringRecvByteAllocatorHandle allocHandle = recvBufAllocHandle();
            Buffer buffer = allocHandle.allocate(bufferAllocator());
            IOUringSubmissionQueue submissionQueue = submissionQueue();
            allocHandle.attemptedBytesRead(buffer.writableBytes());

            readBuffer = buffer;

            submissionQueue.addRead(socket.intValue(), buffer.forEachReadable().first().readableNativeAddress(),
                    buffer.writerOffset(), buffer.capacity(), (short) 0);
            return 1;
        }

        @Override
        protected void readComplete0(int res, int data, int outstanding) {
            boolean close = false;

            final IOUringRecvByteAllocatorHandle allocHandle = recvBufAllocHandle();
            final ChannelPipeline pipeline = pipeline();
            Buffer buffer = this.readBuffer;
            this.readBuffer = null;
            assert buffer != null;

            try {
                if (res < 0) {
                    // If res is negative we should pass it to ioResult(...) which will either throw
                    // or convert it to 0 if we could not read because the socket was not readable.
                    allocHandle.lastBytesRead(ioResult("io_uring read", res));
                } else if (res > 0) {
                    buffer.writerOffset(buffer.writerOffset() + res);
                    allocHandle.lastBytesRead(res);
                } else {
                    // EOF which we signal with -1.
                    allocHandle.lastBytesRead(-1);
                }
                if (allocHandle.lastBytesRead() <= 0) {
                    // nothing was read, release the buffer.
                    buffer.close();
                    buffer = null;
                    close = allocHandle.lastBytesRead() < 0;
                    if (close) {
                        // There is nothing left to read as we received an EOF.
                        shutdownInput(false);
                    }
                    allocHandle.readComplete();
                    pipeline.fireChannelReadComplete();
                    return;
                }

                allocHandle.incMessagesRead(1);
                pipeline.fireChannelRead(buffer);
                buffer = null;
                if (allocHandle.continueReading()) {
                    // Let's schedule another read.
                    scheduleRead();
                } else {
                    // We did not fill the whole ByteBuf so we should break the "read loop" and try again later.
                    allocHandle.readComplete();
                    pipeline.fireChannelReadComplete();
                }
            } catch (Throwable t) {
                handleReadException(pipeline, buffer, t, close, allocHandle);
            }
        }

        private void handleReadException(ChannelPipeline pipeline, Buffer buffer,
                                         Throwable cause, boolean close,
                                         IOUringRecvByteAllocatorHandle allocHandle) {
            if (buffer != null) {
                if (buffer.readableBytes() > 0) {
                    pipeline.fireChannelRead(buffer);
                } else {
                    buffer.close();
                }
            }
            allocHandle.readComplete();
            pipeline.fireChannelReadComplete();
            pipeline.fireExceptionCaught(cause);
            if (close || cause instanceof IOException) {
                shutdownInput(false);
            }
        }

        @Override
        boolean writeComplete0(int res, int data, int outstanding) {
            IovArray iovArray = this.iovArray;
            if (iovArray != null) {
                this.iovArray = null;
                iovArray.release();
            }
            if (res >= 0) {
                unsafe().outboundBuffer().removeBytes(res);
            } else {
                try {
                    if (ioResult("io_uring write", res) == 0) {
                        return false;
                    }
                } catch (Throwable cause) {
                    handleWriteError(cause);
                }
            }
            return true;
        }
    }
}
