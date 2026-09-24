from __future__ import annotations
from dataclasses import dataclass
from hashlib import sha256
from typing import Mapping

@dataclass(frozen=True)
class SignaturePolicy:
    required: bool = True
    trusted_key_ids: tuple[str, ...] = ()

class SignatureVerifier:
    """Adapter para autoridade de assinatura; Ed25519 via cryptography quando disponível."""
    def __init__(self, public_keys: Mapping[str, bytes], policy: SignaturePolicy = SignaturePolicy()): self.public_keys, self.policy = dict(public_keys), policy
    def verify(self, key_id: str, payload: bytes, signature: bytes) -> bool:
        if key_id not in self.public_keys or (self.policy.trusted_key_ids and key_id not in self.policy.trusted_key_ids): return False
        try:
            from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
            Ed25519PublicKey.from_public_bytes(self.public_keys[key_id]).verify(signature, payload); return True
        except ImportError: return False
        except Exception: return False
    def require(self, key_id: str, payload: bytes, signature: bytes) -> None:
        if self.policy.required and not self.verify(key_id, payload, signature): raise PermissionError("signature verification failed")

def content_digest(payload: bytes) -> str: return sha256(payload).hexdigest()
