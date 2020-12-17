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
#define _GNU_SOURCE // RTLD_DEFAULT
#include "netty_io_uring.h"
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include "syscall.h"
#include "netty_io_uring_linuxsocket.h"
#include <errno.h>
#include <fcntl.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <syscall.h>
#include <unistd.h>

#include <arpa/inet.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <sys/un.h>

#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/eventfd.h>
#include <poll.h>

#define NATIVE_CLASSNAME "io/netty/incubator/channel/uring/Native"
#define STATICALLY_CLASSNAME "io/netty/incubator/channel/uring/NativeStaticallyReferencedJniMethods"

static jclass longArrayClass = NULL;

static void netty_io_uring_native_JNI_OnUnLoad(JNIEnv* env, const char* packagePrefix) {
    netty_unix_limits_JNI_OnUnLoad(env, packagePrefix);
    netty_unix_errors_JNI_OnUnLoad(env, packagePrefix);
    netty_unix_filedescriptor_JNI_OnUnLoad(env, packagePrefix);
    netty_unix_socket_JNI_OnUnLoad(env, packagePrefix);
    netty_unix_buffer_JNI_OnUnLoad(env, packagePrefix);

    NETTY_JNI_UTIL_UNLOAD_CLASS(env, longArrayClass);
}

void io_uring_unmap_rings(struct io_uring_sq *sq, struct io_uring_cq *cq) {
    munmap(sq->ring_ptr, sq->ring_sz);
    if (cq->ring_ptr && cq->ring_ptr != sq->ring_ptr) {
        munmap(cq->ring_ptr, cq->ring_sz);
  }
}

static int io_uring_mmap(int fd, struct io_uring_params *p, struct io_uring_sq *sq, struct io_uring_cq *cq) {
    size_t size;
    int ret;

    sq->ring_sz = p->sq_off.array + p->sq_entries * sizeof(unsigned);
    cq->ring_sz = p->cq_off.cqes + p->cq_entries * sizeof(struct io_uring_cqe);

    if ((p->features & IORING_FEAT_SINGLE_MMAP) == 1) {
        if (cq->ring_sz > sq->ring_sz) {
            sq->ring_sz = cq->ring_sz;
        }
        cq->ring_sz = sq->ring_sz;
    }
    sq->ring_ptr = mmap(0, sq->ring_sz, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_POPULATE, fd, IORING_OFF_SQ_RING);
    if (sq->ring_ptr == MAP_FAILED) {
        return -errno;
    }

    if ((p->features & IORING_FEAT_SINGLE_MMAP) == 1) {
        cq->ring_ptr = sq->ring_ptr;
    } else {
        cq->ring_ptr = mmap(0, cq->ring_sz, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_POPULATE, fd, IORING_OFF_CQ_RING);
        if (cq->ring_ptr == MAP_FAILED) {
            cq->ring_ptr = NULL;
            ret = -errno;
            goto err;
        }
    }

    sq->khead = sq->ring_ptr + p->sq_off.head;
    sq->ktail = sq->ring_ptr + p->sq_off.tail;
    sq->kring_mask = sq->ring_ptr + p->sq_off.ring_mask;
    sq->kring_entries = sq->ring_ptr + p->sq_off.ring_entries;
    sq->kflags = sq->ring_ptr + p->sq_off.flags;
    sq->kdropped = sq->ring_ptr + p->sq_off.dropped;
    sq->array = sq->ring_ptr + p->sq_off.array;

    size = p->sq_entries * sizeof(struct io_uring_sqe);
    sq->sqes = mmap(0, size, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_POPULATE, fd, IORING_OFF_SQES);
    if (sq->sqes == MAP_FAILED) {
        ret = -errno;
        goto err;
    }

    cq->khead = cq->ring_ptr + p->cq_off.head;
    cq->ktail = cq->ring_ptr + p->cq_off.tail;
    cq->kring_mask = cq->ring_ptr + p->cq_off.ring_mask;
    cq->kring_entries = cq->ring_ptr + p->cq_off.ring_entries;
    cq->koverflow = cq->ring_ptr + p->cq_off.overflow;
    cq->cqes = cq->ring_ptr + p->cq_off.cqes;

    return 0;
err:
    io_uring_unmap_rings(sq, cq);
    return ret;
}

