/*
 * The host process's half of the JNI layer (docs/design.md, "Files" and "Network"): the sandbox broker that
 * answers the engine process's path calls and, with the network granted, makes its sockets. It is a library of its
 * own (libaria2broker.so) so the host's main process never loads libaria2.
 *
 * The engine sees one folder, virtualRoot (by default /aria2/downloads), which the broker maps to realRoot, a
 * folder the host chose; nothing else of the host's is reachable through it. An isolated engine's other paths stay
 * the kernel's, where its isolated UID decides. An engine under the host's own UID (Android 8 to 10, where an
 * isolated process cannot use a socket it was handed) sends every path here, so with systemReads the broker also
 * answers the platform's read-only code and data, and nothing else.
 */
#include <jni.h>
#include <errno.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>
#include <android/log.h>

#include "net_scope.h"
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
 * Without the local network, every connect the engine asks for is put to net_scope: the device itself and the network it
 * is on are refused, so a torrent's peers, a web seed, a Metalink's mirror or a redirect the host did not walk can never
 * reach a router's page, a NAS or a service on loopback. The one way through is a pass: an address and port the host
 * vouched for because it walked an HTTP download there itself (nativeAllowLocal). A system socket (named by its path)
 * keeps the rules' verdict.
 * A UDP datagram sent without a connect names its address in memory the broker never sees (sbx.h), so DHT packets
 * are not covered by this.
 */
static int decide(void *context, const struct sbx_access *access) {
    (void) context;
    if (strcmp(access->op, "connect") != 0) return SBX_KEEP;
    const char *address = access->resolved && access->resolved[0] ? access->resolved : access->asked;
    if (address && address[0] == '/') return SBX_KEEP;
    return net_scope_is_local(address) && !net_scope_is_allowed(address) ? -EACCES : SBX_KEEP;
}

/* A pass for one local address and port ("192.168.1.20:443", "[fd7a::1]:443"), counted; 0 or a negative errno. */
JNIEXPORT jint JNICALL Java_io_github_bi0shacker001_aria2engine_EngineBroker_nativeAllowLocal(JNIEnv *env, jclass clazz, jstring words) {
    (void) clazz;
    const char *text = (*env)->GetStringUTFChars(env, words, NULL);
    int rv = net_scope_allow(text);
    (*env)->ReleaseStringUTFChars(env, words, text);
    return rv;
}

JNIEXPORT jint JNICALL Java_io_github_bi0shacker001_aria2engine_EngineBroker_nativeRevokeLocal(JNIEnv *env, jclass clazz, jstring words) {
    (void) clazz;
    const char *text = (*env)->GetStringUTFChars(env, words, NULL);
    int rv = net_scope_revoke(text);
    (*env)->ReleaseStringUTFChars(env, words, text);
    return rv;
}

/*
 * Starts a broker on its own thread for the engine process. Returns the engine's end of the socket (the host
 * hands it over and closes its copy), or a negative errno.
 */
JNIEXPORT jint JNICALL Java_io_github_bi0shacker001_aria2engine_EngineBroker_nativeServe(
    JNIEnv *env, jclass clazz, jstring virtual_root, jstring real_root, jboolean network, jboolean local_network,
    jboolean system_reads, jobjectArray extra_reads) {
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
    if (rv == 0 && system_reads) {
        /* The platform's own code, libraries and data (certificates, time zones, the linker's config), read-only;
         * the few device nodes libc and OpenSSL read. Never the host's private folders or shared storage. */
        static const char *const reads[] = {"/system", "/apex", "/vendor", "/product", "/system_ext", "/odm",
                                            "/linkerconfig", "/dev/urandom", "/dev/random", "/dev/null",
                                            "/proc/self", "/proc/sys/kernel", "/sys/devices/system/cpu"};
        for (size_t i = 0; i < sizeof(reads) / sizeof(reads[0]) && rv == 0; i++) rv = sbx_policy_add(policy, reads[i], SBX_READ);
        /* The host's own code (its APKs and native libraries), which the runtime may still open: never its data. */
        jsize n = extra_reads ? (*env)->GetArrayLength(env, extra_reads) : 0;
        for (jsize i = 0; i < n && rv == 0; i++) {
            jstring item = (jstring) (*env)->GetObjectArrayElement(env, extra_reads, i);
            const char *path = (*env)->GetStringUTFChars(env, item, NULL);
            rv = sbx_policy_add(policy, path, SBX_READ);
            (*env)->ReleaseStringUTFChars(env, item, path);
            (*env)->DeleteLocalRef(env, item);
        }
    }
    (*env)->ReleaseStringUTFChars(env, virtual_root, virt);
    (*env)->ReleaseStringUTFChars(env, real_root, real);
    if (rv == 0 && network) {
        /* Out and in for the engine's transfers and its RPC port, and names through netd's resolver; bionic
         * reports each connect and accept to fwmarkd, which the broker connects for it too. */
        sbx_policy_set_net(policy, SBX_NET_CONNECT | SBX_NET_LISTEN | SBX_NET_RESOLVE | (local_network ? 0u : SBX_NET_DECIDE));
        if (!local_network) sbx_policy_set_decider(policy, decide, NULL);
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
