#include "net_scope.h"

#include <arpa/inet.h>
#include <stdint.h>
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

int net_scope_is_local(const char *words) {
    char host[64];
    if (!words) return 1;
    const char *end;
    const char *start = words;
    if (*words == '[') {
        start = words + 1;
        end = strchr(start, ']');
    } else {
        end = strrchr(words, ':');
    }
    if (!end || end <= start || (size_t) (end - start) >= sizeof(host)) return 1;
    memcpy(host, start, (size_t) (end - start));
    host[end - start] = '\0';
    /* A zone ("fe80::1%wlan0") is only ever link-local. */
    if (strchr(host, '%')) return 1;
    uint8_t address[16];
    if (*words == '[') return inet_pton(AF_INET6, host, address) == 1 ? ipv6_is_local(address) : 1;
    return inet_pton(AF_INET, host, address) == 1 ? ipv4_is_local(address) : 1;
}
