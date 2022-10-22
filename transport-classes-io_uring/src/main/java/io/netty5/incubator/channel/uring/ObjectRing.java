/*
 * Copyright 2022 The Netty Project
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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class ObjectRing<T> {
    private Object[] objs;
    private short[] ids;
    private int head; // next push index
    private int tail; // next poll index, unless same as head
    private Object polledObject;
    private short polledId;

    ObjectRing() {
        objs = new Object[16];
        ids = new short[16];
    }

    void push(@NotNull T obj, short id) {
        int nextHead = next(head);
        if (nextHead == tail) {
            expand();
            nextHead = next(head);
        }
        objs[head] = obj;
        ids[head] = id;
        head = nextHead;
    }

    boolean isEmpty() {
        return tail == head;
    }

    @SuppressWarnings("unchecked")
    @Nullable T remove(short id) {
        if (isEmpty()) {
            return null;
        }
        if (ids[tail] == id) {
            Object obj = objs[tail];
            objs[tail] = null;
            ids[tail] = 0;
            tail = next(tail);
            return (T) obj;
        }
        // iterate and do internal remove
        int index = tail;
        while (index != head) {
            if (ids[index] == id) {
                Object obj = objs[index];
                // Found the obj; move prior entries up.
                int i = tail;
                Object prevObj = objs[tail];
                short prevId = ids[tail];
                do {
                    i = next(i);
                    Object tmpObj = objs[i];
                    short tmpId = ids[i];
                    objs[i] = prevObj;
                    ids[i] = prevId;
                    prevObj = tmpObj;
                    prevId = tmpId;
                } while (i != index);
                tail = next(tail);
                return (T) obj;
            }
            index = next(index);
        }
        return null;
    }

    boolean poll() {
        if (isEmpty()) {
            return false;
        }
        polledObject = objs[tail];
        polledId = ids[tail];
        objs[tail] = null;
        ids[tail] = 0;
        tail = next(tail);
        return true;
    }

    boolean peek() {
        if (isEmpty()) {
            return false;
        }
        polledObject = objs[tail];
        polledId = ids[tail];
        return true;
    }

    boolean hasNextId(short id) {
        return !isEmpty() && ids[tail] == id;
    }

    @SuppressWarnings("unchecked")
    T getPolledObject() {
        T object = (T) polledObject;
        polledObject = null;
        return object;
    }

    short getPolledId() {
        short id = polledId;
        polledId = 0;
        return id;
    }

    private void expand() {
        Object[] nextBufs = new Object[objs.length << 1];
        short[] nextIds = new short[ids.length << 1];
        int index = 0;
        while (head != tail) {
            nextBufs[index] = objs[tail];
            nextIds[index] = ids[tail];
            index++;
            tail = next(tail);
        }
        objs = nextBufs;
        ids = nextIds;
        tail = 0;
        head = index;
    }

    private int next(int index) {
        if (index + 1 == objs.length) {
            return 0;
        }
        return index + 1;
    }
}
