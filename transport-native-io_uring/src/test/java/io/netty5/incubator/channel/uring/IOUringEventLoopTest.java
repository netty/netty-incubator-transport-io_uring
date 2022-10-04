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
import io.netty5.channel.ServerChannel;
import io.netty5.testsuite.transport.AbstractSingleThreadEventLoopTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class IOUringEventLoopTest extends AbstractSingleThreadEventLoopTest {

    @BeforeAll
    public static void loadJNI() {
        assumeTrue(IOUring.isAvailable());
    }

    @Override
    protected EventLoopGroup newEventLoopGroup() {
        return new IOUringEventLoopGroup(1);
    }

    @Override
    protected Channel newChannel() {
        return new IOUringSocketChannel();
    }

    @Override
    protected Class<? extends ServerChannel> serverChannelClass() {
        return IOUringServerSocketChannel.class;
    }

    @Test
    public void shutdownGracefullyZeroQuietBeforeStart() throws Exception {
        EventLoopGroup group = newEventLoopGroup();
        assertTrue(group.shutdownGracefully(0L, 2L, TimeUnit.SECONDS).await(1500L));
    }

    @Test
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

    @Test
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
}
