/*
 * Which addresses the engine may connect to (docs/design.md, "Network"): the internet, never the device itself or the
 * network it is on. Pure, so it is tested on the build machine (net_scope_test.c).
 */
#ifndef ARIA2ENGINE_NET_SCOPE_H
#define ARIA2ENGINE_NET_SCOPE_H

/*
 * 1 when words, an address as the sandbox broker gives it ("203.0.113.5:443", "[2001:db8::1]:80"), is on the device
 * or the local network: loopback, private, shared (carrier NAT), link-local, multicast, reserved or unspecified, also
 * as an IPv4-mapped or NAT64 IPv6 address. 1 as well for anything it cannot read, so a doubt refuses. 0 for a public
 * address.
 */
int net_scope_is_local(const char *words);

#endif
