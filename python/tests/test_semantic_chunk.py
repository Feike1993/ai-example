"""语义分块单测。"""

from __future__ import annotations

from ai_example.samples.semantic_chunk import compare_chunking, semantic_chunk


def test_semantic_keeps_heading() -> None:
    text = """# Intro
hello

## MCP
MCP 是工具协议。
"""
    chunks = semantic_chunk(text, source="a.md")
    assert chunks
    assert all(c.chunking == "semantic" for c in chunks)
    assert any(c.heading == "MCP" for c in chunks)


def test_compare_returns_both() -> None:
    result = compare_chunking("一段没有标题的纯文本。\n\n第二段。")
    assert "token" in result and "semantic" in result
    assert result["token"]
    assert result["semantic"]
