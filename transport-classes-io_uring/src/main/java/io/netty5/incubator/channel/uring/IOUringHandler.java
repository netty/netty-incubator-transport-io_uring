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

import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty5.channel.Channel;
import io.netty5.channel.DefaultSelectStrategyFactory;
import io.netty5.channel.EventLoop;
import io.netty5.channel.IoExecutionContext;
import io.netty5.channel.IoHandler;
import io.netty5.channel.IoHandlerFactory;
import io.netty5.channel.SelectStrategy;
import io.netty5.channel.SingleThreadEventLoop;
import io.netty5.channel.unix.Errors;
import io.netty5.channel.unix.FileDescriptor;
import io.netty5.util.IntSupplier;
import io.netty5.util.internal.PlatformDependent;
import io.netty5.util.internal.StringUtil;
import io.netty5.util.internal.logging.InternalLogger;
import io.netty5.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An {@link EventLoop} that uses IO_URING.
 */
public final class IOUringHandler implements IoHandler {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(IOUringHandler.class);

    private final long eventfdReadBuf = PlatformDependent.allocateMemory(8);

    private final IntObjectMap<AbstractIOUringChannel> channels = new IntObjectHashMap<>(4096);
    private final RingBuffer ringBuffer;

    private static final long AWAKE = -1L;
    private static final long NONE = Long.MAX_VALUE;

    // nextWakeupNanos is:
    //    AWAKE            when EL is awake
    //    NONE             when EL is waiting with no wakeup scheduled
    //    other value T    when EL is waiting with wakeup scheduled at time T
    private final AtomicLong nextWakeupNanos = new AtomicLong(AWAKE);
    private final FileDescriptor eventfd;
    private final SelectStrategy selectStrategy = DefaultSelectStrategyFactory.INSTANCE.newSelectStrategy();
    private final IntSupplier selectNowSupplier = this::selectNow;

    // The maximum number of bytes for an InetAddress / Inet6Address
    private final byte[] inet4AddressArray = new byte[SockaddrIn.IPV4_ADDRESS_LENGTH];
    private final byte[] inet6AddressArray = new byte[SockaddrIn.IPV6_ADDRESS_LENGTH];

    private final IOUringCompletionQueueCallback callback = IOUringHandler.this::handle;
    private final Runnable submitIOTask = () -> getRingBuffer().ioUringSubmissionQueue().submit();

    private long prevDeadlineNanos =  SingleThreadEventLoop.nanoTime() - 1;
    private boolean pendingWakeup;

    private static AbstractIOUringChannel cast(Channel channel) {
        if (channel instanceof AbstractIOUringChannel) {
            return (AbstractIOUringChannel) channel;
        }
        throw new IllegalArgumentException("Channel of type " + StringUtil.simpleClassName(channel) + " not supported");
    }

    private IOUringHandler() {
        this(Native.DEFAULT_RING_SIZE, Native.DEFAULT_IOSEQ_ASYNC_THRESHOLD);
    }

    IOUringHandler(int ringSize, int iosqeAsyncThreshold) {
        // Ensure that we load all native bits as otherwise it may fail when try to use native methods in IovArray
        IOUring.ensureAvailability();

        ringBuffer = Native.createRingBuffer(ringSize, iosqeAsyncThreshold);
        eventfd = Native.newBlockingEventFd();
        logger.trace("New EventLoop: {}", this.toString());
    }

    public static IoHandlerFactory newFactory() {
        return IOUringHandler::new;
    }

    public static IoHandlerFactory newFactory(int ringSize, int iosqeAsyncThreshold) {
        return () -> new IOUringHandler(ringSize, iosqeAsyncThreshold);
    }

    /**
     * Submit the IO so the kernel can process it. This method can be called to "force" the submission (before it
     * is submitted by netty itself).
     *//*

    public void submitIO() {
        if (inEventLoop()) {
            getRingBuffer().ioUringSubmissionQueue().submit();
        } else {
            execute(submitIOTask);
        }
    }
*/
    @Override
    public void register(Channel channel) throws Exception {
        final AbstractIOUringChannel ioUringChannel = cast(channel);
        ioUringChannel.register0(new IOUringRegistration() {
            @Override
            public IOUringHandler ioUringHandler() {
                return IOUringHandler.this;
            }

            @Override
            public void add() {
                IOUringHandler.this.add(ioUringChannel);
            }

            @Override
            public void remove() {
                IOUringHandler.this.remove(ioUringChannel);
            }
        });
        add(ioUringChannel);
    }

