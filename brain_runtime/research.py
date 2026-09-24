from __future__ import annotations
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from hashlib import sha256
from typing import Callable, Iterable
from urllib.parse import urlparse
from urllib.request import Request, build_opener, HTTPRedirectHandler, HTTPSHandler
import http.client, ipaddress, re, socket, ssl

_INJECTION = re.compile(r"(?i)(ignore\s+(?:all\s+)?previous|system\s+message|developer\s+instructions|reveal\s+(?:the\s+)?prompt|jailbreak)")

@dataclass(frozen=True)
class ResearchSource:
    source_id: str; url: str; title: str; publisher: str = ""; trust_score: float = .5; retrieved_at: str = ""
@dataclass(frozen=True)
class Evidence:
    evidence_id: str; source_id: str; claim: str; excerpt: str; confidence: float; content_hash: str; rank: float = 0.0; independent_group: str = ""
@dataclass(frozen=True)
class ResearchResult:
    query: str; sources: tuple[ResearchSource, ...]; evidence: tuple[Evidence, ...]; limitations: tuple[str, ...] = ()
    @property
    def provenance(self) -> tuple[str, ...]: return tuple(source.url for source in self.sources)

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl): return None

def validate_external_url(url: str, allowed_hosts: tuple[str, ...] = ()) -> tuple[str, ...]:
    """Valida o URL e retorna os IPs resolvidos e aprovados.

    O chamador deve conectar exatamente a um desses IPs (ver
    _PinnedHTTPSConnection abaixo) em vez de deixar a camada HTTP resolver o
    host de novo — resolver duas vezes abre uma janela de DNS rebinding entre
    a validação e a conexão real.
    """
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password: raise ValueError("only credential-free HTTPS URLs are allowed")
    host = parsed.hostname.lower()
    if allowed_hosts and host not in {item.lower() for item in allowed_hosts}: raise PermissionError("research host is not allowlisted")
    try: addresses = {item[4][0] for item in socket.getaddrinfo(host, parsed.port or 443, type=socket.SOCK_STREAM)}
    except socket.gaierror as error: raise ValueError("research host cannot be resolved") from error
    for address in addresses:
        ip = ipaddress.ip_address(address)
        if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved or ip.is_multicast: raise PermissionError("research URL resolves to a forbidden network")
    return tuple(sorted(addresses))

class _PinnedHTTPSConnection(http.client.HTTPSConnection):
    """Conecta direto no IP já validado, mantendo SNI/verificação de certificado no hostname original."""
    def __init__(self, host: str, pinned_ip: str, **kwargs):
        super().__init__(host, **kwargs); self._pinned_ip = pinned_ip
    def connect(self) -> None:
        sock = socket.create_connection((self._pinned_ip, self.port), self.timeout, self.source_address)
        if getattr(self, "_tunnel_host", None):
            self.sock = sock; self._tunnel()
        context = self._context or ssl.create_default_context()
        self.sock = context.wrap_socket(sock, server_hostname=self.host)

class _PinnedHTTPSHandler(HTTPSHandler):
    def __init__(self, pinned_ip: str, **kwargs): super().__init__(**kwargs); self._pinned_ip = pinned_ip
    def https_open(self, req):
        return self.do_open(lambda host, **kw: _PinnedHTTPSConnection(host, self._pinned_ip, **kw), req)

class HTTPSResearchFetcher:
    def __init__(self, *, allowed_hosts: tuple[str, ...] = (), max_bytes: int = 2_000_000, timeout_seconds: float = 10.0): self.allowed_hosts, self.max_bytes, self.timeout_seconds = allowed_hosts, max_bytes, timeout_seconds
    def __call__(self, source: ResearchSource) -> str:
        addresses = validate_external_url(source.url, self.allowed_hosts)
        # Pina a conexão real no primeiro IP já validado acima; nenhuma nova
        # resolução de DNS acontece entre a validação e o connect().
        opener = build_opener(NoRedirect, _PinnedHTTPSHandler(addresses[0]))
        request = Request(source.url, headers={"User-Agent": "BraimCode-Research/1", "Accept": "text/plain,text/html,application/json"}, method="GET")
        with opener.open(request, timeout=self.timeout_seconds) as response:
            if response.status < 200 or response.status >= 300: raise ValueError(f"HTTP status {response.status}")
            content_type = response.headers.get("Content-Type", "")
            if content_type and not any(kind in content_type.lower() for kind in ("text/", "json", "xml")): raise ValueError("unsupported research content type")
            body = response.read(self.max_bytes + 1)
            if len(body) > self.max_bytes: raise ValueError("research response exceeds size limit")
            return body.decode(response.headers.get_content_charset() or "utf-8", errors="replace")

class ResearchLayer:
    def __init__(self, loader: Callable[[ResearchSource], str] | None = None, allowed_schemes: tuple[str, ...] = ("https",), allowed_hosts: tuple[str, ...] = (), max_excerpt: int = 2000):
        self.loader = loader or HTTPSResearchFetcher(allowed_hosts=allowed_hosts); self.allowed_schemes = allowed_schemes; self.max_excerpt = max_excerpt
    def collect(self, query: str, sources: Iterable[ResearchSource]) -> ResearchResult:
        if not query.strip(): raise ValueError("research query cannot be empty")
        evidence, accepted, limitations = [], [], []
        for source in sources:
            parsed = urlparse(source.url)
            if parsed.scheme not in self.allowed_schemes or not parsed.netloc: limitations.append(f"source rejected: {source.source_id}"); continue
            try: content = self.loader(source)
            except Exception as exc: limitations.append(f"source unavailable: {source.source_id}: {type(exc).__name__}"); continue
            if not content.strip(): limitations.append(f"source empty: {source.source_id}"); continue
            if _INJECTION.search(content): limitations.append(f"source quarantined: {source.source_id}: prompt injection"); continue
            retrieved = replace(source, retrieved_at=datetime.now(timezone.utc).isoformat()); accepted.append(retrieved); excerpt = content.strip()[: self.max_excerpt]; digest = sha256(content.encode()).hexdigest(); trust = max(0.0, min(1.0, source.trust_score)); freshness = 1.0
            evidence.append(Evidence(f"evidence_{digest[:16]}", source.source_id, query, excerpt, trust, digest, trust * .7 + freshness * .3, source.publisher or parsed.netloc))
        evidence.sort(key=lambda item: item.rank, reverse=True); return ResearchResult(query, tuple(accepted), tuple(evidence), tuple(limitations))
    @staticmethod
    def validate(result: ResearchResult, minimum_sources: int = 1, minimum_independent_groups: int = 1) -> tuple[bool, tuple[str, ...]]:
        diagnostics = []
        if len(result.sources) < minimum_sources: diagnostics.append("insufficient independent sources")
        groups = {evidence.independent_group for evidence in result.evidence if evidence.independent_group}
        if len(groups) < minimum_independent_groups: diagnostics.append("insufficient independent evidence groups")
        if not result.evidence: diagnostics.append("no evidence collected")
        if any(not evidence.content_hash for evidence in result.evidence): diagnostics.append("evidence missing content hash")
        return not diagnostics, tuple(diagnostics)
