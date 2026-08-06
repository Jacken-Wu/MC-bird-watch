"""M4a 冒烟测试:RCON 召唤三物种 + 查询,验证实体注册/召唤链路。

用法:python tools/rcon_smoke.py [host] [port] [password]
(默认 127.0.0.1:25575 / birdwatch,与 run/server.properties 一致)

协议注意(26.2 实测):所有整数小端序;请求 = length + requestId + type + payload + 2 null,
length 不含自身(等于整帧字节数 - 4);响应 = length + requestId + type + payload + 2 null。
踩坑:封包漏 requestId 字段会导致服务端秒断连接且无任何日志。
"""
import socket
import struct
import sys

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 25575
PASSWORD = sys.argv[3] if len(sys.argv) > 3 else "birdwatch"


def read_exact(sock, count: int) -> bytes:
    buf = b""
    while len(buf) < count:
        chunk = sock.recv(count - len(buf))
        if not chunk:
            break
        buf += chunk
    return buf


def send_packet(sock, pkt_type: int, payload: str, request_id: int = 1) -> bytes:
    body = struct.pack("<ii", request_id, pkt_type) + payload.encode() + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(body)) + body)
    n = struct.unpack("<i", read_exact(sock, 4))[0]  # 响应总长(不含 length 字段)
    buf = read_exact(sock, n)
    # buf = requestId(4) + type(4) + payload + 2 null → 取 payload
    return buf[8:n - 2] if len(buf) >= n - 2 else buf[8:]


def rcon(*cmds: str) -> None:
    with socket.create_connection((HOST, PORT), timeout=10) as s:
        send_packet(s, 3, PASSWORD, request_id=0)  # auth
        for i, cmd in enumerate(cmds):
            resp = send_packet(s, 2, cmd, request_id=i + 1)
            print(f"> {cmd}\n  {resp.decode(errors='replace').strip()}")


if __name__ == "__main__":
    rcon(
        "time set day",
        "summon birdwatch:little_egret 80 100 80",
        "summon birdwatch:sparrow 90 100 90",
        "summon birdwatch:tit 100 100 100",
        "list",
    )
