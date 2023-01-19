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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class MsgHdrMemoryArrayTest {

    @BeforeAll
    public static void loadJNI() {
        assumeTrue(IOUring.isAvailable());
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 10 })
    public void testNextHdr(int capacity) {
        MsgHdrMemoryArray array = new MsgHdrMemoryArray(capacity);
        try {
            for (int i = 0; i < capacity; i++) {
                assertNotNull(array.nextHdr());
            }
            assertNull(array.nextHdr());
            array.clear();
            assertNotNull(array.nextHdr());
        } finally {
            array.release();;
        }
    }
}