int setup_io_uring(int ring_fd, struct io_uring *io_uring_ring,
                    struct io_uring_params *p) {
    return io_uring_mmap(ring_fd, p, &io_uring_ring->sq, &io_uring_ring->cq);
}

static jint netty_io_uring_enter(JNIEnv *env, jclass class1, jint ring_fd, jint to_submit,
                                 jint min_complete, jint flags) {
    int result;
    int err;
    do {
        result = sys_io_uring_enter(ring_fd, to_submit, min_complete, flags, NULL);
        if (result >= 0) {
            return result;
        }
    } while ((err = errno) == EINTR);
    return -err;
}

static jint netty_epoll_native_blocking_event_fd(JNIEnv* env, jclass clazz) {
    // We use a blocking fd with io_uring FAST_POLL read
    jint eventFD = eventfd(0, EFD_CLOEXEC);

    if (eventFD < 0) {
        netty_unix_errors_throwChannelExceptionErrorNo(env, "eventfd() failed: ", errno);
    }
    return eventFD;
}

static void netty_io_uring_eventFdWrite(JNIEnv* env, jclass clazz, jint fd, jlong value) {
    int result;
    int err;
    do {
        result = eventfd_write(fd, (eventfd_t) value);
        if (result >= 0) {
            return;
        }
    } while ((err = errno) == EINTR);
    netty_unix_errors_throwChannelExceptionErrorNo(env, "eventfd_write(...) failed: ", err);
}

static void netty_io_uring_ring_buffer_exit(JNIEnv *env, jclass clazz,
        jlong submissionQueueArrayAddress, jint submissionQueueRingEntries, jlong submissionQueueRingAddress, jint submissionQueueRingSize,
        jlong completionQueueRingAddress, jint completionQueueRingSize, jint ringFd) {
    munmap((struct io_uring_sqe*) submissionQueueArrayAddress, submissionQueueRingEntries * sizeof(struct io_uring_sqe));
    munmap((void*) submissionQueueRingAddress, submissionQueueRingSize);

    if (((void *) completionQueueRingAddress) && ((void *) completionQueueRingAddress) != ((void *) submissionQueueRingAddress)) {
        munmap((void *)completionQueueRingAddress, completionQueueRingSize);
    }
    close(ringFd);
}

static jboolean netty_io_uring_probe(JNIEnv *env, jclass clazz, jint ring_fd, jintArray ops) {
    jboolean supported = JNI_FALSE;
    struct io_uring_probe *probe;
    size_t mallocLen = sizeof(*probe) + 256 * sizeof(struct io_uring_probe_op);
    probe = malloc(mallocLen);
    memset(probe, 0, mallocLen);

    if (sys_io_uring_register(ring_fd, IORING_REGISTER_PROBE, probe, 256) < 0) {
        netty_unix_errors_throwRuntimeExceptionErrorNo(env, "failed to probe via sys_io_uring_register(....) ", errno);
        goto done;
    }

    jsize opsLen = (*env)->GetArrayLength(env, ops);
    jint *opsElements = (*env)->GetIntArrayElements(env, ops, 0);
    int i;
    for (i = 0; i < opsLen; i++) {
        int op = opsElements[i];
        if (op > probe->last_op || (probe->ops[op].flags & IO_URING_OP_SUPPORTED) == 0) {
            goto done;
        }
    }
    // all supported
    supported = JNI_TRUE;
done:
    free(probe);
    return supported;
}