    void add(AbstractIOUringChannel ch) {
        if (ch.executor().isShuttingDown()) {
            throw new RejectedExecutionException("IoEventLoop is shutting down");
        }
        logger.trace("Add Channel: {} ", ch.socket.intValue());
        int fd = ch.socket.intValue();

        if (channels.put(fd, ch) == null) {
            ringBuffer.ioUringSubmissionQueue().incrementHandledFds();
        }
    }

    @Override
    public void deregister(Channel channel) throws Exception {
        AbstractIOUringChannel ioUringChannel = cast(channel);
        ioUringChannel.deregister0();
        remove(ioUringChannel);
    }

    void remove(AbstractIOUringChannel ch) {
        logger.trace("Remove Channel: {}", ch.socket.intValue());
        int fd = ch.socket.intValue();

        AbstractIOUringChannel old = channels.remove(fd);
        if (old != null) {
            ringBuffer.ioUringSubmissionQueue().decrementHandledFds();

            if (old != ch) {
                // The Channel mapping was already replaced due FD reuse, put back the stored Channel.
                channels.put(fd, old);

                // If we found another Channel in the map that is mapped to the same FD the given Channel MUST be
                // closed.
                assert !ch.isOpen();
            }
        }
    }

    @Override
    public void prepareToDestroy() {
        IOUringCompletionQueue completionQueue = ringBuffer.ioUringCompletionQueue();
        IOUringSubmissionQueue submissionQueue = ringBuffer.ioUringSubmissionQueue();
        if (pendingWakeup) {
            // Another thread is in the process of writing to the eventFd. We must wait to
            // receive the corresponding CQE before closing it or else the fd int may be
            // reassigned by the kernel in the meantime.
            IOUringCompletionQueueCallback callback = new IOUringCompletionQueueCallback() {
                @Override
                public void handle(int fd, int res, int flags, byte op, short data) {
                    if (op == Native.IORING_OP_READ && eventfd.intValue() == fd) {
                        pendingWakeup = false;
                    } else {
                        // Delegate to the original handle(...) method so we not miss some completions.
                        IOUringHandler.this.handle(fd, res, flags, op, data);
                    }
                }
            };
            completionQueue.process(callback);
            while (pendingWakeup) {
                completionQueue.ioUringWaitCqe();
                completionQueue.process(callback);
            }
        }

        // Call closeAll() one last time
        closeAll();

        // If we still have channels in the map we need to continue process tasks as we may have something in the
        // task queue that needs to be executed to make the deregistration complete.

        while (!channels.isEmpty()) {
            submissionQueue.submitAndWait();
            completionQueue.process(callback);
        }
        try {
            eventfd.close();
        } catch (IOException e) {
            logger.warn("Failed to close the event fd.", e);
        }
        PlatformDependent.freeMemory(eventfdReadBuf);
        ringBuffer.close();
    }

    @Override
    public void destroy() {
        closeAll();
    }

    private void closeAll() {
        logger.trace("CloseAll IOUringHandlers");
        // Using the intermediate collection to prevent ConcurrentModificationException.
        // In the `close()` method, the channel is deleted from `channels` map.
        AbstractIOUringChannel[] localChannels = channels.values().toArray(new AbstractIOUringChannel[0]);

        for (AbstractIOUringChannel ch : localChannels) {
            ch.unsafe().close(ch.newPromise());
        }
    }

    private int selectNow() throws IOException {
        return 0; // TODO: 05-06-2022 FIX ME
    }

