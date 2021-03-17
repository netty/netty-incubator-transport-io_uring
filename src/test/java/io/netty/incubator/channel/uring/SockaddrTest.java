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

import io.netty.channel.unix.Buffer;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import static org.junit.Assume.assumeTrue;

public class SockaddrTest {

    @BeforeClass
    public static void loadJNI() {
        assumeTrue(IOUring.isAvailable());
    }

    @Test
    public void testIp4() throws Exception {
        ByteBuffer buffer = Buffer.allocateDirectWithNativeOrder(64);
        try {
            long memoryAddress = Buffer.memoryAddress(buffer);
            InetAddress address = InetAddress.getByAddress(new byte[] { 10, 10, 10, 10 });
            int port = 45678;
            Assert.assertEquals(Native.SIZEOF_SOCKADDR_IN, Sockaddr.writeIPv4(memoryAddress, address, port));
            byte[] bytes = new byte[4];
            InetSocketAddress sockAddr = Sockaddr.readIPv4(memoryAddress, bytes);
            Assert.assertArrayEquals(address.getAddress(), sockAddr.getAddress().getAddress());
            Assert.assertEquals(port, sockAddr.getPort());
        } finally {
            Buffer.free(buffer);
        }
    }

    @Test
    public void testIp6() throws Exception {
        ByteBuffer buffer = Buffer.allocateDirectWithNativeOrder(64);
        try {
            long memoryAddress = Buffer.memoryAddress(buffer);
            Inet6Address address = Inet6Address.getByAddress(
                    null, new byte[] { 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1 }, 12345);
            int port = 45678;
            Assert.assertEquals(Native.SIZEOF_SOCKADDR_IN6, Sockaddr.writeIPv6(memoryAddress, address, port));
            byte[] ipv6Bytes = new byte[16];
            byte[] ipv4Bytes = new byte[4];

            InetSocketAddress sockAddr = Sockaddr.readIPv6(memoryAddress, ipv6Bytes, ipv4Bytes);
            Inet6Address inet6Address = (Inet6Address) sockAddr.getAddress();
            Assert.assertArrayEquals(address.getAddress(), inet6Address.getAddress());
            Assert.assertEquals(address.getScopeId(), inet6Address.getScopeId());
            Assert.assertEquals(port, sockAddr.getPort());
        } finally {
            Buffer.free(buffer);
        }
    }

    @Test
    public void testWriteIp4ReadIpv6Mapped() throws Exception {
        ByteBuffer buffer = Buffer.allocateDirectWithNativeOrder(64);
        try {
            long memoryAddress = Buffer.memoryAddress(buffer);
            InetAddress address = InetAddress.getByAddress(new byte[] { 10, 10, 10, 10 });
            int port = 45678;
            Assert.assertEquals(Native.SIZEOF_SOCKADDR_IN6, Sockaddr.writeIPv6(memoryAddress, address, port));
            byte[] ipv6Bytes = new byte[16];
            byte[] ipv4Bytes = new byte[4];

            InetSocketAddress sockAddr = Sockaddr.readIPv6(memoryAddress, ipv6Bytes, ipv4Bytes);
            Inet4Address ipv4Address = (Inet4Address) sockAddr.getAddress();

            System.arraycopy(Sockaddr.IPV4_MAPPED_IPV6_PREFIX, 0, ipv6Bytes, 0,
                    Sockaddr.IPV4_MAPPED_IPV6_PREFIX.length);
            Assert.assertArrayEquals(ipv4Bytes, ipv4Address.getAddress());
            Assert.assertEquals(port, sockAddr.getPort());
        } finally {
            Buffer.free(buffer);
        }
    }
}