static jobjectArray netty_io_uring_setup(JNIEnv *env, jclass clazz, jint entries) {
    struct io_uring_params p;
    memset(&p, 0, sizeof(p));

    jobjectArray array = (*env)->NewObjectArray(env, 2, longArrayClass, NULL);
    if (array == NULL) {
        // This will put an OOME on the stack
        return NULL;
    }
    jlongArray submissionArray = (*env)->NewLongArray(env, 11);
    if (submissionArray == NULL) {
        // This will put an OOME on the stack
        return NULL;

    }
    jlongArray completionArray = (*env)->NewLongArray(env, 9);
    if (completionArray == NULL) {
        // This will put an OOME on the stack
        return NULL;
    }

    int ring_fd = sys_io_uring_setup((int)entries, &p);

    if (ring_fd < 0) {
        netty_unix_errors_throwRuntimeExceptionErrorNo(env, "failed to create io_uring ring fd ", errno);
        return NULL;
    }
    struct io_uring io_uring_ring;
    int ret = setup_io_uring(ring_fd, &io_uring_ring, &p);

    if (ret != 0) {
        netty_unix_errors_throwRuntimeExceptionErrorNo(env, "failed to mmap io_uring ring buffer", ret);
        return NULL;
    }

    jlong submissionArrayElements[] = {
        (jlong)io_uring_ring.sq.khead,
        (jlong)io_uring_ring.sq.ktail,
        (jlong)io_uring_ring.sq.kring_mask,
        (jlong)io_uring_ring.sq.kring_entries,
        (jlong)io_uring_ring.sq.kflags,
        (jlong)io_uring_ring.sq.kdropped,
        (jlong)io_uring_ring.sq.array,
        (jlong)io_uring_ring.sq.sqes,
        (jlong)io_uring_ring.sq.ring_sz,
        (jlong)io_uring_ring.sq.ring_ptr,
        (jlong)ring_fd
    };
    (*env)->SetLongArrayRegion(env, submissionArray, 0, 11, submissionArrayElements);

    jlong completionArrayElements[] = {
        (jlong)io_uring_ring.cq.khead,
        (jlong)io_uring_ring.cq.ktail,
        (jlong)io_uring_ring.cq.kring_mask,
        (jlong)io_uring_ring.cq.kring_entries,
        (jlong)io_uring_ring.cq.koverflow,
        (jlong)io_uring_ring.cq.cqes,
        (jlong)io_uring_ring.cq.ring_sz,
        (jlong)io_uring_ring.cq.ring_ptr,
        (jlong)ring_fd
    };
    (*env)->SetLongArrayRegion(env, completionArray, 0, 9, completionArrayElements);

    (*env)->SetObjectArrayElement(env, array, 0, submissionArray);
    (*env)->SetObjectArrayElement(env, array, 1, completionArray);
    return array;
}

static jint netty_create_file(JNIEnv *env, jclass class) {
    return open("io-uring-test.txt", O_RDWR | O_TRUNC | O_CREAT, 0644);
}

static jint netty_io_uring_sockNonblock(JNIEnv* env, jclass clazz) {
    return SOCK_NONBLOCK;
}

static jint netty_io_uring_sockCloexec(JNIEnv* env, jclass clazz) {
    return SOCK_CLOEXEC;
}

static jint netty_io_uring_afInet(JNIEnv* env, jclass clazz) {
    return AF_INET;
}

static jint netty_io_uring_afInet6(JNIEnv* env, jclass clazz) {
    return AF_INET6;
}

static jint netty_io_uring_sizeofSockaddrIn(JNIEnv* env, jclass clazz) {
    return sizeof(struct sockaddr_in);
}

static jint netty_io_uring_sizeofSockaddrIn6(JNIEnv* env, jclass clazz) {
    return sizeof(struct sockaddr_in6);
}

static jint netty_io_uring_sockaddrInOffsetofSinFamily(JNIEnv* env, jclass clazz) {
    return offsetof(struct sockaddr_in, sin_family);
}

static jint netty_io_uring_sockaddrInOffsetofSinPort(JNIEnv* env, jclass clazz) {
    return offsetof(struct sockaddr_in, sin_port);
}

static jint netty_io_uring_sockaddrInOffsetofSinAddr(JNIEnv* env, jclass clazz) {
    return offsetof(struct sockaddr_in, sin_addr);
}

static jint netty_io_uring_inAddressOffsetofSAddr(JNIEnv* env, jclass clazz) {
    return offsetof(struct in_addr, s_addr);
}

