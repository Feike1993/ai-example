"""RAG citation 后校验对照（假 hits，无真 Embedding）。"""

from __future__ import annotations

from dataclasses import dataclass


CITATION_REFUSAL = (
    "模型给出的引用未通过校验（空引用或 sourceId 不在本次检索结果中），"
    "因此无法采信该答案。"
)


@dataclass
class Citation:
    """单条引用。"""

    source_id: str
    quote: str


@dataclass
class GroundedAnswer:
    """结构化答。"""

    answer: str
    citations: list[Citation]


@dataclass
class Validation:
    """校验结果。"""

    valid: bool
    detail: str


def validate_citations(citations: list[Citation], allowed_ids: set[str]) -> Validation:
    """校验 citations 非空且 source_id 均在 allowed_ids。"""
    if not citations:
        return Validation(False, "citations 为空")
    missing: list[str] = []
    for item in citations:
        if not item.source_id:
            return Validation(False, "存在空 sourceId")
        if item.source_id not in allowed_ids:
            missing.append(item.source_id)
    if missing:
        return Validation(False, "sourceId 不在检索结果: " + ", ".join(missing))
    return Validation(True, f"citations 均落在检索 hits（{len(citations)} 条）")


def apply_citation_gate(
    grounded: GroundedAnswer,
    allowed_ids: set[str],
) -> tuple[str, bool, list[Citation]]:
    """通过则返回原答案；失败则拒答。"""
    result = validate_citations(grounded.citations, allowed_ids)
    if not result.valid:
        return CITATION_REFUSAL, False, grounded.citations
    return grounded.answer, True, grounded.citations


if __name__ == "__main__":
    ok = GroundedAnswer("答", [Citation("c1", "摘录")])
    print(apply_citation_gate(ok, {"c1", "c2"}))
    bad = GroundedAnswer("答", [Citation("ghost", "假")])
    print(apply_citation_gate(bad, {"c1"}))
