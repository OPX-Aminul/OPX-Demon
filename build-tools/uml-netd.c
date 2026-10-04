/*
 * build-tools/uml-netd.c — OPX-Demon rootless UML network gateway.
 *
 * Problem: the released UML kernel ships only the vector-net driver
 * (CONFIG_UML_NET_VECTOR=y; tap/raw/bess/gre/l2tpv3/fd transports). tap/raw
 * need TUNSETIFF on a fresh interface (CAP_NET_ADMIN — never available to an
 * Android app uid), and no classic eth0=daemon/slirp transport is compiled
 * in, so "eth0=tap,..." could never work rootless.
 *
 * Solution: the kernel connects to us. The vector BESS transport makes the
 * KERNEL a CLIENT of a SOCK_SEQPACKET AF_UNIX socket and exchanges raw
 * Ethernet frames — one seqpacket = one frame, no header (verified against
 * zalexdev/linux-um-arm64: user_init_unix_fds() and build_bess_transport_data()
 * which sets header_size = 0, verify_header = NULL).
 *
 * uml-netd listens on that socket and plays gateway for
 *   guest eth0 (vec0) = 10.0.2.15/24, gateway = 10.0.2.2
 * implementing exactly the L3 surface the rootless engine needs:
 *
 *   1. ARP  — answer every "who has X" with the gateway MAC (proxy-ARP), so
 *             the guest always resolves; gratuitous ARP after connect.
 *   2. ICMP — echo replies for 10.0.2.2 ("ping the host" works).
 *   3. TCP  — relay with an explicit egress policy. A SYN to the gateway
 *             (10.0.2.2) or to 127.0.0.0/8 is relayed to 127.0.0.1:<same
 *             port> on the device: that carries
 *             `usbip attach -r 10.0.2.2 -b <busid>` to the app's USB/IP
 *             server on port 3240. Any other destination leaves the phone
 *             through --egress (default "direct": the daemon runs inside
 *             the app process, so its own sockets already carry the app's
 *             INTERNET permission; "socks" tunnels it through a SOCKS5
 *             proxy instead; "loopback" restores the old gateway-only
 *             behaviour). This is what gives the guest working internet —
 *             unlike QEMU/slirp there is no NAT underneath us.
 *   4. UDP  — DNS (guest -> 10.0.2.2:53) forwarded to --dns (default
 *             10.0.2.3 -> falls back to 8.8.8.8 unless overridden) so apt
 *             resolves inside the guest.
 *
 * Engine boots the guest with:  vec0:transport=bess,dst=<socket>
 * (see RootlessEngine.buildUmlCommand). No root, no /dev/net/tun, no
 * VpnService — an ordinary unprivileged process holds both endpoints.
 *
 *   5. Inbound forward (--forward <hostport>:<guestport>) — the UML engine
 *             has no slirp, so the app's 127.0.0.1:<hostport> listener for
 *             the guest agent (opxdemon-agentd on 10.0.2.15:1050) has no
 *             hostfwd underneath it. The daemon opens that listener itself
 *             and serves every accepted connection by connecting the other
 *             side to the guest through the BESS wire with its own mini TCP
 *             stack — the same machinery as the outbound relay, driven from
 *             the other end.
 *
 * Usage: uml-netd --socket <path> [--dns <ipv4>] [--egress <loopback|direct|socks>]
 *                 [--socks <host:port>] [--forward <hostport>:<guestport>]
 *                 [--forward <hostport>:<guestport> ...] [--verbose]
 */

#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <netdb.h>
#include <fcntl.h>
#include <linux/tcp.h>
#include <netinet/in.h>
#include <netinet/ip.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

/* Guest network constants (mirror RootlessEngine / UmlUsbServer).
 * IPs are kept as WIRE-ORDER byte arrays so memcpy() emits them correctly
 * regardless of host endianness. */
static const uint8_t IP_GUEST[4] = { 10, 0, 2, 15 };
static const uint8_t IP_HOST[4]  = { 10, 0, 2, 2 };

static const uint8_t MAC_GATE[6]  = {0x52, 0x54, 0x00, 0x12, 0x34, 0x02};

static void fwd_logf_(const char *fmt, ...);

/* Destination MAC for every gateway->guest frame.
 *
 * The kernel command line pins vec0 to this address (RootlessEngine emits
 * "mac=" on the vec0 spec), so it starts out correct. It is kept mutable and
 * refreshed from the source address of each frame the kernel sends us: the
 * UML vector driver falls back to eth_hw_addr_random() whenever the ifspec has
 * no mac=, and a frame addressed to a MAC the NIC does not own is dropped
 * without a word — ARP still succeeded, so the guest believed it had a route
 * while no TCP packet ever arrived ("no SYN-ACK after 6 tries"). Learning the
 * real address makes that failure mode impossible even if the two ends drift. */
static uint8_t guest_mac[6] = {0x52, 0x54, 0x00, 0x12, 0x34, 0x15};

/* Record the guest NIC's address from the frame we just received. Only the
 * kernel writes into this socket, so its source address is always the guest. */
static void learn_mac(const uint8_t *smac)
{
    if (smac[0] & 0x01) return;          /* multicast/broadcast source: bogus */
    if (memcmp(smac, guest_mac, 6) == 0) return;
    memcpy(guest_mac, smac, 6);
    fwd_logf_("uml-netd: guest MAC is now %02x:%02x:%02x:%02x:%02x:%02x",
              guest_mac[0], guest_mac[1], guest_mac[2],
              guest_mac[3], guest_mac[4], guest_mac[5]);
}

#define MAX_FRAME   65536
#define MSS         1400
#define RELAY_MAX   48
#define BUF_SZ      (64 * 1024)
#define UDPDNS_MAX  32
#define IDLE_MS     (30L * 60 * 1000)
#define RETX_MS     500
#define WIN_ADV     8192

/* TCP header flags (netinet/tcp.h spells these differently per libc) */
#define F_FIN 0x01
#define F_SYN 0x02
#define F_RST 0x04
#define F_PSH 0x08
#define F_ACK 0x10

/* ── small helpers ─────────────────────────────────────────────────────────── */
static int g_verbose;

/* "<host>:<port>" for --socks; the host may be a literal IPv4 or a name. */
static int parse_socks(const char *arg);

/* Guest internet egress. The gateway/loopback destinations never leave the
 * phone: they are relayed to 127.0.0.1 with the port preserved, which is how
 * `usbip attach -r 10.0.2.2` reaches the app's USB/IP server. */
enum { EGRESS_LOOPBACK = 0, EGRESS_DIRECT = 1, EGRESS_SOCKS = 2 };
static int g_egress = EGRESS_DIRECT;
static uint32_t g_socks_ip;  /* wire-order bytes in memory (memcpy-safe) */
static uint16_t g_socks_port = 1080;
static int g_socks_set;

static const char *egress_name(int e)
{
    switch (e) {
    case EGRESS_LOOPBACK: return "loopback";
    case EGRESS_SOCKS:    return "socks";
    default:              return "direct";
    }
}

/* Addresses the guest believes are "the device itself". */
static int dip_is_device(const uint8_t dip[4])
{
    if (dip[0] == 0x7F) return 1;                    /* 127.0.0.0/8 */
    if (memcmp(dip, IP_HOST, 4) == 0) return 1;      /* the gateway  */
    return 0;
}

