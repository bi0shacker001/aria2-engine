#include "net_scope.h"

#include <arpa/inet.h>
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

static int ipv4_is_local(const uint8_t a[4]) {
    if (a[0] == 0 || a[0] == 10 || a[0] == 127) return 1;     /* "this network", private, loopback */
    if (a[0] == 100 && (a[1] & 0xc0) == 64) return 1;         /* 100.64.0.0/10, carrier NAT and overlay networks */
    if (a[0] == 169 && a[1] == 254) return 1;                 /* link-local */
    if (a[0] == 172 && (a[1] & 0xf0) == 16) return 1;         /* private */
    if (a[0] == 192 && a[1] == 168) return 1;                 /* private */
    if (a[0] == 192 && a[1] == 0 && a[2] == 0) return 1;      /* IETF protocol assignments */
    if (a[0] == 198 && (a[1] & 0xfe) == 18) return 1;         /* benchmarking */
    if (a[0] >= 224) return 1;                                /* multicast, reserved, broadcast */
    return 0;
}

static int ipv6_is_local(const uint8_t a[16]) {
    static const uint8_t mapped[12] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff};
    static const uint8_t nat64[12] = {0, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0};
    static const uint8_t zero[16] = {0};
    if (memcmp(a, mapped, 12) == 0 || memcmp(a, nat64, 12) == 0) return ipv4_is_local(a + 12);
    if (memcmp(a, zero, 15) == 0 && (a[15] == 0 || a[15] == 1)) return 1;  /* unspecified, loopback */
    if ((a[0] & 0xfe) == 0xfc) return 1;                                   /* unique local, fc00::/7 */
    if (a[0] == 0xfe && (a[1] & 0xc0) == 0x80) return 1;                   /* link-local, fe80::/10 */
    if (a[0] == 0xfe && (a[1] & 0xc0) == 0xc0) return 1;                   /* site-local (deprecated), fec0::/10 */
    if (a[0] == 0xff) return 1;                                            /* multicast */
    return 0;
}

/* An address as words name it: its family's bytes and its port. */
struct endpoint {
    int v6;
    uint8_t address[16];
    unsigned port;
};

/* 0 when words is "a.b.c.d:port" or "[v6]:port" with a port, else -1. A zone is not an endpoint. */
static int parse(const char *words, struct endpoint *out) {
    char host[64];
    if (!words) return -1;
    const char *end;
    const char *start = words;
    out->v6 = *words == '[';
    if (out->v6) {
        start = words + 1;
        end = strchr(start, ']');
        if (!end || end[1] != ':') return -1;
    } else {
        end = strrchr(words, ':');
    }
    if (!end || end <= start || (size_t) (end - start) >= sizeof(host)) return -1;
    memcpy(host, start, (size_t) (end - start));
    host[end - start] = '\0';
    if (strchr(host, '%')) return -1;
    const char *digits = end + (out->v6 ? 2 : 1);
    char *rest;
    unsigned long port = strtoul(digits, &rest, 10);
    if (*digits < '0' || *digits > '9' || *rest != '\0' || port > 65535) return -1;
    out->port = (unsigned) port;
    memset(out->address, 0, sizeof(out->address));
    return inet_pton(out->v6 ? AF_INET6 : AF_INET, host, out->address) == 1 ? 0 : -1;
}

int net_scope_is_local(const char *words) {
    struct endpoint e;
    /* A zone ("fe80::1%wlan0") is only ever link-local, and anything unreadable is refused. */
    if (parse(words, &e) != 0) return 1;
    return e.v6 ? ipv6_is_local(e.address) : ipv4_is_local(e.address);
}

#define PASSES_MAX 32

static struct {
    struct endpoint endpoint;
    unsigned count;
} passes[PASSES_MAX];
static pthread_mutex_t passes_lock = PTHREAD_MUTEX_INITIALIZER;

static int same(const struct endpoint *a, const struct endpoint *b) {
    return a->v6 == b->v6 && a->port == b->port && memcmp(a->address, b->address, sizeof(a->address)) == 0;
}

int net_scope_allow(const char *words) {
    struct endpoint e;
    if (parse(words, &e) != 0) return -EINVAL;
    int rv = -ENOSPC;
    pthread_mutex_lock(&passes_lock);
    int free_slot = -1;
    for (int i = 0; i < PASSES_MAX; i++) {
        if (passes[i].count && same(&passes[i].endpoint, &e)) {
            passes[i].count++;
            rv = 0;
            break;
        }
        if (!passes[i].count && free_slot < 0) free_slot = i;
    }
    if (rv != 0 && free_slot >= 0) {
        passes[free_slot].endpoint = e;
        passes[free_slot].count = 1;
        rv = 0;
    }
    pthread_mutex_unlock(&passes_lock);
    return rv;
}

int net_scope_revoke(const char *words) {
    struct endpoint e;
    if (parse(words, &e) != 0) return -EINVAL;
    pthread_mutex_lock(&passes_lock);
    for (int i = 0; i < PASSES_MAX; i++) {
        if (passes[i].count && same(&passes[i].endpoint, &e)) {
            passes[i].count--;
            break;
        }
    }
    pthread_mutex_unlock(&passes_lock);
    return 0;
}

int net_scope_is_allowed(const char *words) {
    struct endpoint e;
    if (parse(words, &e) != 0) return 0;
    int found = 0;
    pthread_mutex_lock(&passes_lock);
    for (int i = 0; i < PASSES_MAX && !found; i++) found = passes[i].count && same(&passes[i].endpoint, &e);
    pthread_mutex_unlock(&passes_lock);
    return found;
}
