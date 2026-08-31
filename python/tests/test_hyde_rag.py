"""HyDE 对照单测：假想文档只用于检索查询。"""

from __future__ import annotations

from unittest.mock import patch

from ai_example.samples.hyde_rag import Chunk, retrieve


def test_hyde_retrieve_uses_hypo_not_as_source() -> None:
    store = [
        Chunk(source="mcp.md", text="MCP 是工具接入协议", embedding=[1.0, 0.0, 0.0]),
        Chunk(source="other.md", text="完全无关的天气说明", embedding=[0.0, 1.0, 0.0]),
    ]
    hypo = "假想段落：MCP 协议让 Host 通过 Client 连接 Server。"

    def fake_embed(_client: object, texts: list[str]) -> list[list[float]]:
        text = texts[0]
        if "假想" in text or "MCP" in text:
            return [[1.0, 0.0, 0.0]]
        return [[0.0, 1.0, 0.0]]

    with (
        patch("ai_example.samples.hyde_rag._dashscope_client"),
        patch("ai_example.samples.hyde_rag._embed", side_effect=fake_embed),
    ):
        result = retrieve(
            store,
            "MCP 是啥",
            expansion="hyde",
            hypo_override=hypo,
            fuse_with_original=False,
        )

    assert result.query_expansion == "hyde"
    assert result.hypothetical_document == hypo
    assert "mcp.md" in result.sources
    assert all("假想" not in s for s in result.sources)


def test_none_expansion_skips_hypo() -> None:
    store = [
        Chunk(source="a.md", text="向量检索演示", embedding=[1.0, 0.0]),
    ]
    with (
        patch("ai_example.samples.hyde_rag._dashscope_client"),
        patch("ai_example.samples.hyde_rag._embed", return_value=[[1.0, 0.0]]),
    ):
        result = retrieve(store, "检索", expansion="none", fuse_with_original=False)
    assert result.query_expansion == "none"
    assert result.hypothetical_document is None
