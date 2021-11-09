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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class IOUringSubmissionQueueTest {

    @BeforeAll
    public static void loadJNI() {
        assumeTrue(IOUring.isAvailable());
    }

    @Test
    public void sqeFullTest() {
        RingBuffer ringBuffer = Native.createRingBuffer(8);
        ByteBuffer buffer = Buffer.allocateDirectWithNativeOrder(128);
        try {
            IOUringSubmissionQueue submissionQueue = ringBuffer.ioUringSubmissionQueue();
            final IOUringCompletionQueue completionQueue = ringBuffer.ioUringCompletionQueue();

            assertNotNull(ringBuffer);
            assertNotNull(submissionQueue);
            assertNotNull(completionQueue);

            long address = Buffer.memoryAddress(buffer);
            int counter = 0;
            while (!submissionQueue.addAccept(-1, address, 128, (short) 0)) {
                counter++;
            }
            assertEquals(8, counter);
        } finally {
            Buffer.free(buffer);
            ringBuffer.close();
        }
    }
}
