"""citation 校验单测。"""

from ai_example.samples.rag_citation import (
    CITATION_REFUSAL,
    Citation,
    GroundedAnswer,
    apply_citation_gate,
    validate_citations,
)


def test_validate_ok():
    result = validate_citations(
        [Citation("c1", "a"), Citation("c2", "b")],
        {"c1", "c2"},
    )
    assert result.valid is True


def test_validate_unknown_id():
    result = validate_citations([Citation("ghost", "x")], {"c1"})
    assert result.valid is False
    assert "ghost" in result.detail


def test_gate_refuse():
    answer, valid, _ = apply_citation_gate(
        GroundedAnswer("编造", [Citation("ghost", "假")]),
        {"c1"},
    )
    assert valid is False
    assert answer == CITATION_REFUSAL