static int parse_socks(const char *arg)
{
    const char *colon = strrchr(arg, ':');
    if (!colon || colon == arg) return -1;
    size_t hlen = (size_t)(colon - arg);
    long port = strtol(colon + 1, NULL, 10);
    if (port <= 0 || port > 65535) return -1;

    char host[256];
    if (hlen >= sizeof host) return -1;
    memcpy(host, arg, hlen);
    host[hlen] = '\0';

    struct in_addr a;
    if (inet_pton(AF_INET, host, &a) == 1) {
        memcpy(&g_socks_ip, &a, 4);
    } else {
        struct addrinfo hints, *res = NULL;
        memset(&hints, 0, sizeof hints);
        hints.ai_family = AF_INET;
        hints.ai_socktype = SOCK_STREAM;
        if (getaddrinfo(host, NULL, &hints, &res) != 0 || !res) return -1;
        struct sockaddr_in *sa = (struct sockaddr_in *)res->ai_addr;
        memcpy(&g_socks_ip, &sa->sin_addr, 4);
        freeaddrinfo(res);
    }
    g_socks_port = (uint16_t)port;
    g_socks_set = 1;
    return 0;
}

static uint16_t csum16(const void *b, size_t len)
{
    const uint8_t *p = b;
    uint32_t sum = 0;
    for (size_t i = 0; i < len; i += 2) {
        uint32_t w = (uint32_t)p[i] << 8;
        if (i + 1 < len) w |= p[i + 1];
        sum += w;
    }
    while (sum >> 16) sum = (sum & 0xffff) + (sum >> 16);
    return (uint16_t)~sum;
}

static long now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

/* Always-on log for the agent-forward path: the app cannot see anything else
 * this daemon does, so the bind/established/refused events print regardless
 * of --verbose. */
static void fwd_logf_(const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
    fflush(stderr);
}

static void logf_(const char *fmt, ...)
{
    if (!g_verbose) return;
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
    fflush(stderr);
}

static void set_nonblock(int fd)
{
    int fl = fcntl(fd, F_GETFL, 0);
    if (fl >= 0) fcntl(fd, F_SETFL, fl | O_NONBLOCK);
}

/* ═════════════════════════════ kernel-facing BESS endpoint ═════════════════ */
/* Frame-path counters. Every boot so far failed as "no SYN-ACK", which says
 * only that the guest never answered — not whether the guest ever spoke. The
 * relay can fail in three places (guest never transmits, our frames never
 * reach it, its frames never reach us) and all three look identical from the
 * outside, so the first frames in each direction are logged by name. */
static long g_rx_frames;   /* frames the guest kernel sent us */
static long g_tx_frames;   /* frames we handed the guest kernel */

static int kfd = -1;   /* accepted kernel connection */
static int lfd = -1;   /* listening AF_UNIX SOCK_SEQPACKET socket */
static char sock_path[108];

static int bess_listen(const char *path)
{
    int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_NONBLOCK, 0);
    if (fd < 0) return -1;
    struct sockaddr_un a;
    memset(&a, 0, sizeof a);
    a.sun_family = AF_UNIX;
    if (strlen(path) >= sizeof a.sun_path) {
        close(fd);
        errno = ENAMETOOLONG;
        return -1;
    }
    strcpy(a.sun_path, path);
    unlink(path); /* stale socket from a previous killed boot */
    if (bind(fd, (struct sockaddr *)&a, sizeof a) < 0) {
        int e = errno;
        close(fd);
        errno = e;
        return -1;
    }
    if (listen(fd, 1) < 0) {
        int e = errno;
        close(fd);
        errno = e;
        return -1;
    }
    lfd = fd;
    snprintf(sock_path, sizeof sock_path, "%s", path);
    return 0;
}

/* ═════════════════════════════════ TX to the guest ═════════════════════════ */
static void tx_frame(const void *buf, size_t len)
{
    const uint8_t *f = buf;
    if (kfd < 0 || len == 0 || len > MAX_FRAME) return;
    if (g_tx_frames < 4 && len >= 14)
        fwd_logf_("uml-netd: frame TO the guest #%ld len=%zu eth=%02x%02x"
                  " dst=%02x:%02x:%02x:%02x:%02x:%02x",
                  g_tx_frames + 1, len, f[12], f[13],
                  f[0], f[1], f[2], f[3], f[4], f[5]);
    g_tx_frames++;
    const char *p = buf;
    size_t off = 0;
    while (off < len) {
        ssize_t n = write(kfd, p + off, len - off);
        if (n > 0) {
            off += (size_t)n;
            continue;
        }
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            struct pollfd pf = { .fd = kfd, .events = POLLOUT, .revents = 0 };
            poll(&pf, 1, 100);
            continue;
        }
        return; /* kernel socket is gone; the poll loop will reap it */
    }
}

/* Send an IPv4 packet (header + payload) from the gateway to the guest. */
static void tx_ipv4(uint8_t proto, const void *payload, size_t plen,
                    const uint8_t saddr[4], const uint8_t daddr[4])
{
    static uint8_t out[14 + 20 + 1600];
    if (plen > 1600) return;
    size_t total = 14 + 20 + plen;

    memcpy(out, guest_mac, 6);      /* dst = guest NIC */
    memcpy(out + 6, MAC_GATE, 6);   /* src = gateway */
    out[12] = 0x08;
    out[13] = 0x00;

    uint8_t *ip = out + 14;
    ip[0] = 0x45;                   /* v4, IHL 5 */
    ip[1] = 0;
    ip[2] = (uint8_t)((20 + plen) >> 8);
    ip[3] = (uint8_t)(20 + plen);
    static uint16_t ipid;
    ipid++;
    ip[4] = (uint8_t)(ipid >> 8);
    ip[5] = (uint8_t)ipid;
    ip[6] = ip[7] = 0;              /* no fragmentation */
    ip[8] = 64;
    ip[9] = proto;
    ip[10] = ip[11] = 0;
    memcpy(ip + 12, saddr, 4);
    memcpy(ip + 16, daddr, 4);
    uint16_t cs = csum16(ip, 20);
    ip[10] = (uint8_t)(cs >> 8);
    ip[11] = (uint8_t)cs;

    memcpy(out + 34, payload, plen);
    tx_frame(out, total);
}

/* Gratuitous ARP: "10.0.2.2 is-at gateway MAC". */
static void send_gratuitous_arp(void)
{
    uint8_t a[42];
    memset(a, 0, sizeof a);
    memcpy(a, guest_mac, 6);
    memcpy(a + 6, MAC_GATE, 6);
    a[12] = 0x08;
    a[13] = 0x06;
    a[14] = 0x00; a[15] = 1;        /* Ethernet */
    a[16] = 0x08; a[17] = 0x00;     /* IPv4 */
    a[18] = 6;
    a[19] = 4;
    a[20] = 0x00; a[21] = 1;        /* request (gratuitous) */
    memcpy(a + 22, MAC_GATE, 6);
    memcpy(a + 28, IP_HOST, 4);     /* sender IP */
    memcpy(a + 32, guest_mac, 6);   /* target MAC */
    memcpy(a + 38, IP_HOST, 4);     /* target IP */
    tx_frame(a, sizeof a);
}

/* ═════════════════════════════════════ ARP ═════════════════════════════════ */
static void handle_arp(const uint8_t *p, size_t len, const uint8_t *smac)
{
    if (len < 28) return;
    uint16_t hrd = (uint16_t)((p[0] << 8) | p[1]);
    uint16_t pro = (uint16_t)((p[2] << 8) | p[3]);
    uint16_t op  = (uint16_t)((p[6] << 8) | p[7]);
    if (hrd != 1 || pro != 0x0800 || p[4] != 6 || p[5] != 4 || op != 1) return;

    /* Proxy-ARP everything: the L3 handlers answer what we serve and drop
     * the rest, so claiming extra addresses only speeds the guest up. */
    uint8_t rep[42];
    memset(rep, 0, sizeof rep);
    memcpy(rep, smac, 6);           /* dst = asking guest */
    memcpy(rep + 6, MAC_GATE, 6);
    rep[12] = 0x08;
    rep[13] = 0x06;
    rep[14] = 0x00; rep[15] = 1;
    rep[16] = 0x08; rep[17] = 0x00;
    rep[18] = 6;
    rep[19] = 4;
    rep[20] = 0x00; rep[21] = 2;    /* reply */
    memcpy(rep + 22, MAC_GATE, 6);  /* sender MAC */
    memcpy(rep + 28, p + 24, 4);    /* sender IP = requested IP (tpa) */
    memcpy(rep + 32, smac, 6);      /* target MAC = guest */
    memcpy(rep + 38, p + 14, 4);    /* target IP = spa */
    tx_frame(rep, sizeof rep);
}

