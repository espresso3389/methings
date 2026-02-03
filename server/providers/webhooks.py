import requests
from typing import Optional

from storage.db import Storage


class WebhookSender:
    def __init__(self, storage: Storage):
        self.storage = storage

    def send(self, provider: str, message: str) -> bool:
        hooks = self.storage.get_webhooks()
        config = hooks.get(provider)
        if not config or not config.get("enabled"):
            return False
        url = config.get("url")
        if not url:
            return False

        payload = {"text": message} if provider == "slack" else {"content": message}
        try:
            resp = requests.post(url, json=payload, timeout=5)
            return 200 <= resp.status_code < 300
        except requests.RequestException:
            return False
