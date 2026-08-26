"""MCP / RAG 对照脚本的纯本地单元测试（不调真实模型）。"""

from ai_example.samples.rag import chunk_text, cosine


def test_chunk_text_splits_paragraphs():
    text = "第一段内容。\n\n第二段内容更长一些。\n\n第三段。"
    chunks = chunk_text(text, max_chars=20)
    assert len(chunks) >= 2
    assert all(c.strip() for c in chunks)


def test_cosine_identical_is_one():
    v = [1.0, 0.0, 0.0]
    assert abs(cosine(v, v) - 1.0) < 1e-9


def test_cosine_orthogonal_is_zero():
    assert abs(cosine([1.0, 0.0], [0.0, 1.0])) < 1e-9
