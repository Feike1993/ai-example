"""RAG vs 记忆双路对照单测（无 LLM）。"""

from ai_example.samples.rag_memory_compare import SKIP_GENERATE, compare_rag_memory


def test_compare_both_paths_hit():
    result = compare_rag_memory("我喜欢吃什么？一期 RAG 是什么？", generate_answers=True)
    assert result.generate_answers is True
    assert result.rag.retrieval_empty is False
    assert result.memory.retrieval_empty is False
    assert result.rag.sources[0].id.startswith("rag-")
    assert result.memory.sources[0].id.startswith("mem-")
    assert "知识库" in result.rag.answer or "RAG" in result.rag.answer
    assert "烤鸭" in result.memory.answer


def test_generate_answers_false_skips(monkeypatch):
    """generateAnswers=false：只比 sources，不走生成占位以外的逻辑。"""

    def fake_rag(_question: str):
        from ai_example.samples.rag_memory_compare import SourceHit

        return [SourceHit("rag-x", "demo.md", "hit")]

    monkeypatch.setattr("ai_example.samples.rag_memory_compare.fake_rag_retrieve", fake_rag)
    result = compare_rag_memory("含 RAG 关键词", generate_answers=False)
    assert result.generate_answers is False
    assert result.rag.answer == SKIP_GENERATE
    assert result.memory.answer == SKIP_GENERATE
    assert result.rag.retrieval_empty is False
    assert result.rag.sources[0].id == "rag-x"


def test_rag_only_empty_memory():
    result = compare_rag_memory("一期 RAG 是什么？", generate_answers=True)
    assert result.rag.retrieval_empty is False
    assert result.memory.retrieval_empty is True


def test_memory_only_empty_rag():
    result = compare_rag_memory("我喜欢吃什么？", generate_answers=True)
    assert result.rag.retrieval_empty is True
    assert result.memory.retrieval_empty is False


def test_unknown_user_no_memory():
    result = compare_rag_memory("我喜欢吃什么？", user_id="other", generate_answers=False)
    assert result.memory.retrieval_empty is True
    assert result.memory.sources == []
