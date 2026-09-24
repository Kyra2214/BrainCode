from __future__ import annotations
import hmac, json, os, secrets, socket, struct
from dataclasses import dataclass
from typing import Any

@dataclass(frozen=True)
class IPCMessage:
    kind: str; correlation_id: str; payload: dict[str, Any]; nonce: str

class IPCProtocolError(ValueError): pass

class SecureIPC:
    """Length-framed, authenticated Unix-socket protocol; secrets are redacted before transport logs."""
    def __init__(self, sock: socket.socket, key: bytes, max_bytes: int = 1_048_576):
        if not key: raise ValueError("IPC key must not be empty")
        self.sock, self.key, self.max_bytes = sock, key, max_bytes
    def _frame(self, message: IPCMessage) -> bytes:
        body = json.dumps(message.__dict__, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
        if len(body) > self.max_bytes: raise IPCProtocolError("IPC message too large")
        mac = hmac.new(self.key, body, "sha256").hexdigest().encode(); return struct.pack("!I", len(body)) + body + mac
    def send(self, kind: str, correlation_id: str, payload: dict[str, Any]) -> None:
        message = IPCMessage(kind, correlation_id, _sanitize(payload), secrets.token_hex(16)); self.sock.sendall(self._frame(message))
    def recv(self) -> IPCMessage:
        header = _read_exact(self.sock, 4); length = struct.unpack("!I", header)[0]
        if length <= 0 or length > self.max_bytes: raise IPCProtocolError("invalid IPC frame size")
        body = _read_exact(self.sock, length); mac = _read_exact(self.sock, 64)
        expected = hmac.new(self.key, body, "sha256").hexdigest().encode()
        if not hmac.compare_digest(mac, expected): raise IPCProtocolError("IPC authentication failed")
        data = json.loads(body); message = IPCMessage(**data)
        if not message.kind or not message.correlation_id or not message.nonce: raise IPCProtocolError("invalid IPC message")
        return message

def _read_exact(sock: socket.socket, size: int) -> bytes:
    chunks = []
    while sum(map(len, chunks)) < size:
        chunk = sock.recv(size - sum(map(len, chunks)))
        if not chunk: raise IPCProtocolError("IPC peer closed connection")
        chunks.append(chunk)
    return b"".join(chunks)

def _sanitize(value: Any) -> Any:
    if isinstance(value, dict): return {k: "[REDACTED]" if str(k).lower() in {"secret", "token", "password", "api_key", "credential"} else _sanitize(v) for k, v in value.items()}
    if isinstance(value, list): return [_sanitize(v) for v in value]
    return value
