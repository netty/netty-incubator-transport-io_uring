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
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.internal.PlatformDependent;

import java.net.InetSocketAddress;

final class MsgHdrMemory {
    private final long memory;
    private final int idx;
    private final long cmsgDataAddr;

    MsgHdrMemory(int idx) {
        this.idx = idx;
        int size = Native.SIZEOF_MSGHDR + Native.SIZEOF_SOCKADDR_STORAGE + Native.SIZEOF_IOVEC + Native.CMSG_SPACE;
        memory = PlatformDependent.allocateMemory(size);
        PlatformDependent.setMemory(memory, size, (byte) 0);

        // We retrieve the address of the data once so we can just be JNI free after construction.
        cmsgDataAddr = Native.cmsghdrData(memory + Native.SIZEOF_MSGHDR +
                Native.SIZEOF_SOCKADDR_STORAGE + Native.SIZEOF_IOVEC);
    }

    void write(LinuxSocket socket, InetSocketAddress address, long bufferAddress , int length, short segmentSize) {
        long sockAddress = memory + Native.SIZEOF_MSGHDR;
        long iovAddress = sockAddress + Native.SIZEOF_SOCKADDR_STORAGE;
        long cmsgAddr = iovAddress + Native.SIZEOF_IOVEC;
        int addressLength;
        if (address == null) {
            addressLength = socket.isIpv6() ? Native.SIZEOF_SOCKADDR_IN6 : Native.SIZEOF_SOCKADDR_IN;
            PlatformDependent.setMemory(sockAddress, Native.SIZEOF_SOCKADDR_STORAGE, (byte) 0);
        } else {
            addressLength = SockaddrIn.write(socket.isIpv6(), sockAddress, address);
        }
        Iov.write(iovAddress, bufferAddress, length);
        MsgHdr.write(memory, sockAddress, addressLength, iovAddress, 1, cmsgAddr, cmsgDataAddr, segmentSize);
    }

    DatagramPacket read(IOUringDatagramChannel channel, ByteBuf buffer, int bytesRead) {
        long sockAddress = memory + Native.SIZEOF_MSGHDR;
        IOUringEventLoop eventLoop = (IOUringEventLoop) channel.eventLoop();
        InetSocketAddress sender;
        if (channel.socket.isIpv6()) {
            byte[] ipv6Bytes = eventLoop.inet6AddressArray();
            byte[] ipv4bytes = eventLoop.inet4AddressArray();

            sender = SockaddrIn.readIPv6(sockAddress, ipv6Bytes, ipv4bytes);
        } else {
            byte[] bytes = eventLoop.inet4AddressArray();
            sender = SockaddrIn.readIPv4(sockAddress, bytes);
        }
        long iovAddress = memory + Native.SIZEOF_MSGHDR + Native.SIZEOF_SOCKADDR_STORAGE;
        long bufferAddress = Iov.readBufferAddress(iovAddress);
        int bufferLength = Iov.readBufferLength(iovAddress);
        // reconstruct the reader index based on the memoryAddress of the buffer and the bufferAddress that was used
        // in the iovec.
        int readerIndex = (int) (bufferAddress - buffer.memoryAddress());

        ByteBuf slice = buffer.slice(readerIndex, bufferLength)
                .writerIndex(bytesRead);
        return new DatagramPacket(slice.retain(), channel.localAddress(), sender);
    }

    int idx() {
        return idx;
    }

    long address() {
        return memory;
    }

    void release() {
        PlatformDependent.freeMemory(memory);
    }
}