/* ═════════════════════════════════════ ICMP ════════════════════════════════ */
static void handle_icmp(const uint8_t *icmp, size_t len,
                        const uint8_t saddr[4], const uint8_t daddr[4])
{
    if (len < 8 || icmp[0] != 8) return;            /* echo request only */
    if (memcmp(daddr, IP_HOST, 4) != 0 && memcmp(daddr, IP_GUEST, 4) != 0)
        return;
    if (len > 1200) len = 1200;

    uint8_t rep[1216];
    memcpy(rep, icmp, len);
    rep[0] = 0;                                     /* echo reply */
    rep[2] = rep[3] = 0;
    uint16_t cs = csum16(rep, len);
    rep[2] = (uint8_t)(cs >> 8);
    rep[3] = (uint8_t)cs;
    tx_ipv4(1, rep, len, daddr, saddr);
}

/* ═════════════════════════════════════ UDP/DNS ═════════════════════════════ */
static uint32_t g_dns_ip; /* host-order value handed to sockaddr_in */

struct udpre {
    int used;
    uint16_t sport;      /* guest source port (host order) */
    int hfd;
    uint8_t client_ip[4]; /* wire order */
};
static struct udpre udpre[UDPDNS_MAX];

static void handle_udp(const uint8_t *udp, size_t len, const uint8_t saddr[4])
{
    if (len < 8) return;
    uint16_t sport = (uint16_t)((udp[0] << 8) | udp[1]);
    uint16_t dport = (uint16_t)((udp[2] << 8) | udp[3]);
    uint16_t ulen  = (uint16_t)((udp[4] << 8) | udp[5]);
    if (ulen < 8 || ulen > len) ulen = (uint16_t)len;
    if (dport != 53 || ulen == 8) return; /* DNS queries only */

    struct udpre *e = NULL;
    for (int i = 0; i < UDPDNS_MAX; i++) {
        if (udpre[i].used && udpre[i].sport == sport) {
            close(udpre[i].hfd);      /* replace an in-flight query */
            e = &udpre[i];
            break;
        }
        if (!e && !udpre[i].used) e = &udpre[i];
    }
    if (!e) return;

    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) return;
    set_nonblock(fd);
    struct sockaddr_in da;
    memset(&da, 0, sizeof da);
    da.sin_family = AF_INET;
    da.sin_addr.s_addr = g_dns_ip;
    da.sin_port = htons(53);
    if (connect(fd, (struct sockaddr *)&da, sizeof da) < 0 ||
        send(fd, udp + 8, ulen - 8, 0) < 0) {
        close(fd);
        return;
    }
    e->used = 1;
    e->sport = sport;
    e->hfd = fd;
    memcpy(e->client_ip, saddr, 4);
}

static void udp_relay_poll(void)
{
    for (int i = 0; i < UDPDNS_MAX; i++) {
        struct udpre *e = &udpre[i];
        if (!e->used) continue;
        uint8_t buf[1400];
        ssize_t n = recv(e->hfd, buf, sizeof buf, MSG_DONTWAIT);
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) continue;
            close(e->hfd);
            e->used = 0;
            continue;
        }
        if (n == 0) {
            close(e->hfd);
            e->used = 0;
            continue;
        }

        uint8_t pkt[8 + 1400];
        pkt[0] = 0x00; pkt[1] = 0x35;                          /* sport 53 */
        pkt[2] = (uint8_t)(e->sport >> 8);
        pkt[3] = (uint8_t)e->sport;
        uint16_t ulen = (uint16_t)(8 + n);
        pkt[4] = (uint8_t)(ulen >> 8);
        pkt[5] = (uint8_t)ulen;
        pkt[6] = pkt[7] = 0;                                   /* UDP csum 0 */
        memcpy(pkt + 8, buf, (size_t)n);
        tx_ipv4(17, pkt, ulen, IP_HOST, e->client_ip);

        close(e->hfd);                                         /* one-shot */
        e->used = 0;
    }
}

/* ═════════════════════════════════════ TCP relay ═══════════════════════════ */
#define SEQ_LT(a, b)   ((int32_t)((uint32_t)(a) - (uint32_t)(b)) < 0)
#define SEQ_LEQ(a, b)  ((int32_t)((uint32_t)(a) - (uint32_t)(b)) <= 0)
#define SEQ_EQ(a, b)   ((uint32_t)(a) == (uint32_t)(b))

struct trelay {
    int used;
    int dead;
    int connecting;        /* host connect() in progress */

    uint16_t gport;        /* guest source port (host order) — lookup key */
    uint16_t dport;        /* guest destination port (host order) */
    uint8_t dip[4];        /* guest destination IP, wire order */
    int egress;            /* EGRESS_* chosen for this relay */
    int hs;                /* 0 = upstream not usable yet (handshake pending) */
    int hfd;               /* host-side socket (see egress) */

    uint32_t rcv_nxt;      /* next expected seq from guest */
    uint32_t snd_nxt;      /* next seq we will use toward the guest */
    uint32_t our_isn;

    uint8_t gbuf[BUF_SZ];  /* guest->host, not yet written to hfd */
    size_t glen;
    uint8_t hbuf[BUF_SZ];  /* host->guest, unacked prefix */
    size_t hlen;
    size_t hsent;          /* bytes of hbuf already transmitted */

    int guest_fin;
    int host_eof;
    int fin_sent;
    int guest_fin_acked;
    long last_tx_ms;
    long created_ms;
    int fwd;               /* 0 = outbound relay; 1 = inbound, SYN in flight;
                            * 2 = inbound, guest side established */
    uint16_t fport;        /* forward: host port the client connected to */
    long syn_retx;         /* forward: SYN retransmit counter (diagnostics) */
};
static struct trelay relays[RELAY_MAX];

static struct trelay *relay_by_gport(uint16_t gport)
{
    for (int i = 0; i < RELAY_MAX; i++)
        if (relays[i].used && relays[i].gport == gport) return &relays[i];
    return NULL;
}

static struct trelay *relay_alloc(uint16_t gport)
{
    for (int i = 0; i < RELAY_MAX; i++) {
        if (!relays[i].used) {
            memset(&relays[i], 0, sizeof relays[i]);
            relays[i].used = 1;
            relays[i].gport = gport;
            relays[i].hfd = -1;
            relays[i].created_ms = now_ms();
            return &relays[i];
        }
    }
    return NULL;
}

static void relay_free(struct trelay *r)
{
    if (r->hfd >= 0) close(r->hfd);
    memset(r, 0, sizeof *r);
}

static uint32_t make_isn(uint16_t gport)
{
    return (uint32_t)time(NULL) * 1000u + (uint32_t)getpid() * 97u
         + (uint32_t)gport * 40503u;
}

