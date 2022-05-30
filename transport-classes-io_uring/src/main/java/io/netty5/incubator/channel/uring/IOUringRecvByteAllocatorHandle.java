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

import io.netty5.buffer.api.Buffer;
import io.netty5.buffer.api.BufferAllocator;
import io.netty5.buffer.api.DefaultBufferAllocators;
import io.netty5.channel.RecvBufferAllocator;
import io.netty5.channel.RecvBufferAllocator.Handle;
import io.netty5.util.UncheckedBooleanSupplier;

final class IOUringRecvByteAllocatorHandle extends RecvBufferAllocator.DelegatingHandle {
    private final UncheckedBooleanSupplier defaultMaybeMoreDataSupplier = this::maybeMoreDataToRead;

    IOUringRecvByteAllocatorHandle(Handle handle) {
        super(handle);
    }

    private boolean rdHupReceived;

    void rdHupReceived() {
        this.rdHupReceived = true;
    }

    boolean maybeMoreDataToRead() {
        return lastBytesRead() > 0;
    }

    @Override
    public Buffer allocate(BufferAllocator alloc) {
        // We need to ensure we always allocate a direct Buffer as we can only use a direct buffer to read via JNI.
        if (!alloc.getAllocationType().isDirect()) {
            return super.allocate(DefaultBufferAllocators.offHeapAllocator());
        }
        return super.allocate(alloc);
    }

    @Override
    public boolean continueReading() {
        // If we received an POLLRDHUP we need to continue draining the input until there is nothing left.
        return delegate().continueReading(defaultMaybeMoreDataSupplier) || rdHupReceived;
    }
}
