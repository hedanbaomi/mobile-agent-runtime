# SPDX-FileCopyrightText: 2026 mobileAgentRuntime contributors
# SPDX-License-Identifier: AGPL-3.0-only

"""Small, capability-only SDK exposed to an isolated skill.

No function in this module opens files, starts processes, imports native
extensions or uses a network socket. Every side effect is a structured request
to the host Broker, which re-checks the invocation grant for that request.
"""

import json

import _mobileagent


class BrokerError(RuntimeError):
    """Raised when the host denies or cannot complete a capability request."""


def _request(capability: str, arguments: object) -> object:
    if not isinstance(capability, str) or not capability or len(capability) > 128:
        raise ValueError("invalid capability")
    arguments_json = json.dumps(arguments, separators=(",", ":"), ensure_ascii=True)
    value_json = _mobileagent.request(capability, arguments_json)
    try:
        return json.loads(value_json)
    except (TypeError, ValueError) as error:
        raise BrokerError("invalid Broker response") from error


def knowledge_search(query: str, limit: int = 10) -> object:
    if not isinstance(query, str) or len(query) > 4096:
        raise ValueError("invalid search query")
    if not isinstance(limit, int) or limit < 1 or limit > 100:
        raise ValueError("invalid search limit")
    return _request("knowledge.search", {"query": query, "limit": limit})


def knowledge_read(document_id: str, max_bytes: int = 256 * 1024) -> object:
    if not isinstance(document_id, str) or not document_id or len(document_id) > 256:
        raise ValueError("invalid document id")
    if not isinstance(max_bytes, int) or max_bytes < 1 or max_bytes > 8 * 1024 * 1024:
        raise ValueError("invalid document limit")
    return _request("knowledge.read", {"documentId": document_id, "maxBytes": max_bytes})


def http_request(url: str, method: str = "GET", headers: dict | None = None, body: object = None) -> object:
    if not isinstance(url, str) or len(url) > 4096:
        raise ValueError("invalid URL")
    if not isinstance(method, str) or len(method) > 16:
        raise ValueError("invalid HTTP method")
    if headers is not None and not isinstance(headers, dict):
        raise ValueError("invalid HTTP headers")
    return _request("http.request", {"url": url, "method": method, "headers": headers or {}, "body": body})


def model_invoke(provider: str, request: object) -> object:
    if not isinstance(provider, str) or not provider or len(provider) > 128:
        raise ValueError("invalid model provider")
    return _request("model.invoke", {"provider": provider, "request": request})


def storage_get(key: str) -> object:
    if not isinstance(key, str) or not key or len(key) > 256:
        raise ValueError("invalid storage key")
    return _request("storage.get", {"key": key})


def storage_put(key: str, value: object) -> object:
    if not isinstance(key, str) or not key or len(key) > 256:
        raise ValueError("invalid storage key")
    return _request("storage.put", {"key": key, "value": value})


def files_read_handle(handle: str, max_bytes: int = 256 * 1024) -> object:
    if not isinstance(handle, str) or not handle or len(handle) > 256:
        raise ValueError("invalid file handle")
    if not isinstance(max_bytes, int) or max_bytes < 1 or max_bytes > 8 * 1024 * 1024:
        raise ValueError("invalid file limit")
    return _request("files.readHandle", {"handle": handle, "maxBytes": max_bytes})


def files_write_artifact(name: str, content: object) -> object:
    if not isinstance(name, str) or not name or len(name) > 256:
        raise ValueError("invalid artifact name")
    return _request("files.writeArtifact", {"name": name, "content": content})


def log_info(message: str) -> object:
    if not isinstance(message, str) or len(message) > 4096:
        raise ValueError("invalid log message")
    return _request("log.info", {"message": message})


__all__ = [
    "BrokerError",
    "knowledge_search",
    "knowledge_read",
    "http_request",
    "model_invoke",
    "storage_get",
    "storage_put",
    "files_read_handle",
    "files_write_artifact",
    "log_info",
]
