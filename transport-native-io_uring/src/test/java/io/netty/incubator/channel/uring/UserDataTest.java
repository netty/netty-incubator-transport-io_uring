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


import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class UserDataTest {
    @Test
    public void testUserData() {
        // Ensure userdata works with negative and positive values
        for (int fd : new int[] { -10, -1, 0, 1, 10, Short.MAX_VALUE, Integer.MAX_VALUE }) {
            for (byte op = 0; op < 20; op++) {
                for (int data = Short.MIN_VALUE; data <= Short.MAX_VALUE; data++) {
                    final int expectedFd = fd;
                    final byte expectedOp = op;
                    final short expectedData = (short) data;
                    long udata = UserData.encode(expectedFd, expectedOp, expectedData);
                    UserData.decode(0, 0, udata, new IOUringCompletionQueueCallback() {
                        @Override
                        public void handle(int actualFd, int res, int flags, byte actualOp, short actualData) {
                            assertEquals(expectedFd, actualFd);
                            assertEquals(expectedOp, actualOp);
                            assertEquals(expectedData, actualData);
                        }
                    });
                }
            }
        }
    }
}
