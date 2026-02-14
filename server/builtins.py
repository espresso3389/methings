import requests


BASE_URL = "http://127.0.0.1:33389"


class BuiltinError(RuntimeError):
    pass


def _post(path: str, payload: dict):
    resp = requests.post(f"{BASE_URL}{path}", json=payload, timeout=30)
    if resp.status_code == 501:
        raise NotImplementedError(resp.text)
    if resp.status_code >= 400:
        raise BuiltinError(resp.text)
    return resp.json()


def tts(text: str, voice: str | None = None):
    payload = {"text": text}
    if voice:
        payload["voice"] = voice
    return _post("/builtins/tts", payload)


def stt(audio_path: str, locale: str | None = None):
    payload = {"audio_path": audio_path}
    if locale:
        payload["locale"] = locale
    return _post("/builtins/stt", payload)


__all__ = ["tts", "stt"]
