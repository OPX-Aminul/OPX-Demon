#!/usr/bin/env python3
"""
build-tools/test-uml-netd.py — functional harness for build-tools/uml-netd.c.

Simulates the UML kernel's vector BESS transport (SOCK_SEQPACKET AF_UNIX
client, one raw Ethernet frame per packet) and asserts that uml-netd:

  1. answers ARP for 10.0.2.2 with the gateway MAC,
  2. answers ICMP echo requests,
  3. completes a TCP 3-way handshake to a host loopback listener and relays
     data both ways with correct sequence/ACK accounting and checksums,
  4. sends RST when the host-side port is refused,
  5. relays UDP DNS to the resolver given via --dns,
  6. sends a non-gateway destination straight out to that address
     (--egress direct, the guest-internet path),
  7. tunnels a non-gateway destination through a SOCKS5 proxy
     (--socks) and relays payload both ways after the handshake,
  8. still relays the gateway (10.0.2.2) to 127.0.0.1 while --socks is on,
  9. refuses to start with --egress socks and no --socks.

Run:  python3 build-tools/test-uml-netd.py
"""

import os
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time

SOCK = tempfile.mktemp(prefix="uml-netd-test-", suffix=".sock")
SOCK2 = tempfile.mktemp(prefix="uml-netd-test2-", suffix=".sock")
SOCK3 = tempfile.mktemp(prefix="uml-netd-test3-", suffix=".sock")
NETD = os.path.join(os.path.dirname(__file__), "..", "build-tools", "uml-netd")
NETD = os.path.abspath(NETD)

MAC_GATE = bytes([0x52, 0x54, 0x00, 0x12, 0x34, 0x02])
MAC_GUEST = bytes([0x52, 0x54, 0x00, 0x12, 0x34, 0x15])
IP_GUEST = bytes([10, 0, 2, 15])
IP_HOST = bytes([10, 0, 2, 2])
IP_DNS = bytes([127, 0, 0, 1])

PASS = 0
FAIL = 0


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  PASS  {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name}  {detail}")


def csum(data: bytes) -> int:
    if len(data) % 2:
        data += b"\0"
    s = 0
    for i in range(0, len(data), 2):
        s += (data[i] << 8) + data[i + 1]
    while s >> 16:
        s = (s & 0xFFFF) + (s >> 16)
    return (~s) & 0xFFFF


def ipv4(proto, payload, src, dst):
    total = 20 + len(payload)
    hdr = struct.pack(">BBHHHBBH4s4s", 0x45, 0, total, 0x1234, 0, 64, proto, 0, src, dst)
    hdr = hdr[:10] + struct.pack(">H", csum(hdr)) + hdr[12:]
    return hdr + payload


def eth(payload, ethertype, dst=MAC_GATE):
    return dst + MAC_GUEST + struct.pack(">H", ethertype) + payload


def ip_csum_ok(ip: bytes) -> bool:
    return csum(ip[:20]) == 0


def tcp_seg(sport, dport, seq, ack, flags, payload=b"", window=8192, dst=IP_HOST):
    # full 20-byte TCP header: ports, seq, ack, offset/flags, window, csum, urgent
    hdr = struct.pack(">HHIIBBHHH", sport, dport, seq, ack, 0x50, flags, window, 0, 0)
    ph = IP_GUEST + dst + struct.pack(">BBH", 0, 6, 20 + len(payload))
    cs = csum(ph + hdr + payload)
    hdr = hdr[:16] + struct.pack(">H", cs) + hdr[18:]
    return hdr + payload


def tcp_csum_ok(seg: bytes) -> bool:
    if len(seg) < 20:
        return False
    ph = IP_HOST + IP_GUEST + struct.pack(">BBH", 0, 6, len(seg))
    return csum(ph + seg) == 0


class Kernel:
    """The fake UML kernel: a BESS seqpacket client."""

    def __init__(self, path=SOCK):
        self.s = socket.socket(socket.AF_UNIX, socket.SOCK_SEQPACKET)
        self.s.settimeout(2.0)
        self.s.connect(path)

    def send(self, frame: bytes):
        self.s.send(frame)

    def recv(self, timeout=2.0):
        self.s.settimeout(timeout)
        try:
            return self.s.recv(65536)
        except socket.timeout:
            return None

    def drain(self, secs=0.3):
        end = time.time() + secs
        out = []
        while time.time() < end:
            f = self.recv(timeout=max(0.05, end - time.time()))
            if f is None:
                break
            out.append(f)
        return out

    def close(self):
        self.s.close()


