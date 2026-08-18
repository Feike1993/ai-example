"""JSON 修复单测，不调用真实 LLM。"""

from ai_example.core.json_repair import parse_model, strip_markdown_fence
from ai_example.samples.structured import Ticket


def test_strip_markdown_fence() -> None:
    raw = """```json
{"title":"登录失败"}
```"""
    assert strip_markdown_fence(raw) == '{"title":"登录失败"}'


def test_parse_ticket() -> None:
    raw = """```json
{"title":"登录 500","priority":"P1","labels":["backend","auth"],"summary":"登录页偶发 500"}
```"""
    ticket = parse_model(raw, Ticket)
    assert ticket.title == "登录 500"
    assert ticket.priority == "P1"
    assert ticket.labels == ["backend", "auth"]
