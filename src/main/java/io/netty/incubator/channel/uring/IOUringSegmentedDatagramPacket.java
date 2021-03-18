/*
 * Copyright 2021 The Netty Project
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
import io.netty.util.internal.ObjectUtil;

import java.net.InetSocketAddress;

/**
 * Allows to use <a href="https://blog.cloudflare.com/accelerating-udp-packet-transmission-for-quic/">GSO</a>
 * if the underlying OS supports it. Before instance and use this class you should check {@link #isSupported()}.
 */
public final class IOUringSegmentedDatagramPacket extends DatagramPacket {

    private final int segmentSize;

    /**
     * Create a new instance.
     *
     * @param data          the {@link ByteBuf} which must be continguous.
     * @param segmentSize   the segment size.
     * @param recipient     the recipient.
     */
    public IOUringSegmentedDatagramPacket(ByteBuf data, int segmentSize, InetSocketAddress recipient) {
        super(data, recipient);
        checkIsSupported();
        this.segmentSize = ObjectUtil.checkPositive(segmentSize, "segmentSize");
    }

    /**
     * Create a new instance.
     *
     * @param data          the {@link ByteBuf} which must be continguous.
     * @param segmentSize   the segment size.
     * @param recipient     the recipient.
     */
    public IOUringSegmentedDatagramPacket(ByteBuf data, int segmentSize,
                                          InetSocketAddress recipient, InetSocketAddress sender) {
        super(data, recipient, sender);
        checkIsSupported();
        this.segmentSize = ObjectUtil.checkPositive(segmentSize, "segmentSize");
    }

    /**
     * Returns {@code true} if the underlying system supports GSO.
     */
    public static boolean isSupported() {
        return IOUring.isAvailable();
    }

    /**
     * Return the size of each segment (the last segment can be smaller).
     *
     * @return size of segments.
     */
    public int segmentSize() {
        return segmentSize;
    }

    @Override
    public IOUringSegmentedDatagramPacket copy() {
        return new IOUringSegmentedDatagramPacket(content().copy(), segmentSize, recipient(), sender());
    }

    @Override
    public IOUringSegmentedDatagramPacket duplicate() {
        return new IOUringSegmentedDatagramPacket(content().duplicate(), segmentSize, recipient(), sender());
    }

    @Override
    public IOUringSegmentedDatagramPacket retainedDuplicate() {
        return new IOUringSegmentedDatagramPacket(content().retainedDuplicate(), segmentSize, recipient(), sender());
    }

    @Override
    public IOUringSegmentedDatagramPacket replace(ByteBuf content) {
        return new IOUringSegmentedDatagramPacket(content, segmentSize, recipient(), sender());
    }

    @Override
    public IOUringSegmentedDatagramPacket retain() {
        super.retain();
        return this;
    }

    @Override
    public IOUringSegmentedDatagramPacket retain(int increment) {
        super.retain(increment);
        return this;
    }

    @Override
    public IOUringSegmentedDatagramPacket touch() {
        super.touch();
        return this;
    }

    @Override
    public IOUringSegmentedDatagramPacket touch(Object hint) {
        super.touch(hint);
        return this;
    }

    private static void checkIsSupported() {
        if (!isSupported()) {
            throw new IllegalStateException();
        }
    }
}