/* Send a TCP segment guest-ward with the proper pseudo-header checksum. */
static void tcp_send(struct trelay *r, uint8_t flags,
                     const uint8_t *payload, size_t plen,
                     uint32_t seq, uint32_t ack)
{
    uint8_t seg[20 + MSS];
    if (plen > MSS) plen = MSS;
    memset(seg, 0, sizeof seg);

    /* Outbound relay: sport = the port the guest dialed, dport = the guest's
     * source port. Inbound forward: the daemon IS the initiator, so the
     * segment leaves with sport = our forward gport and dport = the guest
     * service port — the guest answers into the demux key (gport). */
    uint16_t sp = r->fwd ? r->gport : r->dport;
    uint16_t dp = r->fwd ? r->dport : r->gport;
    seg[0] = (uint8_t)(sp >> 8);
    seg[1] = (uint8_t)sp;
    seg[2] = (uint8_t)(dp >> 8);
    seg[3] = (uint8_t)dp;
    seg[4] = (uint8_t)(seq >> 24);
    seg[5] = (uint8_t)(seq >> 16);
    seg[6] = (uint8_t)(seq >> 8);
    seg[7] = (uint8_t)seq;
    seg[8] = (uint8_t)(ack >> 24);
    seg[9] = (uint8_t)(ack >> 16);
    seg[10] = (uint8_t)(ack >> 8);
    seg[11] = (uint8_t)ack;
    /* SYN-ACK carries the 4-byte MSS option -> data offset 6 (24 bytes). */
    seg[12] = (flags & F_SYN) ? 0x60 : 0x50;
    seg[13] = flags;
    seg[14] = (uint8_t)(WIN_ADV >> 8);   /* window */
    seg[15] = (uint8_t)WIN_ADV;
    if (plen) memcpy(seg + 20, payload, plen);

    uint8_t ph[12];
    memcpy(ph, IP_HOST, 4);
    memcpy(ph + 4, IP_GUEST, 4);
    ph[8] = 0;
    ph[9] = 6;
    uint16_t tl = (uint16_t)(20 + plen);
    ph[10] = (uint8_t)(tl >> 8);
    ph[11] = (uint8_t)tl;

    uint8_t cbuf[12 + 20 + MSS];
    size_t total = 12 + 20 + plen;
    memcpy(cbuf, ph, 12);
    memcpy(cbuf + 12, seg, 20 + plen);
    if (total & 1) cbuf[total++] = 0;
    uint16_t cs = csum16(cbuf, total);
    seg[16] = (uint8_t)(cs >> 8);
    seg[17] = (uint8_t)cs;

    tx_ipv4(6, seg, 20 + plen, IP_HOST, IP_GUEST);
    r->last_tx_ms = now_ms();
}

static void relay_send_ack(struct trelay *r)
{
    tcp_send(r, F_ACK, NULL, 0, r->snd_nxt, r->rcv_nxt);
}

static void relay_send_rst(struct trelay *r)
{
    tcp_send(r, F_RST | F_ACK, NULL, 0, r->rcv_nxt, r->snd_nxt);
}

static void relay_connect_host(struct trelay *r)
{
    struct sockaddr_in da;
    memset(&da, 0, sizeof da);
    da.sin_family = AF_INET;
    da.sin_port = htons(r->dport);

    /* Gateway and loopback destinations stay on the device — this is the
     * usbip/USB-IP path. Everything else follows the egress policy. */
    if (g_egress == EGRESS_LOOPBACK || dip_is_device(r->dip)) {
        r->egress = EGRESS_LOOPBACK;
        da.sin_addr.s_addr = htonl(0x7F000001u); /* 127.0.0.1 */
    } else if (g_egress == EGRESS_SOCKS) {
        r->egress = EGRESS_SOCKS;
        da.sin_addr.s_addr = g_socks_ip;
        da.sin_port = htons(g_socks_port);
    } else {
        r->egress = EGRESS_DIRECT;
        memcpy(&da.sin_addr, r->dip, 4);
    }

    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) {
        r->dead = 1;
        return;
    }
    set_nonblock(fd);
    int rc = connect(fd, (struct sockaddr *)&da, sizeof da);
    if (rc != 0 && errno != EINPROGRESS) {
        close(fd);
        r->hfd = -1;
        r->dead = 1;
        return;
    }
    r->hfd = fd;
    /* Stay in the "connecting" state even when the loopback connect() already
     * returned 0: host_pump_all() is the single place that validates the
     * socket, runs the SOCKS5 handshake and flips r->hs. */
    r->connecting = 1;
}

/* ── SOCKS5 CONNECT (RFC 1928) ─────────────────────────────────────────────
 * The proxy always sits on the device (127.0.0.1 by default), so this
 * handshake completes in a couple of milliseconds even though it runs
 * synchronously: a blocking socket with a 5 s cap is simpler and safer than
 * a third state machine in the poll loop. */
static int io_all(int fd, void *buf, size_t len, int write_side)
{
    uint8_t *p = buf;
    size_t done = 0;
    while (done < len) {
        ssize_t n = write_side
            ? write(fd, p + done, len - done)
            : read(fd, p + done, len - done);
        if (n > 0) { done += (size_t)n; continue; }
        if (n < 0 && errno == EINTR) continue;
        return -1;
    }
    return 0;
}

static int socks_connect(struct trelay *r)
{
    struct timeval tv = { 5, 0 };
    setsockopt(r->hfd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    setsockopt(r->hfd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);
    int fl = fcntl(r->hfd, F_GETFL, 0);
    if (fl >= 0) fcntl(r->hfd, F_SETFL, fl & ~O_NONBLOCK);

    int rc = -1;
    uint8_t greet[3] = { 5, 1, 0 };                 /* VER 5, 1 method, none */
    uint8_t rep[2];
    uint8_t req[10];                                /* ATYP 1 = IPv4 */
    uint8_t head[4], addr[4], tail[2];

    if (io_all(r->hfd, greet, sizeof greet, 1) < 0) goto out;
    if (io_all(r->hfd, rep, sizeof rep, 0) < 0) goto out;
    if (rep[0] != 5 || rep[1] != 0) goto out;      /* no "no-auth" offered */

    req[0] = 5; req[1] = 1;                        /* CONNECT */
    req[2] = 0; req[3] = 1;                        /* RSV, ATYP = IPv4 */
    memcpy(req + 4, r->dip, 4);
    req[8] = (uint8_t)(r->dport >> 8);
    req[9] = (uint8_t)r->dport;
    if (io_all(r->hfd, req, sizeof req, 1) < 0) goto out;

    if (io_all(r->hfd, head, sizeof head, 0) < 0) goto out;
    if (head[1] != 0) goto out;                    /* reply code != success */
    if (io_all(r->hfd, addr, sizeof addr, 0) < 0) goto out;
    if (io_all(r->hfd, tail, sizeof tail, 0) < 0) goto out;
    rc = 0;

out:
    if (fl >= 0) fcntl(r->hfd, F_SETFL, fl | O_NONBLOCK);
    return rc;
}

/* guest -> host: flush gbuf into hfd.
 * NOTE: rcv_nxt is owned by tcp_input() — the bytes were already ACKed
 * sequence-wise when they were appended to gbuf; this pump only drains. */
static void pump_to_host(struct trelay *r)
{
    if (r->hfd < 0 || r->connecting || !r->hs) return;
    while (r->glen > 0) {
        ssize_t n = write(r->hfd, r->gbuf, r->glen);
        if (n > 0) {
            memmove(r->gbuf, r->gbuf + n, r->glen - (size_t)n);
            r->glen -= (size_t)n;
            continue;
        }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) break;
        r->dead = 1; /* host side died */
        return;
    }
    if (r->glen == 0 && r->guest_fin && !r->host_eof && r->hfd >= 0) {
        shutdown(r->hfd, SHUT_WR); /* guest closed its write side */
    }
}