static jint netty_io_uring_sockaddrIn6OffsetofSin6Family(JNIEnv* env, jclass clazz) {
    return offsetof(struct sockaddr_in6, sin6_family);
}

static jint netty_io_uring_sockaddrIn6OffsetofSin6Port(JNIEnv* env, jclass clazz) {
    return offsetof(struct sockaddr_in6, sin6_port);
}

static jint netty_io_uring_sockaddrIn6OffsetofSin6Flowinfo(JNIEnv* env, jclass clazz) {
    return offsetof(struct sockaddr_in6, sin6_flowinfo);
}

static jint netty_io_uring_sockaddrIn6OffsetofSin6Addr(JNIEnv* env, jclass clazz) {
    return offsetof(struct sockaddr_in6, sin6_addr);
}

static jint netty_io_uring_sockaddrIn6OffsetofSin6ScopeId(JNIEnv* env, jclass clazz) {
    return offsetof(struct sockaddr_in6, sin6_scope_id);
}

static jint netty_io_uring_in6AddressOffsetofS6Addr(JNIEnv* env, jclass clazz) {
    return offsetof(struct in6_addr, s6_addr);
}

static jint netty_io_uring_sizeofSockaddrStorage(JNIEnv* env, jclass clazz) {
    return sizeof(struct sockaddr_storage);
}

static jint netty_io_uring_sizeofSizeT(JNIEnv* env, jclass clazz) {
    return sizeof(size_t);
}

static jint netty_io_uring_sizeofIovec(JNIEnv* env, jclass clazz) {
    return sizeof(struct iovec);
}

static jint netty_io_uring_iovecOffsetofIovBase(JNIEnv* env, jclass clazz) {
    return offsetof(struct iovec, iov_base);
}

static jint netty_io_uring_iovecOffsetofIovLen(JNIEnv* env, jclass clazz) {
    return offsetof(struct iovec, iov_len);
}

static jint netty_io_uring_sizeofMsghdr(JNIEnv* env, jclass clazz) {
    return sizeof(struct msghdr);
}

static jint netty_io_uring_msghdrOffsetofMsgName(JNIEnv* env, jclass clazz) {
    return offsetof(struct msghdr, msg_name);
}

static jint netty_io_uring_msghdrOffsetofMsgNamelen(JNIEnv* env, jclass clazz) {
    return offsetof(struct msghdr, msg_namelen);
}
static jint netty_io_uring_msghdrOffsetofMsgIov(JNIEnv* env, jclass clazz) {
    return offsetof(struct msghdr, msg_iov);
}
static jint netty_io_uring_msghdrOffsetofMsgIovlen(JNIEnv* env, jclass clazz) {
    return offsetof(struct msghdr, msg_iovlen);
}

static jint netty_io_uring_msghdrOffsetofMsgControl(JNIEnv* env, jclass clazz) {
    return offsetof(struct msghdr, msg_control);
}

static jint netty_io_uring_msghdrOffsetofMsgControllen(JNIEnv* env, jclass clazz) {
    return offsetof(struct msghdr, msg_controllen);
}

static jint netty_io_uring_msghdrOffsetofMsgFlags(JNIEnv* env, jclass clazz) {
    return offsetof(struct msghdr, msg_flags);
}

static jint netty_io_uring_etime(JNIEnv* env, jclass clazz) {
    return ETIME;
}

static jint netty_io_uring_ecanceled(JNIEnv* env, jclass clazz) {
    return ECANCELED;
}

static jint netty_io_uring_pollin(JNIEnv* env, jclass clazz) {
    return POLLIN;
}

static jint netty_io_uring_pollout(JNIEnv* env, jclass clazz) {
    return POLLOUT;
}

static jint netty_io_uring_pollrdhup(JNIEnv* env, jclass clazz) {
    return POLLRDHUP;
}

static jbyte netty_io_uring_ioringOpWritev(JNIEnv* env, jclass clazz) {
    return IORING_OP_WRITEV;
}

static jbyte netty_io_uring_ioringOpPollAdd(JNIEnv* env, jclass clazz) {
    return IORING_OP_POLL_ADD;
}

