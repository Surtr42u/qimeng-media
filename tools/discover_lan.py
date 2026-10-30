#!/usr/bin/env python3
"""无凭据查找影库，供人工/AI 调试；只读发现，不登录或读取业务库。

与 server/internal/landiscovery 和 Android LanDiscoveryProtocol 同步。
输出地址属于现场网络信息，保存报告须使用忽略的 *.local 目录。
"""
import argparse
import ipaddress
import json
import secrets
import socket
import time
from urllib.parse import urlsplit

PORT = 18421
PACKET_SIZE = 96
SCAN_SECONDS = 3
PREFERRED_SECONDS = 0.3
MAX_RESULTS = 32


def discover(preferred, broadcasts):
    nonce = secrets.token_hex(16)
    request = f"QIMENG_DISCOVER_V1:{nonce}".ljust(PACKET_SIZE).encode("ascii")
    prefix = f"QIMENG_SERVER_V1:{nonce}:".encode("ascii")
    found = set()
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.bind(("0.0.0.0", 0))
        sock.settimeout(0.1)

        def receive(deadline, expected=None):
            while time.monotonic() < deadline and len(found) < MAX_RESULTS:
                try:
                    data, peer = sock.recvfrom(PACKET_SIZE + 1)
                except socket.timeout:
                    continue
                if peer[1] != PORT or (expected and peer[0] != expected):
                    continue
                if len(data) > PACKET_SIZE or not data.startswith(prefix):
                    continue
                port_text = data[len(prefix):]
                if not port_text.isdigit() or not 1 <= int(port_text) <= 65535:
                    continue
                found.add(f"http://{peer[0]}:{int(port_text)}")

        for url in preferred[:5]:
            parsed = urlsplit(url)
            address = ipaddress.IPv4Address(parsed.hostname)
            if parsed.scheme != "http" or not (address.is_private or address.is_link_local):
                raise ValueError("--prefer 仅接受 http 局域网 IPv4 地址")
            try:
                sock.sendto(request, (str(address), PORT))
            except OSError:
                # 旧网段已无路由属于预期失效，继续历史/当前网卡广播。
                continue
            receive(time.monotonic() + PREFERRED_SECONDS, str(address))
            if found:
                return sorted(found)
        deadline = time.monotonic() + SCAN_SECONDS
        while time.monotonic() < deadline and len(found) < MAX_RESULTS:
            sent = False
            send_error = None
            for address in broadcasts:
                try:
                    sock.sendto(request, (address, PORT))
                    sent = True
                except OSError as error:
                    send_error = error
            if not sent:
                raise send_error
            receive(min(deadline, time.monotonic() + 1))
    return sorted(found)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prefer", action="append", default=[], help="依次优先寻找的历史 URL，最多五个")
    parser.add_argument("--broadcast", action="append", help="指定网卡广播地址，可重复；默认 255.255.255.255")
    parser.add_argument("--json", action="store_true", help="输出机器可读 JSON")
    args = parser.parse_args()
    try:
        addresses = discover(args.prefer, args.broadcast or ["255.255.255.255"])
    except (OSError, ValueError) as error:
        if args.json:
            print(json.dumps({"status": "error", "error": type(error).__name__, "servers": []}))
        else:
            print(f"发现失败：{type(error).__name__}")
        return 2
    if args.json:
        print(json.dumps({"status": "found" if addresses else "empty", "servers": addresses}))
    else:
        print("\n".join(addresses) if addresses else "未发现在线影库")
    return 0 if addresses else 1


if __name__ == "__main__":
    raise SystemExit(main())
