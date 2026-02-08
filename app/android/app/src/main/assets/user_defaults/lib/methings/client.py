from __future__ import annotations

from typing import Optional

from kugutz.client import KugutzClient


class MethingsClient(KugutzClient):
    """
    Back-compat wrapper for the on-device control plane client.

    `KugutzClient` remains the implementation, but new code can import
    `MethingsClient` from `lib/methings`.
    """

    def __init__(self, base_url: str = "http://127.0.0.1:8765", *, identity: Optional[str] = None):
        super().__init__(base_url=base_url, identity=identity)

