"""父子文档单测。"""

from __future__ import annotations

from ai_example.samples.parent_child_rag import build_parent_child, expand_parents


def test_build_and_expand() -> None:
    text = """## A
一二三四五六七八九十。

## B
甲乙丙丁。
"""
    kids = build_parent_child(text, child_size=20)
    assert kids
    assert all(c.chunk_role == "child" for c in kids)
    assert all(c.parent_text for c in kids)
    expanded = expand_parents(kids)
    assert len(expanded) <= len(kids)
    assert len(expanded) >= 1
