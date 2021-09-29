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
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.unix.tests.DetectPeerCloseWithoutReadTest;
import org.junit.jupiter.api.BeforeAll;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class IOUringDetectPeerCloseWithReadTest extends DetectPeerCloseWithoutReadTest {

    @BeforeAll
    public static void loadJNI() {
        assumeTrue(IOUring.isAvailable());
    }

    @Override
    protected EventLoopGroup newGroup() {
        return new IOUringEventLoopGroup(2);
    }

    @Override
    protected Class<? extends ServerChannel> serverChannel() {
        return IOUringServerSocketChannel.class;
    }

    @Override
    protected Class<? extends Channel> clientChannel() {
        return IOUringSocketChannel.class;
    }
}