/* host -> guest: transmit new data + FIN */
static void pump_to_guest(struct trelay *r)
{
    if (r->hfd < 0 || !r->hs) return; /* upstream not usable yet */
    while (r->hsent < r->hlen && !r->dead) {
        size_t avail = r->hlen - r->hsent;
        size_t chunk = avail > MSS ? MSS : avail;
        tcp_send(r, F_ACK | F_PSH, r->hbuf + r->hsent, chunk,
                 r->snd_nxt, r->rcv_nxt);
        r->hsent += chunk;
        r->snd_nxt += (uint32_t)chunk;
    }
    if (r->hsent == r->hlen && r->host_eof && !r->fin_sent && !r->dead) {
        tcp_send(r, F_ACK | F_FIN, NULL, 0, r->snd_nxt, r->rcv_nxt);
        r->snd_nxt++;          /* FIN consumes one sequence number */
        r->fin_sent = 1;
    }
}

/* guest ACKed a prefix: slide hbuf */
static void relay_on_ack(struct trelay *r, uint32_t una)
{
    uint32_t base = r->snd_nxt - (uint32_t)r->hsent; /* seq of hbuf[0] */
    if (SEQ_LEQ(una, base) || SEQ_LT(r->snd_nxt, una)) return;
    size_t acked = una - base;
    if (acked > r->hlen) acked = r->hlen;
    memmove(r->hbuf, r->hbuf + acked, r->hlen - acked);
    r->hlen -= acked;
    r->hsent = (acked <= r->hsent) ? (r->hsent - acked) : 0;
}

/* Retransmit the first unacked segment when the guest goes quiet on it. */
static void relay_retx(struct trelay *r, long now)
{
    if (r->dead) return;
    /* Inbound forward still waiting for the guest's SYN-ACK: retransmit. */
    if (r->fwd == 1) {
        if (now - r->last_tx_ms < RETX_MS) return;
        tcp_send(r, F_SYN, NULL, 0, r->our_isn, 0);
        r->last_tx_ms = now;
        r->syn_retx++;
        if (r->syn_retx == 6)
            fwd_logf_("uml-netd: forward 10.0.2.15:%u — no SYN-ACK after %ld tries "
                      "(vec0 has no 10.0.2.15 address, or the frame path is dead)",
                      r->dport, r->syn_retx);
        return;
    }
    if (r->hsent == 0) return;
    if (now - r->last_tx_ms < RETX_MS) return;
    size_t chunk = r->hsent > MSS ? MSS : r->hsent;
    uint32_t base = r->snd_nxt - (uint32_t)r->hsent;
    tcp_send(r, F_ACK | F_PSH, r->hbuf, chunk, base, r->rcv_nxt);
    r->last_tx_ms = now;
}

/* ── inbound forward: host listener -> guest service ─────────────────────────
 * The app execs the guest agent through 127.0.0.1:1050 (QEMU provides that
 * with slirp hostfwd; UML has no slirp). With one --forward argument the
 * daemon holds that listener and bridges each accepted TCP connection to
 * 10.0.2.15:<guestport> across the BESS wire. Reuses struct trelay verbatim:
 * the guest end is exactly the relay's guest side and the "host" end is the
 * accepted client fd — the only difference is who initiates.
 *
 * Demux: a forward relay's guest-side source port (r->gport) is what the
 * guest answers to, so it must be unique per connection AND outside the
 * guest's ephemeral source range (Linux default 32768-60999) so an outbound
 * relay can never collide with it. [2000,6000) satisfies both; collisions
 * with an outbound gport are skipped via relay_by_gport. */
static struct fwd {
    int used;
    int lfd;                /* 127.0.0.1:<hostport> listener (non-blocking) */
    uint16_t hport;         /* host-order host port */
    uint16_t gport;         /* host-order guest port (10.0.2.15) */
} fwds[8];

#define FWD_GPORT_MIN 2000
#define FWD_GPORT_MAX 5999

/* SYN attempts before an inbound forward is abandoned. The relay table only
 * holds RELAY_MAX (48) sessions and the app opens a new 127.0.0.1:1050
 * connection every second or two while the guest is still coming up, so a
 * forward that never gets its SYN-ACK MUST give its slot back: fwd_finished()
 * only retires relays the guest actually completed a handshake with, and
 * r->dead is only set once the guest (or the client) says something. A guest
 * that is still booting therefore filled all 48 slots within a minute and left
 * them full forever — the guest could come up healthy minutes later and still
 * be unreachable, because every later connection found no free slot and was
 * closed on accept. 12 x RETX_MS(500) = 6s, twice the 3s it takes to print
 * the "no SYN-ACK" diagnostic, so an ordinary slow handshake is never cut. */
#define FWD_SYN_MAX 12

/* Port channel the UML kernel serves its console on (RootlessEngine passes
 * port=1050; the app and this daemon share that single TCP host port). */
static uint16_t fwd_console_port = 1050;

static int fwd_is_gport(uint16_t gport)
{
    return gport >= FWD_GPORT_MIN && gport <= FWD_GPORT_MAX;
}

static uint16_t fwd_next_gport(void)
{
    static uint16_t n;
    for (int i = 0; i < 4000; i++) {
        uint16_t c = (uint16_t)(FWD_GPORT_MIN + (n++ % 4000));
        if (!relay_by_gport(c)) return c;
    }
    return 0;
}

/* A forward relay is done only when BOTH directions drained and closed —
 * it must never hit the 30-minute idle reap while the agent session lives. */
static int fwd_finished(const struct trelay *r)
{
    return r->fwd == 2 && r->host_eof && r->fin_sent && r->guest_fin
        && r->glen == 0 && r->hlen == r->hsent;
}

