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

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.testsuite.transport.AbstractSingleThreadEventLoopTest;
import io.netty.util.concurrent.Future;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class IOUringEventLoopTest extends AbstractSingleThreadEventLoopTest {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(IOUringEventLoopTest.class);

    ThreadFactory threadFactory;

    @BeforeEach
    public void setup() {
        threadFactory = new TFactory();
    }

    @BeforeAll
    public static void loadJNI() {
        assumeTrue(IOUring.isAvailable());
    }

    @Override
    protected EventLoopGroup newEventLoopGroup() {
        return new IOUringEventLoopGroup(1, threadFactory);
    }

    @Override
    protected Channel newChannel() {
        return new IOUringSocketChannel();
    }

    @Override
    protected Class<? extends ServerChannel> serverChannelClass() {
        return IOUringServerSocketChannel.class;
    }

//    @Test
    public void shutdownGracefullyZeroQuietBeforeStart() throws Exception {
        EventLoopGroup group = newEventLoopGroup();
        assertTrue(group.shutdownGracefully(0L, 2L, TimeUnit.SECONDS).await(1500L));
    }

    @RepeatedTest(1)
    public void shutdownNonGraceful() throws Exception {
        EventLoopGroup group = newEventLoopGroup();
        IOUringEventLoop loop = (IOUringEventLoop) group.next();
        logger.info("Starting test");

        loop.submit(() -> {}); // noop task to start thread.

        // Wait for the thread to start.
        loop.latch1.await();
        logger.info("Continuing.");
        // submit the task so we `hasTasks() == true` and we skip the submitAndWait() call.
        group.submit(() -> {}); // noop task, just to hit the WAKUP state.

        // Also shutdown so we skip the rest of the loop entirely.
        Future<?> shutdownFuture = loop.shutdownGracefully(0L, 0L, TimeUnit.NANOSECONDS);
        // let the event loop proceed now.
        loop.latch2.countDown();


        if (!shutdownFuture.await(2000L)) {
            dumpThreads();
        }
        // Fails with
        // [ERROR] Tests run: 1010, Failures: 0, Errors: 1, Skipped: 3, Time elapsed: 4.527 s <<< FAILURE! - in io.netty.incubator.channel.uring.IOUringEventLoopTest
        // [ERROR] shutdownNonGraceful[301]  Time elapsed: 2.009 s  <<< ERROR!
        // java.lang.Exception:
        // What IO threads doing? Stack traces:
        // Thread io_uring-0:
        //		io.netty.incubator.channel.uring.Native.ioUringEnter(Native Method)
        //		io.netty.incubator.channel.uring.IOUringCompletionQueue.ioUringWaitCqe(IOUringCompletionQueue.java:99)
        //		io.netty.incubator.channel.uring.IOUringEventLoop.cleanup(IOUringEventLoop.java:338)
        //		io.netty.util.concurrent.SingleThreadEventExecutor$4.run(SingleThreadEventExecutor.java:1044)
        //		io.netty.util.internal.ThreadExecutorMap$2.run(ThreadExecutorMap.java:74)
        //		java.lang.Thread.run(Thread.java:750)
        //
        //	at io.netty.incubator.channel.uring.IOUringEventLoopTest.dumpThreads(IOUringEventLoopTest.java:162)
        //	at io.netty.incubator.channel.uring.IOUringEventLoopTest.shutdownNonGraceful(IOUringEventLoopTest.java:80)
        //	at sun.reflect.GeneratedMethodAccessor2.invoke(Unknown Source)
        //	at sun.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)
        //	at java.lang.reflect.Method.invoke(Method.java:498)
        //  ...
    }

//    @Test
    public void testSubmitMultipleTasksAndEnsureTheseAreExecuted() throws Exception {
        IOUringEventLoopGroup group = new IOUringEventLoopGroup(1);
        try {
            EventLoop loop = group.next();
            loop.submit(new Runnable() {
                @Override
                public void run() {
                }
            }).sync();

            loop.submit(new Runnable() {
                @Override
                public void run() {
                }
            }).sync();
            loop.submit(new Runnable() {
                @Override
                public void run() {
                }
            }).sync();
            loop.submit(new Runnable() {
                @Override
                public void run() {
                }
            }).sync();
        } finally {
            group.shutdownGracefully();
        }
    }

//    @Test
    public void testSchedule() throws Exception {
        IOUringEventLoopGroup group = new IOUringEventLoopGroup(1);
        try {
            EventLoop loop = group.next();
            loop.schedule(new Runnable() {
                @Override
                public void run() { }
            }, 1, TimeUnit.SECONDS).sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    private static void dumpThreads() throws Exception {
        final StringBuilder sb = new StringBuilder();
        sb.append("What IO threads doing? Stack traces:\n");
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            if (!entry.getKey().getName().startsWith("io_uring-")) {
                continue;
            }

            sb.append("Thread: ").append(entry.getKey().getName()).append(":\n");
            for (StackTraceElement e : entry.getValue()) {
                sb.append("\t\t").append(e).append('\n');
            }
        }

        throw new Exception(sb.toString());
    }
    private static final AtomicLong threadId = new AtomicLong();

    private static class TFactory implements ThreadFactory {


        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "io_uring-" + threadId.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
