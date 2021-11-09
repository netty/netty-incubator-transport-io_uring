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

import io.netty.util.internal.PlatformDependent;

/**
 * struct msghdr {
 *     void         *msg_name;       // optional address
 *     socklen_t    msg_namelen;     // size of address
 *     struct       iovec*msg_iov;   // scatter/gather array
 *     size_t       msg_iovlen;      // # elements in msg_iov
 *     void*        msg_control;     // ancillary data, see below
 *     size_t       msg_controllen;  // ancillary data buffer len
 *     int          msg_flags;       // flags on received message
 * };
 */
final class MsgHdr {

    private MsgHdr() { }

    static void write(long memoryAddress, long address, int addressSize,  long iovAddress, int iovLength,
                      long msgControlAddr, long cmsgHdrDataAddress, short segmentSize) {
        PlatformDependent.putInt(memoryAddress + Native.MSGHDR_OFFSETOF_MSG_NAMELEN, addressSize);

        int msgControlLen = 0;
        if (segmentSize > 0) {
            msgControlLen = Native.CMSG_LEN;
            CmsgHdr.write(msgControlAddr, cmsgHdrDataAddress, Native.CMSG_LEN, Native.SOL_UDP,
                    Native.UDP_SEGMENT, segmentSize);
        } else {
            // Set to 0 if we not explicit requested GSO.
            msgControlAddr = 0;
        }
        if (Native.SIZEOF_SIZE_T == 4) {
            PlatformDependent.putInt(memoryAddress + Native.MSGHDR_OFFSETOF_MSG_NAME, (int) address);
            PlatformDependent.putInt(memoryAddress + Native.MSGHDR_OFFSETOF_MSG_IOV, (int) iovAddress);
            PlatformDependent.putInt(memoryAddress + Native.MSGHDR_OFFSETOF_MSG_IOVLEN, iovLength);
            PlatformDependent.putInt(memoryAddress + Native.MSGHDR_OFFSETOF_MSG_CONTROL, (int) msgControlAddr);
            PlatformDependent.putInt(memoryAddress + Native.MSGHDR_OFFSETOF_MSG_CONTROLLEN, msgControlLen);
        } else {
            assert Native.SIZEOF_SIZE_T == 8;
            PlatformDependent.putLong(memoryAddress + Native.MSGHDR_OFFSETOF_MSG_NAME, address);
            PlatformDependent.putLong(memoryAddress + Native.MSGHDR_OFFSETOF_MSG_IOV, iovAddress);
            PlatformDependent.putLong(memoryAddress + Native.MSGHDR_OFFSETOF_MSG_IOVLEN, iovLength);
            PlatformDependent.putLong(memoryAddress + Native.MSGHDR_OFFSETOF_MSG_CONTROL, msgControlAddr);
            PlatformDependent.putLong(memoryAddress + Native.MSGHDR_OFFSETOF_MSG_CONTROLLEN, msgControlLen);
        }
        // No flags (we assume the memory was memset before)
    }
}
