/* net_scope.c on the build machine: cc -Wall -Werror -pthread net_scope.c net_scope_test.c && ./a.out */
#include "net_scope.h"

#include <errno.h>
#include <stdio.h>

static int failures;

static void expect(const char *words, int local) {
    if (net_scope_is_local(words) != local) {
        fprintf(stderr, "FAIL %s: expected %s\n", words, local ? "local" : "public");
        failures++;
    }
}

/* One step of a sequence: the calls run in the order written. */
static void check(int got, int want, const char *what) {
    if (got != want) {
        fprintf(stderr, "FAIL %s: got %d, expected %d\n", what, got, want);
        failures++;
    }
}

int main(void) {
    const char *local[] = {
        "127.0.0.1:6800", "10.0.2.2:8080", "192.168.1.20:445", "172.16.0.1:80", "172.31.255.255:80",
        "169.254.1.1:80", "100.64.0.1:443", "100.127.255.254:443", "0.0.0.0:80", "224.0.0.251:5353",
        "239.255.255.250:1900", "255.255.255.255:67", "[::1]:80", "[::]:80", "[fe80::1]:80", "[fe80::1%wlan0]:80",
        "[fd12:3456::1]:80", "[fc00::1]:80", "[ff02::1]:80", "[::ffff:192.168.0.1]:80", "[64:ff9b::a00:1]:80",
        "not an address", "", "[2001:db8::1", "1.2.3.4", "999.1.1.1:80",
    };
    const char *public[] = {
        "203.0.113.5:443", "8.8.8.8:53", "172.32.0.1:80", "100.128.0.1:80", "11.0.0.1:80", "[2606:4700::1111]:443",
        "[::ffff:8.8.8.8]:443", "[64:ff9b::808:808]:443", "151.101.1.69:6881",
    };
    for (size_t i = 0; i < sizeof(local) / sizeof(local[0]); i++) expect(local[i], 1);
    for (size_t i = 0; i < sizeof(public) / sizeof(public[0]); i++) expect(public[i], 0);
    if (net_scope_is_local(NULL) != 1) {
        fprintf(stderr, "FAIL NULL\n");
        failures++;
    }

    /* Passes: exactly the address and port vouched for, counted, an IPv6 address however it is written. */
    check(net_scope_is_allowed("192.168.1.20:443"), 0, "nothing vouched for yet");
    check(net_scope_allow("192.168.1.20:443"), 0, "allow");
    check(net_scope_is_allowed("192.168.1.20:443"), 1, "the vouched-for address");
    check(net_scope_is_allowed("192.168.1.20:6881"), 0, "another port of it");
    check(net_scope_is_allowed("192.168.1.21:443"), 0, "another address");
    check(net_scope_allow("192.168.1.20:443"), 0, "a second download to it");
    check(net_scope_revoke("192.168.1.20:443"), 0, "the first one ends");
    check(net_scope_is_allowed("192.168.1.20:443"), 1, "still open for the second");
    check(net_scope_revoke("192.168.1.20:443"), 0, "the second one ends");
    check(net_scope_is_allowed("192.168.1.20:443"), 0, "closed when both ended");
    check(net_scope_allow("[fd7a:115c:a1e0::1]:8080"), 0, "allow an IPv6 tailnet address");
    check(net_scope_is_allowed("[fd7a:115c:a1e0:0:0:0:0:1]:8080"), 1, "the same address written out");
    check(net_scope_allow("not words"), -EINVAL, "unreadable");
    check(net_scope_allow("10.0.0.1:99999"), -EINVAL, "a port out of range");
    check(net_scope_is_allowed("fe80::1%wlan0:80"), 0, "a zone is never vouched for");
    printf("%s\n", failures ? "net_scope: FAILED" : "net_scope: ok");
    return failures != 0;
}
