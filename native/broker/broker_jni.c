/*
 * The host process's half of the JNI layer (docs/design.md, "Files" and "Network"): the sandbox broker that
 * answers the engine process's path calls and, with the network granted, makes its sockets. It is a library of its
 * own (libaria2broker.so) so the host's main process never loads libaria2.
 *
 * The engine sees one folder, virtualRoot (by default /aria2/downloads), which the broker maps to realRoot, a
 * folder the host chose; nothing else of the host's is reachable through it. Everything outside the engine's
 * claims stays the kernel's, where its isolated UID decides.
 */
#include <jni.h>
#include <errno.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
#include <android/log.h>

#include "sbx.h"

#define TAG "aria2engine.broker"

static void audit(void *context, const struct sbx_access *access, int result) {
    (void) context;
    if (result < 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "refused %s %s: %s", access->op, access->asked ? access->asked : "?",
                            strerror(-result));
    }
}

/*
 * Starts a broker on its own thread for the engine process. Returns the engine's end of the socket (the host
 * hands it over and closes its copy), or a negative errno.
 */
JNIEXPORT jint JNICALL Java_io_github_bi0shacker001_aria2engine_EngineBroker_nativeServe(
    JNIEnv *env, jclass clazz, jstring virtual_root, jstring real_root, jboolean network) {
    (void) clazz;
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, pair) != 0) return -errno;
    struct sbx_policy *policy = sbx_policy_new();
    if (!policy) {
        close(pair[0]);
        close(pair[1]);
        return -ENOMEM;
    }
    const char *virt = (*env)->GetStringUTFChars(env, virtual_root, NULL);
    const char *real = (*env)->GetStringUTFChars(env, real_root, NULL);
    int rv = sbx_policy_add(policy, real, SBX_READ | SBX_WRITE);
    if (rv == 0) rv = sbx_policy_alias(policy, virt, real);
    (*env)->ReleaseStringUTFChars(env, virtual_root, virt);
    (*env)->ReleaseStringUTFChars(env, real_root, real);
    if (rv == 0 && network) {
        /* Out and in for the engine's transfers and its RPC port, and names through netd's resolver; bionic
         * reports each connect and accept to fwmarkd, which the broker connects for it too. */
        sbx_policy_set_net(policy, SBX_NET_CONNECT | SBX_NET_LISTEN | SBX_NET_RESOLVE);
        sbx_policy_add_system_socket(policy, "/dev/socket/dnsproxyd", SBX_NET_RESOLVE);
        sbx_policy_add_system_socket(policy, "/dev/socket/fwmarkd", SBX_NET_CONNECT | SBX_NET_LISTEN);
    }
    sbx_policy_set_audit(policy, audit, NULL);
    /* sbx_broker_start prepares the socket first; on a failure the policy is still ours to free. */
    if (rv == 0) rv = sbx_broker_start(policy, pair[0]);
    if (rv != 0) {
        sbx_policy_free(policy);
        close(pair[0]);
        close(pair[1]);
        return rv < 0 ? rv : -EINVAL;
    }
    return pair[1];
}