static jbyte netty_io_uring_ioringOpPollRemove(JNIEnv* env, jclass clazz) {
    return IORING_OP_POLL_REMOVE;
}

static jbyte netty_io_uring_ioringOpTimeout(JNIEnv* env, jclass clazz) {
    return IORING_OP_TIMEOUT;
}

static jbyte netty_io_uring_ioringOpAccept(JNIEnv* env, jclass clazz) {
    return IORING_OP_ACCEPT;
}

static jbyte netty_io_uring_ioringOpRead(JNIEnv* env, jclass clazz) {
    return IORING_OP_READ;
}

static jbyte netty_io_uring_ioringOpWrite(JNIEnv* env, jclass clazz) {
    return IORING_OP_WRITE;
}

static jbyte netty_io_uring_ioringOpConnect(JNIEnv* env, jclass clazz) {
    return IORING_OP_CONNECT;
}

static jbyte netty_io_uring_ioringOpClose(JNIEnv* env, jclass clazz) {
    return IORING_OP_CLOSE;
}

static jbyte netty_io_uring_ioringOpSendmsg(JNIEnv* env, jclass clazz) {
    return IORING_OP_SENDMSG;
}

static jbyte netty_io_uring_ioringOpRecvmsg(JNIEnv* env, jclass clazz) {
    return IORING_OP_RECVMSG;
}

static jint netty_io_uring_ioringEnterGetevents(JNIEnv* env, jclass clazz) {
    return IORING_ENTER_GETEVENTS;
}

static jint netty_io_uring_iosqeAsync(JNIEnv* env, jclass clazz) {
    return IOSQE_ASYNC;
}


