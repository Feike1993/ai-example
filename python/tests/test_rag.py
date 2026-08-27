"""MCP / RAG 对照脚本的纯本地单元测试（不调真实模型）。"""

from ai_example.samples.rag import EMPTY_REFUSAL, answer, chunk_text, cosine, is_retrieval_empty


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


def test_is_retrieval_empty_respects_min_sources():
    assert is_retrieval_empty([], min_sources=1)
    assert is_retrieval_empty([], min_sources=2)


def test_answer_skips_llm_when_hits_empty():
    result = answer("虚构关键词 xyz123", [], skip_llm_when_empty=True)
    assert result.retrieval_empty is True
    assert result.answer == EMPTY_REFUSAL
    assert result.sources == []
