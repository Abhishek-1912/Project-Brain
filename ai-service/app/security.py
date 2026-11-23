import hmac
import json

from starlette.types import ASGIApp, Receive, Scope, Send

from .config import MAX_REQUEST_BYTES, service_token


class ServiceGuard:
    """Rejects bad requests before any request body is parsed."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or not self._is_private(scope["path"]):
            await self.app(scope, receive, send)
            return

        headers = {name.lower(): value for name, value in scope["headers"]}
        if not self._authorized(headers.get(b"authorization", b"")):
            await self._reject(send, 401, "UNAUTHORIZED")
            return
        if self._too_large(headers.get(b"content-length")):
            await self._reject(send, 413, "FILE_TOO_LARGE")
            return
        await self.app(scope, receive, send)

    @staticmethod
    def _is_private(path: str) -> bool:
        return path == "/v1" or path.startswith("/v1/")

    @staticmethod
    def _authorized(header: bytes) -> bool:
        token = service_token().encode()
        return bool(token) and hmac.compare_digest(header, b"Bearer " + token)

    @staticmethod
    def _too_large(header: bytes | None) -> bool:
        try:
            return header is not None and int(header) > MAX_REQUEST_BYTES
        except ValueError:
            return True

    @staticmethod
    async def _reject(send: Send, status: int, code: str) -> None:
        body = json.dumps({"code": code}).encode()
        await send(
            {
                "type": "http.response.start",
                "status": status,
                "headers": [
                    (b"content-type", b"application/json"),
                    (b"content-length", str(len(body)).encode()),
                ],
            }
        )
        await send({"type": "http.response.body", "body": body})