// JNI Method Registration Table Begin
static const JNINativeMethod statically_referenced_fixed_method_table[] = {
  { "sockNonblock", "()I", (void *) netty_io_uring_sockNonblock },
  { "sockCloexec", "()I", (void *) netty_io_uring_sockCloexec },
  { "afInet", "()I", (void *) netty_io_uring_afInet },
  { "afInet6", "()I", (void *) netty_io_uring_afInet6 },
  { "sizeofSockaddrIn", "()I", (void *) netty_io_uring_sizeofSockaddrIn },
  { "sizeofSockaddrIn6", "()I", (void *) netty_io_uring_sizeofSockaddrIn6 },
  { "sockaddrInOffsetofSinFamily", "()I", (void *) netty_io_uring_sockaddrInOffsetofSinFamily },
  { "sockaddrInOffsetofSinPort", "()I", (void *) netty_io_uring_sockaddrInOffsetofSinPort },
  { "sockaddrInOffsetofSinAddr", "()I", (void *) netty_io_uring_sockaddrInOffsetofSinAddr },
  { "inAddressOffsetofSAddr", "()I", (void *) netty_io_uring_inAddressOffsetofSAddr },
  { "sockaddrIn6OffsetofSin6Family", "()I", (void *) netty_io_uring_sockaddrIn6OffsetofSin6Family },
  { "sockaddrIn6OffsetofSin6Port", "()I", (void *) netty_io_uring_sockaddrIn6OffsetofSin6Port },
  { "sockaddrIn6OffsetofSin6Flowinfo", "()I", (void *) netty_io_uring_sockaddrIn6OffsetofSin6Flowinfo },
  { "sockaddrIn6OffsetofSin6Addr", "()I", (void *) netty_io_uring_sockaddrIn6OffsetofSin6Addr },
  { "sockaddrIn6OffsetofSin6ScopeId", "()I", (void *) netty_io_uring_sockaddrIn6OffsetofSin6ScopeId },
  { "in6AddressOffsetofS6Addr", "()I", (void *) netty_io_uring_in6AddressOffsetofS6Addr },
  { "sizeofSockaddrStorage", "()I", (void *) netty_io_uring_sizeofSockaddrStorage },
  { "sizeofSizeT", "()I", (void *) netty_io_uring_sizeofSizeT },
  { "sizeofIovec", "()I", (void *) netty_io_uring_sizeofIovec },
  { "iovecOffsetofIovBase", "()I", (void *) netty_io_uring_iovecOffsetofIovBase },
  { "iovecOffsetofIovLen", "()I", (void *) netty_io_uring_iovecOffsetofIovLen },
  { "sizeofMsghdr", "()I", (void *) netty_io_uring_sizeofMsghdr },
  { "msghdrOffsetofMsgName", "()I", (void *) netty_io_uring_msghdrOffsetofMsgName },
  { "msghdrOffsetofMsgNamelen", "()I", (void *) netty_io_uring_msghdrOffsetofMsgNamelen },
  { "msghdrOffsetofMsgIov", "()I", (void *) netty_io_uring_msghdrOffsetofMsgIov },
  { "msghdrOffsetofMsgIovlen", "()I", (void *) netty_io_uring_msghdrOffsetofMsgIovlen },
  { "msghdrOffsetofMsgControl", "()I", (void *) netty_io_uring_msghdrOffsetofMsgControl },
  { "msghdrOffsetofMsgControllen", "()I", (void *) netty_io_uring_msghdrOffsetofMsgControllen },
  { "msghdrOffsetofMsgFlags", "()I", (void *) netty_io_uring_msghdrOffsetofMsgFlags },
  { "etime", "()I", (void *) netty_io_uring_etime },
  { "ecanceled", "()I", (void *) netty_io_uring_ecanceled },
  { "pollin", "()I", (void *) netty_io_uring_pollin },
  { "pollout", "()I", (void *) netty_io_uring_pollout },
  { "pollrdhup", "()I", (void *) netty_io_uring_pollrdhup },
  { "ioringOpWritev", "()B", (void *) netty_io_uring_ioringOpWritev },
  { "ioringOpPollAdd", "()B", (void *) netty_io_uring_ioringOpPollAdd },
  { "ioringOpPollRemove", "()B", (void *) netty_io_uring_ioringOpPollRemove },
  { "ioringOpTimeout", "()B", (void *) netty_io_uring_ioringOpTimeout },
  { "ioringOpAccept", "()B", (void *) netty_io_uring_ioringOpAccept },
  { "ioringOpRead", "()B", (void *) netty_io_uring_ioringOpRead },
  { "ioringOpWrite", "()B", (void *) netty_io_uring_ioringOpWrite },
  { "ioringOpConnect", "()B", (void *) netty_io_uring_ioringOpConnect },
  { "ioringOpClose", "()B", (void *) netty_io_uring_ioringOpClose },
  { "ioringOpSendmsg", "()B", (void *) netty_io_uring_ioringOpSendmsg },
  { "ioringOpRecvmsg", "()B", (void *) netty_io_uring_ioringOpRecvmsg },
  { "ioringEnterGetevents", "()I", (void *) netty_io_uring_ioringEnterGetevents },
  { "iosqeAsync", "()I", (void *) netty_io_uring_iosqeAsync }
};
static const jint statically_referenced_fixed_method_table_size = sizeof(statically_referenced_fixed_method_table) / sizeof(statically_referenced_fixed_method_table[0]);

static const JNINativeMethod method_table[] = {
    {"ioUringSetup", "(I)[[J", (void *) netty_io_uring_setup},
    {"ioUringProbe", "(I[I)Z", (void *) netty_io_uring_probe},
    {"ioUringExit", "(JIJIJII)V", (void *) netty_io_uring_ring_buffer_exit},
    {"createFile", "()I", (void *) netty_create_file},
    {"ioUringEnter", "(IIII)I", (void *) netty_io_uring_enter},
    {"blockingEventFd", "()I", (void *) netty_epoll_native_blocking_event_fd},
    {"eventFdWrite", "(IJ)V", (void *) netty_io_uring_eventFdWrite }
};
static const jint method_table_size =
    sizeof(method_table) / sizeof(method_table[0]);
// JNI Method Registration Table End

