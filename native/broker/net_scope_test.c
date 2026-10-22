/* net_scope.c on the build machine: cc -Wall -Werror net_scope.c net_scope_test.c && ./a.out */
#include "net_scope.h"

#include <stdio.h>

static int failures;

static void expect(const char *words, int local) {
    if (net_scope_is_local(words) != local) {
        fprintf(stderr, "FAIL %s: expected %s\n", words, local ? "local" : "public");
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
    printf("%s\n", failures ? "net_scope: FAILED" : "net_scope: ok");
    return failures != 0;
}
