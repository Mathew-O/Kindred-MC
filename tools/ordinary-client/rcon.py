#!/usr/bin/env python3
"""Runs server console commands over RCON, for a live check: python3 rcon.py <password> <command>... (port 25575)."""
import socket, struct, sys


def packet(sock, request_id, kind, body):
    data = struct.pack("<ii", request_id, kind) + body.encode() + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(data)) + data)
    size = struct.unpack("<i", sock.recv(4))[0]
    payload = b""
    while len(payload) < size:
        payload += sock.recv(size - len(payload))
    return payload[8:-2].decode(errors="replace")


def main():
    password, commands = sys.argv[1], sys.argv[2:]
    with socket.create_connection(("127.0.0.1", 25575)) as sock:
        packet(sock, 1, 3, password)
        for command in commands:
            print("> " + command)
            print(packet(sock, 2, 2, command))


if __name__ == "__main__":
    main()