static void fwd_setup_listener(struct fwd *f)
{
    f->lfd = socket(AF_INET, SOCK_STREAM, 0);
    if (f->lfd < 0) return;
    int one = 1;
    setsockopt(f->lfd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    struct sockaddr_in sa;
    memset(&sa, 0, sizeof sa);
    sa.sin_family = AF_INET;
    sa.sin_addr.s_addr = htonl(0x7F000001u);
    sa.sin_port = htons(f->hport);
    if (bind(f->lfd, (struct sockaddr *)&sa, sizeof sa) != 0
        || listen(f->lfd, 4) != 0) {
        fwd_logf_("uml-netd: forward 127.0.0.1:%u bind/listen failed (errno=%d)",
              f->hport, errno);
        close(f->lfd);
        f->lfd = -1;
        f->used = 0;
        return;
    }
    set_nonblock(f->lfd); /* fwd_accept_all() drains with accept()==EAGAIN */
}

/* Accept on every forward listener; wire each connection to the guest. */
static void fwd_accept_all(void)
{
    for (int i = 0; i < (int)(sizeof fwds / sizeof fwds[0]); i++) {
        struct fwd *f = &fwds[i];
        if (!f->used || f->lfd < 0) continue;
        for (;;) {
            int cfd = accept(f->lfd, NULL, NULL);
            if (cfd < 0) break;
            set_nonblock(cfd);

            uint16_t gp = fwd_next_gport();
            struct trelay *r = gp ? relay_alloc(gp) : NULL;
            if (!r) { close(cfd); break; }   /* table full / no port */
            r->dport = f->gport;
            memcpy(r->dip, IP_GUEST, 4);
            r->hfd = cfd;
            r->hs = 0;          /* flips once the guest completes the handshake */
            r->connecting = 0;  /* the client fd is usable from the start */
            r->fport = f->hport;
            r->egress = EGRESS_LOOPBACK;      /* never leaves the device */
            r->our_isn = make_isn(gp);
            r->snd_nxt = r->our_isn;
            r->rcv_nxt = 0;
            r->fwd = 1;
            tcp_send(r, F_SYN, NULL, 0, r->snd_nxt, 0);
            r->snd_nxt = r->our_isn + 1;
            fwd_logf_("uml-netd: forward 127.0.0.1:%u -> 10.0.2.15:%u (gport=%u)",
                  f->hport, f->gport, r->gport);
        }
    }
}

/* Inbound TCP segment from the guest. `dip` is the frame's destination IP —
 * the egress decision needs it and it lives in the IP header, not in TCP. */
static void tcp_input(const uint8_t *seg, size_t len, const uint8_t dip[4])
{
    if (len < 20) return;
    uint16_t sport = (uint16_t)((seg[0] << 8) | seg[1]);
    uint16_t dport = (uint16_t)((seg[2] << 8) | seg[3]);
    uint32_t seq   = ((uint32_t)seg[4] << 24) | ((uint32_t)seg[5] << 16)
                   | ((uint32_t)seg[6] << 8) | (uint32_t)seg[7];
    uint32_t ackn  = ((uint32_t)seg[8] << 24) | ((uint32_t)seg[9] << 16)
                   | ((uint32_t)seg[10] << 8) | (uint32_t)seg[11];
    size_t hlen = (size_t)(seg[12] >> 4) * 4;
    uint8_t flags = seg[13];
    if (hlen < 20 || hlen > len) return;
    const uint8_t *payload = seg + hlen;
    size_t plen = len - hlen;

    struct trelay *r = relay_by_gport(sport);
    /* Segments belonging to an inbound forward arrive with the daemon's
     * forward gport in the DESTINATION field (the guest service is the
     * source). The sport lookup cannot see them. */
    if (!r && fwd_is_gport(dport)) r = relay_by_gport(dport);

    if (flags & F_RST) {
        if (r) {
            /* The guest itself refused: with the forward path this means
             * nothing is listening on the guest service (agent dead) —
             * always surface it, it is the one fact the app cannot learn
             * otherwise (its client socket just sees EOF). */
            if (r->fwd)
                fwd_logf_("uml-netd: forward 10.0.2.15:%u REFUSED by guest (RST) "
                          "— nothing listening inside the guest", r->dport);
            relay_free(r); /* closes the client fd too: connect() fails fast */
        }
        return;
    }

    /* A forward relay's SYN answered: complete the 3-way handshake. */
    if (r && r->fwd == 1 && (flags & (F_SYN | F_ACK)) == (F_SYN | F_ACK)
        && SEQ_EQ(ackn, r->snd_nxt)) {
        r->fwd = 2;
        r->hs = 1;
        r->rcv_nxt = seq + 1;
        tcp_send(r, F_ACK, NULL, 0, r->snd_nxt, r->rcv_nxt);
        fwd_logf_("uml-netd: forward to 10.0.2.15:%u established (gport=%u)",
              r->dport, r->gport);
        pump_to_guest(r);
        return;
    }

    if (flags & F_SYN) {
        if (r) {
            if (r->fwd == 1) {         /* guest retransmitting its SYN-ACK */
                tcp_send(r, F_SYN, NULL, 0, r->our_isn, 0);
                r->last_tx_ms = now_ms();
            } else if (!r->fwd) {      /* SYN retransmission: re-send SYN-ACK */
                tcp_send(r, F_SYN | F_ACK, NULL, 0, r->our_isn, r->rcv_nxt);
            }
            return;
        }
        if (dport == 0) return;
        r = relay_alloc(sport);
        if (!r) return; /* table full: guest will retransmit */
        r->dport = dport;
        memcpy(r->dip, dip, 4);
        r->rcv_nxt = seq + 1;
        r->our_isn = make_isn(sport);
        r->snd_nxt = r->our_isn + 1;

        uint8_t mss[4] = { 2, 4, (uint8_t)(MSS >> 8), (uint8_t)MSS };
        tcp_send(r, F_SYN | F_ACK, mss, 4, r->our_isn, r->rcv_nxt);
        relay_connect_host(r);
        if (r->dead) {
            relay_send_rst(r);
            relay_free(r);
        }
        return;
    }

    if (!r) {
        /* Unknown tuple (slot reaped): RST so the guest fails fast. */
        if (plen || (flags & F_FIN)) {
            struct trelay tmp;
            memset(&tmp, 0, sizeof tmp);
            tmp.gport = sport;
            tmp.dport = dport;
            tcp_send(&tmp, F_RST | F_ACK, NULL, 0, seq + (uint32_t)plen, 0);
        }
        return;
    }

    if (flags & F_ACK) relay_on_ack(r, ackn);

    if (plen > 0) {
        if (SEQ_EQ(seq, r->rcv_nxt)) {
            size_t space = sizeof r->gbuf - r->glen;
            size_t take = plen < space ? plen : space;
            if (take > 0) {
                memcpy(r->gbuf + r->glen, payload, take);
                r->glen += take;
                r->rcv_nxt += (uint32_t)take;
            }
            /* surplus bytes dropped; cumulative ACK makes the guest re-send */
        }
        /* out-of-order: drop, the ACK below re-states rcv_nxt */
    }

    if (flags & F_FIN) {
        if (SEQ_LEQ(seq + (uint32_t)plen, r->rcv_nxt)) {
            r->guest_fin = 1;
            uint32_t fin_nxt = seq + (uint32_t)plen + 1;
            if (SEQ_LT(r->rcv_nxt, fin_nxt)) r->rcv_nxt = fin_nxt;
        }
    }

    pump_to_host(r);

    int need_ack = (plen > 0) || (flags & F_FIN) || r->guest_fin;
    if (!need_ack && (flags & F_ACK) && r->hsent == r->hlen) need_ack = 1;
    if (need_ack && !r->dead) relay_send_ack(r);

    if (r->dead) {
        relay_send_rst(r);
        relay_free(r);
        return;
    }

    pump_to_guest(r);
}

/* (RST on refused connect is sent directly from host_pump_all.) */

/* ═══════════════════════════ host-side socket pumping ══════════════════════ */
static void host_pump_all(void)
{
    struct pollfd pfds[RELAY_MAX];
    struct trelay *map[RELAY_MAX];
    int n = 0;

    for (int i = 0; i < RELAY_MAX; i++) {
        struct trelay *r = &relays[i];
        if (!r->used || r->hfd < 0) continue;
        pfds[n].fd = r->hfd;
        pfds[n].events = (short)(r->connecting ? POLLOUT : POLLIN);
        pfds[n].revents = 0;
        map[n] = r;
        n++;
    }
    if (n == 0) return;
    if (poll(pfds, (nfds_t)n, 0) <= 0) return;

    for (int i = 0; i < n; i++) {
        struct trelay *r = map[i];
        short rev = pfds[i].revents;
        if (!r->used) continue;

        if (r->connecting && (rev & (POLLOUT | POLLERR | POLLHUP))) {
            int err = 0;
            socklen_t el = sizeof err;
            getsockopt(r->hfd, SOL_SOCKET, SO_ERROR, &err, &el);
            r->connecting = 0;
            if (err != 0) {
                logf_("uml-netd: %s connect to %u.%u.%u.%u:%u failed (errno=%d)",
                      egress_name(r->egress), r->dip[0], r->dip[1], r->dip[2],
                      r->dip[3], r->dport, err);
                /* Refused: tell the guest immediately so connect() fails fast
                 * instead of waiting for its own retransmit timeouts. */
                tcp_send(r, F_RST | F_ACK, NULL, 0, r->rcv_nxt, r->snd_nxt);
                relay_free(r);
                continue;
            }
            if (r->egress == EGRESS_SOCKS && socks_connect(r) < 0) {
                logf_("uml-netd: SOCKS5 CONNECT to %u.%u.%u.%u:%u refused",
                      r->dip[0], r->dip[1], r->dip[2], r->dip[3], r->dport);
                tcp_send(r, F_RST | F_ACK, NULL, 0, r->rcv_nxt, r->snd_nxt);
                relay_free(r);
                continue;
            }
            r->hs = 1;
            if (r->egress == EGRESS_LOOPBACK) {
                logf_("uml-netd: relay %u -> 127.0.0.1:%u established",
                      r->gport, r->dport);
            } else {
                logf_("uml-netd: relay %u -> %u.%u.%u.%u:%u established (%s)",
                      r->gport, r->dip[0], r->dip[1], r->dip[2], r->dip[3],
                      r->dport, egress_name(r->egress));
            }
            pump_to_host(r);
            pump_to_guest(r);
            continue;
        }

        if (rev & (POLLIN | POLLHUP | POLLERR)) {
            if (!r->hs) {
                /* A forwarded client that gives up before the guest
                 * completes the handshake must free its slot. */
                if (r->fwd && (rev & POLLHUP)) r->dead = 1;
                continue;
            }
            for (;;) {
                size_t space = sizeof r->hbuf - r->hlen;
                if (space == 0) break;
                ssize_t rd = read(r->hfd, r->hbuf + r->hlen, space);
                if (rd > 0) {
                    r->hlen += (size_t)rd;
                    continue;
                }
                if (rd == 0) {
                    r->host_eof = 1;
                    break;
                }
                if (errno == EINTR) continue;
                if (errno == EAGAIN || errno == EWOULDBLOCK) break;
                r->dead = 1;
                break;
            }
            if (!r->dead && (r->hlen > r->hsent || (r->host_eof && !r->fin_sent)))
                pump_to_guest(r);
            if ((rev & (POLLERR | POLLHUP)) && r->hlen == r->hsent
                && !r->host_eof && !r->fin_sent)
                r->dead = 1; /* host closed with nothing pending */
        }
    }
}

/* ═════════════════════════════════════ main ════════════════════════════════ */
int main(int argc, char **argv)
{
    signal(SIGPIPE, SIG_IGN);
    /*
     * Lifecycle: the daemon lives exactly as long as the kernel connection.
     * - UML dies        -> kfd EOF -> the daemon exits and unlinks its socket.
     * - The app dies    -> the kernel dies too -> same EOF -> same cleanup.
     * - The app stops the VM -> teardownRunning() destroys us explicitly.
     * (PR_SET_PDEATHSIG is deliberately NOT used: Linux signals it when the
     * *thread* that forked us exits, and boot threads are one-shot.)
     */

    g_dns_ip = 0x08080808u; // 8.8.8.8 (host-order value; passed to inet-style API)

    const char *path = NULL;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--socket") && i + 1 < argc) {
            path = argv[++i];
        } else if (!strcmp(argv[i], "--dns") && i + 1 < argc) {
            struct in_addr a;
            if (inet_pton(AF_INET, argv[++i], &a) == 1) g_dns_ip = a.s_addr;
        } else if (!strcmp(argv[i], "--egress") && i + 1 < argc) {
            const char *m = argv[++i];
            if (!strcmp(m, "loopback")) g_egress = EGRESS_LOOPBACK;
            else if (!strcmp(m, "direct")) g_egress = EGRESS_DIRECT;
            else if (!strcmp(m, "socks")) g_egress = EGRESS_SOCKS;
            else {
                fprintf(stderr, "uml-netd: --egress must be "
                                "loopback|direct|socks\n");
                return 2;
            }
        } else if (!strcmp(argv[i], "--socks") && i + 1 < argc) {
            if (parse_socks(argv[++i]) < 0) {
                fprintf(stderr, "uml-netd: --socks expects <host>:<port>\n");
                return 2;
            }
            g_egress = EGRESS_SOCKS;
        } else if (!strcmp(argv[i], "--forward") && i + 1 < argc) {
            /* --forward <hostport>:<guestport>: serve the guest service on
             * 127.0.0.1:<hostport>. The agent channel (1050:1050) is what
             * replaces QEMU's slirp hostfwd on the UML engine. */
            const char *spec = argv[++i];
            const char *colon = strchr(spec, ':');
            if (!colon || colon == spec || colon[1] == '\0') {
                fprintf(stderr, "uml-netd: --forward expects <hostport>:<guestport>\n");
                return 2;
            }
            struct fwd *slot = NULL;
            for (int j = 0; j < (int)(sizeof fwds / sizeof fwds[0]); j++)
                if (!fwds[j].used) { slot = &fwds[j]; break; }
            if (!slot) {
                fprintf(stderr, "uml-netd: too many --forward entries\n");
                return 2;
            }
            memset(slot, 0, sizeof *slot);
            slot->used = 1;
            slot->lfd = -1;
            slot->hport = (uint16_t)atoi(spec);
            slot->gport = (uint16_t)atoi(colon + 1);
            if (slot->hport == 0 || slot->gport == 0) {
                fprintf(stderr, "uml-netd: --forward expects <hostport>:<guestport>\n");
                return 2;
            }
        } else if (!strcmp(argv[i], "--verbose")) {
            g_verbose = 1;
        } else {
            fprintf(stderr,
                    "usage: uml-netd --socket <path> [--dns <ipv4>] "
                    "[--egress <loopback|direct|socks>] [--socks <host:port>] "
                    "[--forward <hostport>:<guestport>] [--verbose]\n");
            return 2;
        }
    }
    if (!path) {
        fprintf(stderr, "usage: uml-netd --socket <path>\n");
        return 2;
    }
    if (g_egress == EGRESS_SOCKS && !g_socks_set) {
        fprintf(stderr, "uml-netd: --egress socks needs --socks <host:port>\n");
        return 2;
    }

    if (bess_listen(path) < 0) {
        fprintf(stderr, "uml-netd: cannot listen on %s: %s\n", path, strerror(errno));
        return 1;
    }
    {
        char pidpath[512];
        snprintf(pidpath, sizeof pidpath, "%s.pid", path);
        FILE *pf = fopen(pidpath, "w");
        if (pf) {
            fprintf(pf, "%ld\n", (long)getpid());
            fclose(pf);
        }
    }
    for (int j = 0; j < (int)(sizeof fwds / sizeof fwds[0]); j++)
        if (fwds[j].used && fwds[j].lfd < 0) fwd_setup_listener(&fwds[j]);
    logf_("uml-netd: listening on %s (dns=%u.%u.%u.%u egress=%s%s)", path,
          g_dns_ip & 0xFF, (g_dns_ip >> 8) & 0xFF,
          (g_dns_ip >> 16) & 0xFF, (g_dns_ip >> 24) & 0xFF,
          egress_name(g_egress),
          g_socks_set ? "/socks-proxy" : "");
    for (int j = 0; j < (int)(sizeof fwds / sizeof fwds[0]); j++)
        if (fwds[j].used && fwds[j].lfd >= 0)
            logf_("uml-netd: forwarding 127.0.0.1:%u -> 10.0.2.15:%u",
                  fwds[j].hport, fwds[j].gport);

    long last_gratuitous = 0;
    long last_activity = now_ms();
    long last_report = now_ms();      /* frame-counter heartbeat beat */
    long last_rx_report = -1, last_tx_report = -1;
    long last_console = 0;        /* 10 s console watchdog beat */
    int console_connected = 0;    /* guest's tty0 shell dialed in */
    const long IDLE_SPAWN_MS = 5L * 60 * 1000; /* never connected: give up */

    for (;;) {
        struct pollfd pfds[2];
        int npfd = 0;
        pfds[npfd].fd = lfd;
        pfds[npfd].events = POLLIN;
        pfds[npfd].revents = 0;
        npfd++;
        fwd_accept_all();
        if (kfd >= 0) {
            pfds[npfd].fd = kfd;
            pfds[npfd].events = POLLIN;
            pfds[npfd].revents = 0;
            npfd++;
        }

        int rc = poll(pfds, (nfds_t)npfd, 100);
        if (rc < 0 && errno != EINTR) break;
        long now = now_ms();

        /* nobody ever connected — spawned without a boot following */
        if (kfd < 0 && now - last_activity > IDLE_SPAWN_MS) {
            fwd_logf_("uml-netd: no kernel ever connected — exiting");
            break;
        }

        /* kernel connecting */
        if ((pfds[0].revents & POLLIN)) {
            int cfd = accept(lfd, NULL, NULL);
            if (cfd >= 0) {
                set_nonblock(cfd);
                if (kfd >= 0) close(kfd);
                kfd = cfd;
                fwd_logf_("uml-netd: kernel connected (BESS)");
                last_activity = now;
                last_gratuitous = now;
                send_gratuitous_arp();
            }
        }

        /* frames from the kernel */
        if (kfd >= 0 && npfd >= 2 && (pfds[1].revents & (POLLIN | POLLHUP | POLLERR))) {
            static uint8_t frame[MAX_FRAME];
            for (;;) {
                ssize_t n = read(kfd, frame, sizeof frame);
                if (n < 0) {
                    if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)
                        break;
                    /* fallthrough: fatal */
                    n = 0;
                }
                if (n <= 0) {
                    /* A disconnect is not fatal by itself: Android freezes and
                     * thaws the whole app process group (SIGSTOP/SIGCONT), and
                     * during the freeze the SEQPACKET peer can drop. The
                     * kernel always redials after a thaw; exiting here would
                     * also close the forward listeners and make the app's
                     * agent dial fail with ECONNREFUSED mid-boot. Keep the
                     * daemon and its listeners up; the 5 min never-connected
                     * idle timer below is the real give-up path. */
                    fwd_logf_("uml-netd: kernel disconnected (n=%zd errno=%d) — "
                              "waiting for it to redial", n, errno);
                    close(kfd);
                    kfd = -1;
                    for (int i = 0; i < RELAY_MAX; i++)
                        if (relays[i].used) relay_free(&relays[i]);
                    last_activity = now;
                    break;
                }
                last_activity = now;
                g_rx_frames++;
                if (g_rx_frames <= 4 && n >= 14)
                    fwd_logf_("uml-netd: frame FROM the guest #%ld len=%zd"
                              " eth=%02x%02x src=%02x:%02x:%02x:%02x:%02x:%02x",
                              g_rx_frames, n, frame[12], frame[13],
                              frame[6], frame[7], frame[8],
                              frame[9], frame[10], frame[11]);
                if (n < 14) continue;

                /* Every frame arriving on this socket came from the guest NIC,
                 * so its source address is the one we must address replies to. */
                learn_mac(frame + 6);

                uint16_t et = (uint16_t)((frame[12] << 8) | frame[13]);
                if (et == 0x0806) {
                    handle_arp(frame + 14, (size_t)n - 14, frame + 6);
                    continue;
                }
                if (et != 0x0800) continue;

                size_t iplen = (size_t)n - 14;
                if (iplen < 20) continue;
                size_t ihl = (size_t)(frame[14] & 0x0F) * 4;
                if (ihl < 20 || ihl > iplen) continue;
                uint16_t frag = (uint16_t)(((frame[14 + 6] << 8) | frame[14 + 7])
                                           & 0x3FFF);
                if (frag != 0) continue; /* no fragment reassembly */

                uint8_t proto = frame[14 + 9];
                const uint8_t *l4 = frame + 14 + ihl;
                size_t l4len = iplen - ihl;

                if (proto == 1) {
                    handle_icmp(l4, l4len, frame + 14 + 12, frame + 14 + 16);
                } else if (proto == 17) {
                    handle_udp(l4, l4len, frame + 14 + 12);
                } else if (proto == 6) {
                    tcp_input(l4, l4len, frame + 14 + 16);
                }
            }
        }

        /* host-side sockets */
        host_pump_all();

        /* Frame-path heartbeat: only when a counter actually moved, so a
         * healthy boot stays quiet and a dead path reports "0 / 0" instead of
         * nothing at all. */
        if (kfd >= 0 && now - last_report >= 15000) {
            last_report = now;
            if (g_rx_frames != last_rx_report || g_tx_frames != last_tx_report) {
                last_rx_report = g_rx_frames;
                last_tx_report = g_tx_frames;
                fwd_logf_("uml-netd: frame counters — guest->host %ld,"
                          " host->guest %ld, guest MAC"
                          " %02x:%02x:%02x:%02x:%02x:%02x",
                          g_rx_frames, g_tx_frames,
                          guest_mac[0], guest_mac[1], guest_mac[2],
                          guest_mac[3], guest_mac[4], guest_mac[5]);
            }
        }

        /* Console watchdog: after boot settles (t>60 s, agent still silent)
         * the engine never opens its console — that is exactly when the app
         * must. Dialing it is idempotent (raw mode is restored on close),
         * and the app stays out of the way until the agent had its chance. */
        if (kfd >= 0 && !console_connected && now - last_activity > 60000
            && now - last_console >= 10000) {
            last_console = now;
            int cfd = socket(AF_INET, SOCK_STREAM, 0);
            if (cfd >= 0) {
                struct sockaddr_in ca;
                memset(&ca, 0, sizeof ca);
                ca.sin_family = AF_INET;
                ca.sin_port = htons(fwd_console_port);
                ca.sin_addr.s_addr = htonl(0x7F000001u);
                if (connect(cfd, (struct sockaddr *)&ca, sizeof ca) == 0) {
                    console_connected = 1;
                    close(cfd);
                    fwd_logf_("uml-netd: console watchdog dialed 127.0.0.1:%u "
                              "(agent silent, handing the guest tty to the app)",
                              fwd_console_port);
                } else {
                    close(cfd);
                }
            }
        }

        /* DNS responses */
        udp_relay_poll();

        /* retransmits + reaping */
        for (int i = 0; i < RELAY_MAX; i++) {
            struct trelay *r = &relays[i];
            if (!r->used) continue;
            if (r->fwd) {
                /* A forwarded agent connection lives as long as the session;
                 * free it only when both directions drained and closed (or
                 * one side gave up while the handshake was in flight).
                 * A handshake that never completes is the third case: without
                 * the give-up below its slot is never returned (see
                 * FWD_SYN_MAX). */
                if (r->fwd == 1 && r->syn_retx > FWD_SYN_MAX) {
                    fwd_logf_("uml-netd: forward 127.0.0.1:%u -> 10.0.2.15:%u"
                              " (gport=%u) abandoned after %ld SYN attempts"
                              " — the guest never answered, releasing the slot",
                              r->fport, r->dport, r->gport, r->syn_retx);
                    r->dead = 1;
                }
                if (r->dead || fwd_finished(r)) {
                    relay_free(r);
                    continue;
                }
                relay_retx(r, now);
                continue;
            }
            if (now - r->created_ms > IDLE_MS) {
                relay_free(r);
                continue;
            }
            relay_retx(r, now);
        }

        /* gratuitous ARP for the first seconds after a kernel connect */
        if (kfd >= 0 && last_gratuitous && now - last_gratuitous < 20000
            && now - last_gratuitous >= 3000) {
            send_gratuitous_arp();
            last_gratuitous += 3000;
        }
    }

    if (kfd >= 0) close(kfd);
    if (lfd >= 0) close(lfd);
    unlink(sock_path);
    {
        char pidpath[512];
        snprintf(pidpath, sizeof pidpath, "%s.pid", sock_path);
        unlink(pidpath);
    }
    return 0;
}