static jint netty_iouring_native_JNI_OnLoad(JNIEnv* env, const char* packagePrefix) {
    int ret = JNI_ERR;
    int nativeRegistered = 0;
    int staticallyRegistered = 0;
    int limitsOnLoadCalled = 0;
    int errorsOnLoadCalled = 0;
    int filedescriptorOnLoadCalled = 0;
    int socketOnLoadCalled = 0;
    int bufferOnLoadCalled = 0;
    int linuxsocketOnLoadCalled = 0;

    // We must register the statically referenced methods first!
    if (netty_jni_util_register_natives(env,
            packagePrefix,
            STATICALLY_CLASSNAME,
            statically_referenced_fixed_method_table,
            statically_referenced_fixed_method_table_size) != 0) {
        goto done;
    }
    nativeRegistered = 1;

    if (netty_jni_util_register_natives(env, packagePrefix,
                                       NATIVE_CLASSNAME,
                                       method_table, method_table_size) != 0) {
        goto done;
    }
    staticallyRegistered = 1;

    // Load all c modules that we depend upon
    if (netty_unix_limits_JNI_OnLoad(env, packagePrefix) == JNI_ERR) {
        goto done;
    }
    limitsOnLoadCalled = 1;

    if (netty_unix_errors_JNI_OnLoad(env, packagePrefix) == JNI_ERR) {
        goto done;
    }
    errorsOnLoadCalled = 1;

    if (netty_unix_filedescriptor_JNI_OnLoad(env, packagePrefix) == JNI_ERR) {
        goto done;
    }
    filedescriptorOnLoadCalled = 1;

    if (netty_unix_socket_JNI_OnLoad(env, packagePrefix) == JNI_ERR) {
        goto done;
    }
    socketOnLoadCalled = 1;

    if (netty_unix_buffer_JNI_OnLoad(env, packagePrefix) == JNI_ERR) {
        goto done;
    }
    bufferOnLoadCalled = 1;

    if (netty_io_uring_linuxsocket_JNI_OnLoad(env, packagePrefix) == JNI_ERR) {
        goto done;
    }
    linuxsocketOnLoadCalled = 1;

    NETTY_JNI_UTIL_LOAD_CLASS(env, longArrayClass, "[J", done);

    ret = NETTY_JNI_UTIL_JNI_VERSION;
done:
    if (ret == JNI_ERR) {
        if (nativeRegistered == 1) {
            netty_jni_util_unregister_natives(env, packagePrefix, NATIVE_CLASSNAME);
        }
        if (staticallyRegistered == 1) {
            netty_jni_util_unregister_natives(env, packagePrefix, STATICALLY_CLASSNAME);
        }
        if (limitsOnLoadCalled == 1) {
            netty_unix_limits_JNI_OnUnLoad(env, packagePrefix);
        }
        if (errorsOnLoadCalled == 1) {
            netty_unix_errors_JNI_OnUnLoad(env, packagePrefix);
        }
        if (filedescriptorOnLoadCalled == 1) {
            netty_unix_filedescriptor_JNI_OnUnLoad(env, packagePrefix);
        }
        if (socketOnLoadCalled == 1) {
            netty_unix_socket_JNI_OnUnLoad(env, packagePrefix);
        }
        if (bufferOnLoadCalled == 1) {
            netty_unix_buffer_JNI_OnUnLoad(env, packagePrefix);
        }
        if (linuxsocketOnLoadCalled == 1) {
            netty_io_uring_linuxsocket_JNI_OnUnLoad(env, packagePrefix);
        }
    }
    return ret;
}

static void netty_iouring_native_JNI_OnUnload(JNIEnv* env, const char* packagePrefix) {
    netty_jni_util_unregister_natives(env, packagePrefix, NATIVE_CLASSNAME);
    netty_jni_util_unregister_natives(env, packagePrefix, STATICALLY_CLASSNAME);
    netty_io_uring_native_JNI_OnUnLoad(env, packagePrefix);
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    return netty_jni_util_JNI_OnLoad(vm, reserved, "netty_transport_native_io_uring", netty_iouring_native_JNI_OnLoad);
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved) {
    netty_jni_util_JNI_OnUnload(vm, reserved, netty_iouring_native_JNI_OnUnload);
}
