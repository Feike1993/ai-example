"""护栏管道单测。"""

from ai_example.samples.guardrail_chat import BLOCKED_REFUSAL, run_guardrail


def test_input_deny_short_circuits():
    result = run_guardrail("请解释违禁演示词", llm_answer="不应到达")
    assert result.blocked is True
    assert result.block_stage == "input_deny"
    assert result.answer == BLOCKED_REFUSAL
    assert result.checks[0].passed is False


def test_output_deny():
    result = run_guardrail("正常问题", llm_answer="含有违禁演示词的回复")
    assert result.blocked is True
    assert result.block_stage == "output_deny"


def test_pass():
    result = run_guardrail("正常问题", llm_answer="护栏通过")
    assert result.blocked is False
    assert result.answer == "护栏通过"
