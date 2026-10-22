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

/*
 * The local addresses the host has vouched for: an HTTP download it walked itself to a server on the device's own
 * network (a NAS, a tailnet machine). A connect to exactly one of them is let through; every other local address stays
 * refused, so a torrent's peers never reach the network. Each pass is counted: as many revokes as allows remove it.
 * Thread-safe. Returns 0, or a negative errno (-EINVAL for words it cannot read, -ENOSPC when the list is full).
 */
int net_scope_allow(const char *words);
int net_scope_revoke(const char *words);

/* 1 when words names an address and port the host has vouched for. */
int net_scope_is_allowed(const char *words);

#endif
