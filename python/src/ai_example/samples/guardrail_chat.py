"""输出护栏对照：词表检查 + 可选结构信封校验（可无真 LLM）。"""

from __future__ import annotations

from dataclasses import dataclass, field


DEFAULT_DENY_WORDS = ("违禁演示词", "BLOCKED_DEMO")
BLOCKED_REFUSAL = "请求或回复未通过护栏检查，已拒绝生成/展示内容。请修改表述后重试。"


@dataclass
class Check:
    """单次检查。"""

    name: str
    passed: bool
    detail: str


@dataclass
class GuardrailResult:
    """护栏结果。"""

    blocked: bool
    block_stage: str | None
    answer: str
    checks: list[Check] = field(default_factory=list)


def first_deny_hit(text: str, words: tuple[str, ...] = DEFAULT_DENY_WORDS) -> str | None:
    """返回命中的第一个敏感词（大小写不敏感）。"""
    if not text:
        return None
    hay = text.lower()
    for word in words:
        if word and word.lower() in hay:
            return word
    return None


def run_guardrail(
    prompt: str,
    *,
    llm_answer: str | None = None,
    require_structured: bool = False,
    structured_ok: bool = True,
    structured_content: str | None = None,
    deny_words: tuple[str, ...] = DEFAULT_DENY_WORDS,
) -> GuardrailResult:
    """离线护栏管道：输入词表 →（假 LLM）→ 结构可选 → 输出词表。"""
    checks: list[Check] = []
    hit = first_deny_hit(prompt, deny_words)
    if hit:
        checks.append(Check("input_deny", False, f"命中敏感词: {hit}"))
        return GuardrailResult(True, "input_deny", BLOCKED_REFUSAL, checks)
    checks.append(Check("input_deny", True, "未命中输入词表"))

    if require_structured:
        if not structured_ok or not structured_content:
            checks.append(Check("structure", False, "信封解析失败或 content 为空"))
            return GuardrailResult(True, "structure", BLOCKED_REFUSAL, checks)
        checks.append(Check("structure", True, "信封解析成功且 safe=true"))
        content = structured_content
    else:
        checks.append(Check("structure", True, "未启用结构校验（跳过）"))
        content = llm_answer or ""

    out_hit = first_deny_hit(content, deny_words)
    if out_hit:
        checks.append(Check("output_deny", False, f"命中敏感词: {out_hit}"))
        return GuardrailResult(True, "output_deny", BLOCKED_REFUSAL, checks)
    checks.append(Check("output_deny", True, "未命中输出词表"))
    return GuardrailResult(False, None, content, checks)


if __name__ == "__main__":
    print(run_guardrail("你好", llm_answer="护栏是确定性规则。"))
    print(run_guardrail("请解释违禁演示词"))