    @Override
    public int run(IoExecutionContext context) {
        final IOUringCompletionQueue completionQueue = ringBuffer.ioUringCompletionQueue();
        final IOUringSubmissionQueue submissionQueue = ringBuffer.ioUringSubmissionQueue();

        // Lets add the eventfd related events before starting to do any real work.
        addEventFdRead(submissionQueue);

        int handled = 0;
        try {
            int strategy = selectStrategy.calculateStrategy(selectNowSupplier, !context.canBlock());
            switch (strategy) {
                case SelectStrategy.CONTINUE:
                    return 0;

                case SelectStrategy.BUSY_WAIT:
                    // Prepare to block wait
                    long curDeadlineNanos = context.deadlineNanos();
                    if (curDeadlineNanos == -1L) {
                        curDeadlineNanos = NONE; // nothing on the calendar
                    }
                    nextWakeupNanos.set(curDeadlineNanos);

                    if (curDeadlineNanos != prevDeadlineNanos) {
                        prevDeadlineNanos = curDeadlineNanos;
                        submissionQueue.addTimeout(context.delayNanos(curDeadlineNanos), (short) 0);
                    }

                    break;
                case SelectStrategy.SELECT:
                    // Check there were any completion events to process
                    if (!completionQueue.hasCompletions()) {
                        // Block if there is nothing to process after this try again to call process(....)
                        logger.trace("submitAndWait {}", this);
                        submissionQueue.submitAndWait();
                    }

                    if (nextWakeupNanos.get() == AWAKE || nextWakeupNanos.getAndSet(AWAKE) == AWAKE) {
                        pendingWakeup = true;
                    }
                    break;
                default:
            }
            if (strategy > 0) {
                handled = strategy;
                // Avoid blocking for as long as possible - loop until available work exhausted
                boolean maybeMoreWork = true;
                do {
                    try {
                        // CQE processing can produce tasks, and new CQEs could arrive while
                        // processing tasks. So run both on every iteration and break when
                        // they both report that nothing was done (| means always run both).
                        maybeMoreWork = completionQueue.process(callback) != 0;
                    } catch (Throwable t) {
                        handleLoopException(t);
                    }
                } while (maybeMoreWork);
            }
        } catch (Error error) {
            throw error;
        } catch (Throwable t) {
            handleLoopException(t);
        }
        return handled;
    }

    /**
     * Visible only for testing!
     */
    void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the io_uring event loop", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }

    private void handle(int fd, int res, int flags, byte op, short data) {
        if (op == Native.IORING_OP_READ && eventfd.intValue() == fd) {
            pendingWakeup = false;
            addEventFdRead(ringBuffer.ioUringSubmissionQueue());
        } else if (op == Native.IORING_OP_TIMEOUT) {
            if (res == Native.ERRNO_ETIME_NEGATIVE) {
                prevDeadlineNanos = NONE;
            }
        } else {
            // Remaining events should be channel-specific
            final AbstractIOUringChannel channel = channels.get(fd);
            if (channel == null) {
                return;
            }
            if (op == Native.IORING_OP_READ || op == Native.IORING_OP_ACCEPT || op == Native.IORING_OP_RECVMSG) {
                handleRead(channel, res, data);
            } else if (op == Native.IORING_OP_WRITEV ||
                    op == Native.IORING_OP_WRITE || op == Native.IORING_OP_SENDMSG) {
                handleWrite(channel, res, data);
            } else if (op == Native.IORING_OP_POLL_ADD) {
                handlePollAdd(channel, res, data);
            } else if (op == Native.IORING_OP_POLL_REMOVE) {
                if (res == Errors.ERRNO_ENOENT_NEGATIVE) {
                    logger.trace("IORING_POLL_REMOVE not successful");
                } else if (res == 0) {
                    logger.trace("IORING_POLL_REMOVE successful");
                }
                channel.clearPollFlag(data);
                if (!channel.ioScheduled()) {
                    // We cancelled the POLL ops which means we are done and should remove the mapping.
                    remove(channel);
                    return;
                }
            } else if (op == Native.IORING_OP_CONNECT) {
                handleConnect(channel, res);
            }
            channel.ioUringUnsafe().processDelayedClose();
        }
    }

    private void handleRead(AbstractIOUringChannel channel, int res, int data) {
        channel.ioUringUnsafe().readComplete(res, data);
    }

    private void handleWrite(AbstractIOUringChannel channel, int res, int data) {
        channel.ioUringUnsafe().writeComplete(res, data);
    }

    private void handlePollAdd(AbstractIOUringChannel channel, int res, int pollMask) {
        if ((pollMask & Native.POLLOUT) != 0) {
            channel.ioUringUnsafe().pollOut(res);
        }
        if ((pollMask & Native.POLLIN) != 0) {
            channel.ioUringUnsafe().pollIn(res);
        }
        if ((pollMask & Native.POLLRDHUP) != 0) {
            channel.ioUringUnsafe().pollRdHup(res);
        }
    }

    private void addEventFdRead(IOUringSubmissionQueue submissionQueue) {
        submissionQueue.addEventFdRead(eventfd.intValue(), eventfdReadBuf, 0, 8, (short) 0);
    }

    private void handleConnect(AbstractIOUringChannel channel, int res) {
        channel.ioUringUnsafe().connectComplete(res);
    }

    RingBuffer getRingBuffer() {
        return ringBuffer;
    }

    @Override
    public void wakeup(boolean inEventLoop) {
        if (!inEventLoop && nextWakeupNanos.getAndSet(AWAKE) != AWAKE) {
            // write to the evfd which will then wake-up epoll_wait(...)
            Native.eventFdWrite(eventfd.intValue(), 1L);
        }
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
}
