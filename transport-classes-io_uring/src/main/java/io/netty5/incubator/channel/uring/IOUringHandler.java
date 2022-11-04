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

import io.netty5.channel.IoExecutionContext;
import io.netty5.channel.IoHandle;
import io.netty5.channel.IoHandler;
import io.netty5.channel.unix.FileDescriptor;
import io.netty5.util.collection.IntObjectHashMap;
import io.netty5.util.collection.IntObjectMap;
import io.netty5.util.internal.PlatformDependent;
import io.netty5.util.internal.logging.InternalLogger;
import io.netty5.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link IoHandler} which is implemented in terms of the Linux-specific {@code io_uring} API.
 */
class IOUringHandler implements IoHandler, CompletionCallback {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(IOUringHandler.class);

    private final RingBuffer ringBuffer;
    private final IntObjectMap<AbstractIOUringChannel<?>> channels;
    private final IntObjectMap<AbstractIOUringChannel<?>> touchedChannels;

    private final AtomicBoolean eventfdAsyncNotify = new AtomicBoolean();
    private final FileDescriptor eventfd;
    private final long eventfdReadBuf;
    private long eventfdReadSubmitted;

    private volatile boolean shuttingDown;
    private boolean closeCompleted;

    IOUringHandler(RingBuffer ringBuffer) {
        // Ensure that we load all native bits as otherwise it may fail when try to use native methods in IovArray
        IOUring.ensureAvailability();
        this.ringBuffer = ringBuffer;
        channels = new IntObjectHashMap<>();
        touchedChannels = new IntObjectHashMap<>();
        eventfd = Native.newBlockingEventFd();
        eventfdReadBuf = PlatformDependent.allocateMemory(8);
    }

    @Override
    public int run(IoExecutionContext context) {
        SubmissionQueue submissionQueue = ringBuffer.ioUringSubmissionQueue();
        CompletionQueue completionQueue = ringBuffer.ioUringCompletionQueue();
        if (!completionQueue.hasCompletions() && context.canBlock()) {
            if (eventfdReadSubmitted == 0) {
                submitEventFdRead();
            }
            if (context.deadlineNanos() != -1) {
                submitTimeout(context);
            }
            submissionQueue.submitAndWait();
        } else {
            submissionQueue.submit();
        }
        int completed = completionQueue.process(this);
        notifyIoFinished();
        return completed;
    }

    private void notifyIoFinished() {
        for (IntObjectMap.PrimitiveEntry<AbstractIOUringChannel<?>> entry : touchedChannels.entries()) {
            entry.value().ioLoopCompleted();
        }
        touchedChannels.clear();
    }

    @Override
    public void handle(int fd, int res, int flags, long udata) {
        if (fd == eventfd.intValue()) {
            handleEventFdRead();
            return;
        }
        byte op = UserData.decodeOp(udata);
        if (fd == ringBuffer.fd()) {
            switch (op) {
                case Native.IORING_OP_NOP:
                    completeRingClose();
                    break;
                case Native.IORING_OP_TIMEOUT:
                    // We don't care about the result of timeouts; they only server to wake us up to process scheduled tasks.
                    break;
            }
            return;
        }
        if (op == Native.IORING_OP_ASYNC_CANCEL) {
            // We don't care about the result of async cancels; they are best effort.
            return;
        }
        AbstractIOUringChannel<?> ch = channels.get(fd);
        if (ch == null) {
            logger.debug("ignoring {} completion for unknown channel (fd={}, res={})",
                    Native.opToStr(op), fd, res);
            return;
        }
        touchedChannels.put(fd, ch);
        switch (op) {
            case Native.IORING_OP_READ:
            case Native.IORING_OP_RECV:
            case Native.IORING_OP_ACCEPT:
            case Native.IORING_OP_RECVMSG:
                ch.readComplete(res, udata);
                break;
            case Native.IORING_OP_WRITE:
            case Native.IORING_OP_SEND:
            case Native.IORING_OP_WRITEV:
            case Native.IORING_OP_SENDMSG:
                ch.writeComplete(res, udata);
                break;
            case Native.IORING_OP_CONNECT:
                ch.connectComplete(res, udata);
                break;
            case Native.IORING_OP_POLL_ADD:
                if (UserData.decodeData(udata) == Native.POLLRDHUP) {
                    ch.completeRdHub(res);
                }
                break;
            case Native.IORING_OP_POLL_REMOVE:
                // Ignore poll_removes.
                break;
            case Native.IORING_OP_CLOSE:
                ch.closeComplete(res, udata);
                break;
            default:
                logger.warn("Unknown {} completion: fd={}, res={}, udata={}.", Native.opToStr(op), fd, res, udata);
        }
    }