def parse_ip(frame):
    assert frame[12:14] == b"\x08\x00", f"not IPv4: ethertype {frame[12:14].hex()}"
    ip = frame[14:]
    assert ip_csum_ok(ip), "bad IP checksum"
    ihl = (ip[0] & 0x0F) * 4
    proto = ip[9]
    src, dst = ip[12:16], ip[16:20]
    return proto, src, dst, ip[ihl:]


def wait_frame(k, predicate, tries=10):
    for _ in range(tries):
        f = k.recv(timeout=1.0)
        if f is None:
            return None
        if predicate(f):
            return f
    return None


def is_synack(f, dport):
    try:
        proto, src, dst, l4 = parse_ip(f)
        return proto == 6 and l4[13] & 0x12 == 0x12 and l4[0:2] == struct.pack(">H", dport)
    except Exception:
        return False


def is_psh(f):
    try:
        proto, src, dst, l4 = parse_ip(f)
        return proto == 6 and l4[13] & 0x08 and len(l4) > 20
    except Exception:
        return False


def local_ipv4():
    """A routable address of this host, for the direct-egress test."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 53))
        ip = s.getsockname()[0]
    except OSError:
        return None
    finally:
        s.close()
    if ip.startswith("127.") or ip == "10.0.2.2":
        return None
    return ip


def relay_exchange(k, dst_ip, dport, sport, payload, tries=12):
    """3-way handshake + one data round trip to an arbitrary destination."""
    seq = 1000
    k.send(eth(ipv4(6, tcp_seg(sport, dport, seq, 0, 0x02, dst=dst_ip), IP_GUEST, dst_ip), 0x0800))
    f = wait_frame(k, lambda fr: is_synack(fr, dport), tries=tries)
    if f is None:
        return None
    _, _, _, l4 = parse_ip(f)
    isn = struct.unpack(">I", l4[4:8])[0]
    our_seq, rcv_nxt = seq + 1, isn + 1
    k.send(eth(ipv4(6, tcp_seg(sport, dport, our_seq, rcv_nxt, 0x10, dst=dst_ip), IP_GUEST, dst_ip), 0x0800))
    k.send(eth(ipv4(6, tcp_seg(sport, dport, our_seq, rcv_nxt, 0x18, payload,
                              dst=dst_ip), IP_GUEST, dst_ip), 0x0800))
    f = wait_frame(k, is_psh, tries=tries)
    if f is None:
        return None
    _, _, _, l4 = parse_ip(f)
    data = l4[(l4[12] >> 4) * 4:]
    rseq = struct.unpack(">I", l4[4:8])[0]
    k.send(eth(ipv4(6, tcp_seg(sport, dport, our_seq + len(payload),
                               rseq + len(data), 0x10, dst=dst_ip), IP_GUEST, dst_ip), 0x0800))
    return data


def main():
    print(f"starting {NETD} on {SOCK}")
    proc = subprocess.Popen(
        [NETD, "--socket", SOCK, "--dns", "127.0.0.1", "--verbose"],
        stderr=subprocess.PIPE,
    )
    time.sleep(0.3)
    if proc.poll() is not None:
        print("uml-netd died immediately:", proc.stderr.read().decode())
        return 1

    try:
        # ── 1. ARP ──────────────────────────────────────────────────────────
        print("[1] ARP")
        k = Kernel()
        arp_req = struct.pack(
            ">HHBBH", 1, 0x0800, 6, 4, 1
        ) + MAC_GUEST + IP_GUEST + b"\0" * 6 + IP_HOST
        k.send(eth(arp_req, 0x0806))

        def is_arp_reply(f):
            return f[12:14] == b"\x08\x06" and f[20:22] == b"\x00\x02"

        f = wait_frame(k, is_arp_reply)
        check("ARP reply arrives", f is not None)
        if f:
            check("ARP sender MAC = gateway", f[22:28] == MAC_GATE)
            check("ARP sender IP = 10.0.2.2", f[28:32] == IP_HOST)
            check("ARP reply targeted at guest", f[0:6] == MAC_GUEST)

        # ── 2. ICMP echo ────────────────────────────────────────────────────
        print("[2] ICMP echo")
        body = struct.pack(">BBHHH", 8, 0, 0, 0x4321, 1) + b"opxdemon"
        body = body[:2] + struct.pack(">H", csum(body)) + body[4:]
        k.send(eth(ipv4(1, body, IP_GUEST, IP_HOST), 0x0800))

        def is_icmp_reply(f):
            try:
                proto, src, dst, l4 = parse_ip(f)
                return proto == 1 and src == IP_HOST and dst == IP_GUEST and l4[0] == 0
            except Exception:
                return False

        f = wait_frame(k, is_icmp_reply)
        check("ICMP echo reply arrives", f is not None)
        if f:
            proto, src, dst, l4 = parse_ip(f)
            check("ICMP payload matches", l4[8:] == b"opxdemon")
            check("ICMP checksum ok", csum(l4) == 0)

        # ── 3. TCP relay ────────────────────────────────────────────────────
        print("[3] TCP relay (echo server on 127.0.0.1:39999)")
        srv = socket.socket()
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind(("127.0.0.1", 39999))
        srv.listen(4)
        got = {}

        def echo_server():
            c, _ = srv.accept()
            data = c.recv(4096)
            got["data"] = data
            c.sendall(b"ECHO:" + data)
            time.sleep(0.1)
            c.close()

        th = threading.Thread(target=echo_server, daemon=True)
        th.start()

        sport, dport, seq = 55555, 39999, 1000
        k.send(eth(ipv4(6, tcp_seg(sport, dport, seq, 0, 0x02), IP_GUEST, IP_HOST), 0x0800))

        def is_synack(f):
            try:
                proto, src, dst, l4 = parse_ip(f)
                return proto == 6 and l4[13] & 0x12 == 0x12 and l4[0:2] == struct.pack(">H", dport)
            except Exception:
                return False

        f = wait_frame(k, is_synack)
        check("SYN-ACK arrives", f is not None)
        if not f:
            return 1
        proto, src, dst, l4 = parse_ip(f)
        check("SYN-ACK checksum ok", tcp_csum_ok(l4))
        isn = struct.unpack(">I", l4[4:8])[0]
        check("SYN-ACK acks our SYN", struct.unpack(">I", l4[8:12])[0] == seq + 1)
        check("SYN-ACK MSS option present", l4[12] >> 4 == 6 and l4[20:22] == b"\x02\x04")
        rcv_nxt = isn + 1
        our_seq = seq + 1
        k.send(eth(ipv4(6, tcp_seg(sport, dport, our_seq, rcv_nxt, 0x10), IP_GUEST, IP_HOST), 0x0800))
        time.sleep(0.2)  # let the daemon's host connect() land
        th.join(timeout=2)

        payload = b"hello-uml-netd"
        k.send(eth(ipv4(6, tcp_seg(sport, dport, our_seq, rcv_nxt, 0x18, payload), IP_GUEST, IP_HOST), 0x0800))

        # daemon should ACK our data
        def is_ack(f):
            try:
                proto, src, dst, l4 = parse_ip(f)
                return (
                    proto == 6
                    and l4[13] == 0x10
                    and struct.unpack(">I", l4[8:12])[0] == our_seq + len(payload)
                )
            except Exception:
                return False

        f = wait_frame(k, is_ack)
        check("data ACKed by gateway", f is not None)
        if f:
            proto, src, dst, l4 = parse_ip(f)
            check("ACK checksum ok", tcp_csum_ok(l4))
        check("host server got the payload", got.get("data") == payload)

        # echo comes back: ECHO:hello-uml-netd
        expect = b"ECHO:" + payload

        def is_psh(f):
            try:
                proto, src, dst, l4 = parse_ip(f)
                return proto == 6 and l4[13] & 0x08 and len(l4) > 20
            except Exception:
                return False

        f = wait_frame(k, is_psh)
        check("echo data relayed back", f is not None)
        if f:
            proto, src, dst, l4 = parse_ip(f)
            check("relay checksum ok", tcp_csum_ok(l4))
            plen = len(l4) - (l4[12] >> 4) * 4
            data = l4[(l4[12] >> 4) * 4:]
            rseq = struct.unpack(">I", l4[4:8])[0]
            check("echo payload matches", data == expect, f"{data!r}")
            check("echo seq = SYN-ACK seq", rseq == isn + 1)
            # ACK the echoed data
            k.send(eth(ipv4(6, tcp_seg(sport, dport, our_seq + len(payload),
                                       rseq + len(data), 0x10), IP_GUEST, IP_HOST), 0x0800))

        # FIN from guest, expect FIN back after host EOF
        fin_seq = our_seq + len(payload)
        k.send(eth(ipv4(6, tcp_seg(sport, dport, fin_seq, rcv_nxt, 0x11), IP_GUEST, IP_HOST), 0x0800))

        def is_fin(f):
            try:
                proto, src, dst, l4 = parse_ip(f)
                return proto == 6 and l4[13] & 0x01
            except Exception:
                return False

        f = wait_frame(k, is_fin)
        check("FIN relayed back after host EOF", f is not None)

        # ── 4. RST on refused port ──────────────────────────────────────────
        print("[4] RST on refused host port")
        sport2 = 55556
        k.send(eth(ipv4(6, tcp_seg(sport2, 39998, 500, 0, 0x02), IP_GUEST, IP_HOST), 0x0800))

        def is_rst(f):
            try:
                proto, src, dst, l4 = parse_ip(f)
                return proto == 6 and l4[13] & 0x04 and l4[0:2] == struct.pack(">H", 39998)
            except Exception:
                return False

        f = wait_frame(k, is_rst)
        check("RST sent when host port refused", f is not None)
        if f:
            proto, src, dst, l4 = parse_ip(f)
            check("RST checksum ok", tcp_csum_ok(l4))

        # ── 5. DNS relay ────────────────────────────────────────────────────
        print("[5] DNS relay (resolver on 127.0.0.1:53)")
        udp_srv = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp_srv.bind(("127.0.0.1", 53))
        udp_srv.settimeout(2)
        dns_req = b"\xab\xcd\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00\x07example\x03com\x00\x00\x01\x00\x01"

        def dns_server():
            try:
                data, addr = udp_srv.recvfrom(2048)
                got["dns"] = data
                udp_srv.sendto(b"\xab\xcd\x81\x80" + data[4:] + b"\xc0\x0c", addr)
            except OSError:
                pass

        t2 = threading.Thread(target=dns_server, daemon=True)
        t2.start()
        udp_hdr = struct.pack(">HHHH", 4444, 53, 8 + len(dns_req), 0) + dns_req
        k.send(eth(ipv4(17, udp_hdr, IP_GUEST, IP_HOST), 0x0800))
        t2.join(timeout=2)
        check("DNS query reached resolver", got.get("dns") == dns_req)

        def is_dns(f):
            try:
                proto, src, dst, l4 = parse_ip(f)
                return proto == 17 and l4[2:4] == struct.pack(">H", 4444)
            except Exception:
                return False

        f = wait_frame(k, is_dns)
        check("DNS response relayed to guest", f is not None)
        if f:
            proto, src, dst, l4 = parse_ip(f)
            check("DNS response payload", l4[8:12] == b"\xab\xcd\x81\x80")
            check("DNS sport = 53", l4[0:2] == struct.pack(">H", 53))

        udp_srv.close()
        srv.close()
        k.close()

        # ── 6. direct egress (guest internet) ──────────────────────────────
        print("[6] direct egress to a non-gateway address")
        host_ip = local_ipv4()
        if host_ip is None:
            print("  SKIP  no routable local IPv4 on this machine")
        else:
            got.clear()
            direct_srv = socket.socket()
            direct_srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            direct_srv.bind((host_ip, 39997))
            direct_srv.listen(4)
            # A fresh daemon: the first one exits together with its kernel.
            direct_proc = subprocess.Popen(
                [NETD, "--socket", SOCK2, "--dns", "127.0.0.1", "--verbose"],
                stderr=subprocess.PIPE,
            )
            time.sleep(0.3)

            def direct_echo():
                c, peer = direct_srv.accept()
                data = c.recv(4096)
                got["direct"] = (peer[0], data)
                c.sendall(b"DIRECT:" + data)
                time.sleep(0.1)
                c.close()

            th3 = threading.Thread(target=direct_echo, daemon=True)
            th3.start()
            k2 = Kernel(SOCK2)
            payload = b"direct-egress"
            back = relay_exchange(k2, socket.inet_aton(host_ip), 39997, 55601, payload)
            th3.join(timeout=2)
            check("direct: gateway replied to a non-gateway SYN", back is not None)
            check("direct: server received the payload", got.get("direct", (None, None))[1] == payload)
            check("direct: echo relayed back", back == b"DIRECT:" + payload,
                  f"{back!r}")
            check("direct: server saw the real destination, not 127.0.0.1",
                  got.get("direct", (None,))[0] == host_ip,
                  f"{got.get('direct')}")
            direct_srv.close()
            k2.close()
            if direct_proc.poll() is None:
                direct_proc.send_signal(signal.SIGTERM)
                try:
                    direct_proc.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    direct_proc.kill()

        # ── 7/8. SOCKS5 egress ─────────────────────────────────────────────
        print("[7] SOCKS5 egress")
        socks_state = {"connects": [], "data": None}
        socks_srv = socket.socket()
        socks_srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        socks_srv.bind(("127.0.0.1", 10800))
        socks_srv.listen(4)

        def socks_server():
            c, _ = socks_srv.accept()
            greet = c.recv(3)
            if greet[0] != 5:
                c.close()
                return
            socks_state["greet"] = greet
            c.sendall(b"\x05\x00")                      # no auth
            req = c.recv(10)
            if len(req) < 10 or req[3] != 1:
                c.close()
                return
            socks_state["connects"].append(
                (socket.inet_ntoa(req[4:8]), struct.unpack(">H", req[8:10])[0])
            )
            c.sendall(b"\x05\x00\x00\x01" + b"\x00" * 4 + b"\x00\x00")
            data = c.recv(4096)
            socks_state["data"] = data
            c.sendall(b"TUNNELED:" + data)
            time.sleep(0.1)
            c.close()

        th4 = threading.Thread(target=socks_server, daemon=True)
        th4.start()
        socks_proc = subprocess.Popen(
            [NETD, "--socket", SOCK3, "--dns", "127.0.0.1",
             "--socks", "127.0.0.1:10800", "--verbose"],
            stderr=subprocess.PIPE,
        )
        time.sleep(0.3)
        if socks_proc.poll() is not None:
            check("socks: uml-netd started with --socks", False,
                  socks_proc.stderr.read().decode())
        else:
            check("socks: uml-netd started with --socks", True)
            k3 = Kernel(SOCK3)
            time.sleep(0.1)
            payload = b"socks-egress"
            back = relay_exchange(k3, socket.inet_aton("93.184.216.34"), 443,
                                  55602, payload)
            th4.join(timeout=3)
            check("socks: greeting offered 'no auth'", socks_state.get("greet") == b"\x05\x01\x00",
                  f"{socks_state.get('greet')!r}")
            check("socks: CONNECT carries the guest's destination",
                  socks_state["connects"] == [("93.184.216.34", 443)],
                  f"{socks_state['connects']}")
            check("socks: payload only after the handshake",
                  socks_state.get("data") == payload, f"{socks_state.get('data')!r}")
            check("socks: tunnelled echo relayed to the guest",
                  back == b"TUNNELED:" + payload, f"{back!r}")

            # ── 8. gateway still maps to 127.0.0.1 while --socks is on ────
            print("[8] gateway path is unaffected by --socks")
            got.clear()
            gw_srv = socket.socket()
            gw_srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            gw_srv.bind(("127.0.0.1", 39996))
            gw_srv.listen(4)

            def gw_echo():
                c, peer = gw_srv.accept()
                data = c.recv(4096)
                got["gw"] = (peer[0], data)
                c.sendall(b"GW:" + data)
                time.sleep(0.1)
                c.close()

            th5 = threading.Thread(target=gw_echo, daemon=True)
            th5.start()
            back = relay_exchange(k3, IP_HOST, 39996, 55603, b"usbip-path")
            th5.join(timeout=2)
            check("gateway SYN still lands on 127.0.0.1", got.get("gw", (None,))[0] == "127.0.0.1",
                  f"{got.get('gw')}")
            check("gateway payload relayed", got.get("gw", (None, None))[1] == b"usbip-path")
            check("gateway echo relayed back", back == b"GW:usbip-path", f"{back!r}")
            check("socks proxy saw no extra CONNECT", len(socks_state["connects"]) == 1,
                  f"{socks_state['connects']}")
            gw_srv.close()
            k3.close()

        socks_srv.close()
        if socks_proc.poll() is None:
            socks_proc.send_signal(signal.SIGTERM)
            try:
                socks_proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                socks_proc.kill()

        # ── 9. argument validation ─────────────────────────────────────────
        print("[9] argument validation")
        bad = [
            ["--socket", SOCK3, "--egress", "socks"],
            ["--socket", SOCK3, "--socks", "127.0.0.1"],
            ["--socket", SOCK3, "--socks", "127.0.0.1:0"],
            ["--socket", SOCK3, "--egress", "nonsense"],
        ]
        for args in bad:
            r = subprocess.run([NETD] + args, capture_output=True, timeout=5)
            check(f"rejects {' '.join(args[1:])}", r.returncode == 2,
                  f"rc={r.returncode} {r.stderr.decode().strip()}")

        print()
        print(f"RESULT: {PASS} passed, {FAIL} failed")
        return 0 if FAIL == 0 else 1
    finally:
        proc.send_signal(signal.SIGTERM)
        try:
            proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            proc.kill()


if __name__ == "__main__":
    sys.exit(main())
