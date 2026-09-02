"""MCP HTTP Bearer 头构造。"""

from ai_example.samples.mcp_client_http import bearer_headers


def test_bearer_headers_default(monkeypatch):
    monkeypatch.delenv("MCP_BEARER_TOKEN", raising=False)
    assert bearer_headers() == {"Authorization": "Bearer dev-mcp-token"}


def test_bearer_headers_from_env(monkeypatch):
    monkeypatch.setenv("MCP_BEARER_TOKEN", "from-env")
    assert bearer_headers() == {"Authorization": "Bearer from-env"}


def test_bearer_headers_explicit_overrides_env(monkeypatch):
    monkeypatch.setenv("MCP_BEARER_TOKEN", "from-env")
    assert bearer_headers("explicit") == {"Authorization": "Bearer explicit"}
