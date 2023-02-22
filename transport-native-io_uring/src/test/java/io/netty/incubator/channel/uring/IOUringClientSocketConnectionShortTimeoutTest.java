/*
 * Copyright 2023 The Netty Project
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

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.ConnectTimeoutException;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.testsuite.transport.TestsuitePermutation;
import io.netty.testsuite.transport.socket.AbstractClientSocketTest;
import org.hamcrest.MatcherAssert;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsInstanceOf.instanceOf;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class IOUringClientSocketConnectionShortTimeoutTest extends AbstractClientSocketTest {

   @BeforeAll
   public static void loadJNI() {
      assumeTrue(IOUring.isAvailable());
   }

   @Test
   @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS)
   public void testConnectTimeoutAndClose(TestInfo testInfo) throws Throwable {
      run(testInfo, (bootstrap) -> {
         testFailedConnectWithSuperShortTimeout(bootstrap);
         try {
            bootstrap.config().group().shutdownGracefully().sync();
         } catch (Throwable t) {
            t.printStackTrace();
         }
      });
   }

   public void testFailedConnectWithSuperShortTimeout(Bootstrap cb) throws Throwable {
      cb.handler(new SimpleChannelInboundHandler<Object>() {
         @Override
         public void channelRead0(ChannelHandlerContext ctx, Object msgs) {
            // Nothing will be sent.
         }
      });
      cb.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1);
      ChannelFuture future = cb.connect("198.51.100.254", 65535);
      try {
         assertThat(future.await(Integer.MAX_VALUE), is(true));
      } finally {
         Assert.assertNotNull(future.cause());
         MatcherAssert.assertThat(future.cause(), instanceOf(ConnectTimeoutException.class));
      }
   }

   @Override
   protected List<TestsuitePermutation.BootstrapFactory<Bootstrap>> newFactories() {
      return Arrays.asList(() -> new Bootstrap().group(new IOUringEventLoopGroup(1)).channel(IOUringSocketChannel.class));
   }
}