    private void handleEventFdRead() {
        eventfdReadSubmitted = 0;
        eventfdAsyncNotify.set(false);
        if (!shuttingDown) {
            submitEventFdRead();
        }
    }

    private void submitEventFdRead() {
        SubmissionQueue submissionQueue = ringBuffer.ioUringSubmissionQueue();
        eventfdReadSubmitted = submissionQueue.addEventFdRead(eventfd.intValue(), eventfdReadBuf, 0, 8, (short) 0);
    }

    private void submitTimeout(IoExecutionContext context) {
        long delayNanos = context.delayNanos(System.nanoTime());
        ringBuffer.ioUringSubmissionQueue().addTimeout(ringBuffer.fd(), delayNanos, (short) 0);
    }

    @Override
    public void prepareToDestroy() {
        shuttingDown = true;
        CompletionQueue completionQueue = ringBuffer.ioUringCompletionQueue();
        SubmissionQueue submissionQueue = ringBuffer.ioUringSubmissionQueue();
        AbstractIOUringChannel<?>[] chs = channels.values().toArray(AbstractIOUringChannel[]::new);
        boolean first = true; // Ensure all previously submitted IOs get to complete before closing all fds.
        for (AbstractIOUringChannel<?> ch : chs) {
            ch.closeTransportNow(first);
            if (submissionQueue.count() > 0) {
                submissionQueue.submit();
            }
            if (completionQueue.hasCompletions()) {
                completionQueue.process(this);
            }
            first = false;
        }
    }

    @Override
    public void destroy() {
        SubmissionQueue submissionQueue = ringBuffer.ioUringSubmissionQueue();
        CompletionQueue completionQueue = ringBuffer.ioUringCompletionQueue();
        if (eventfdReadSubmitted != 0) {
            submissionQueue.addCancel(eventfd.intValue(), eventfdReadSubmitted);
            eventfdReadSubmitted = 0;
        }
        if (submissionQueue.remaining() < 2) {
            submissionQueue.submit();
        }
        // Try to drain all the IO from the queue first...
        submissionQueue.link(true);
        submissionQueue.addNop(ringBuffer.fd(), Native.IOSQE_IO_DRAIN, (short) 0);
        submissionQueue.link(false);
        // ... but only wait for 200 milliseconds on this
        submissionQueue.addLinkTimeout(ringBuffer.fd(), TimeUnit.MILLISECONDS.toNanos(200), (short) 0);
        submissionQueue.submitAndWait();
        completionQueue.process(this);
        if (!closeCompleted) {
            completeRingClose();
        }
    }

    private void completeRingClose() {
        closeCompleted = true;
        ringBuffer.close();
        try {
            eventfd.close();
        } catch (IOException e) {
            logger.warn("Failed to close eventfd", e);
        }
        PlatformDependent.freeMemory(eventfdReadBuf);
    }

    @Override
    public void register(IoHandle handle) throws Exception {
        AbstractIOUringChannel<?> ch = (AbstractIOUringChannel<?>) handle;
        if (shuttingDown) {
            throw new RejectedExecutionException("IoEventLoop is shutting down");
        }
        int fd = ch.fd().intValue();
        logger.debug("Adding channel: {} (fd={})", ch.id(), fd);
        ch.completeChannelRegister(ringBuffer.ioUringSubmissionQueue());
        if (channels.put(fd, ch) == null) {
            ringBuffer.ioUringSubmissionQueue().incrementHandledFds();
        }
    }

    @Override
    public void deregister(IoHandle handle) {
        AbstractIOUringChannel<?> ch = (AbstractIOUringChannel<?>) handle;
        int fd = ch.fd().intValue();
        logger.debug("Removing channel: {} (fd={})", ch.id(), fd);
        AbstractIOUringChannel<?> existing = channels.remove(fd);
        if (existing != null) {
            ringBuffer.ioUringSubmissionQueue().decrementHandledFds();
            if (existing != ch) {
                // The Channel mapping was already replaced due FD reuse, put back the stored Channel.
                channels.put(fd, existing);
                // If we found another Channel in the map that is mapped to the same FD the given Channel MUST be
                // closed.
                assert !ch.isOpen();
            }
        }
    }

    @Override
    public void wakeup(boolean inEventLoop) {
        if (!inEventLoop && !eventfdAsyncNotify.getAndSet(true)) {
            // write to the eventfd which will then trigger an eventfd read completion.
            Native.eventFdWrite(eventfd.intValue(), 1L);
        }
    }

    @Override
    public boolean isCompatible(Class<? extends IoHandle> handleType) {
        return AbstractIOUringChannel.class.isAssignableFrom(handleType);
    }
}
