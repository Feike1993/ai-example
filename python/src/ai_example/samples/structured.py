"""结构化输出对照：JSON Mode + Pydantic 校验 + 失败重试。"""

from pydantic import BaseModel, Field

from ai_example.core.client import default_model, openai_client
from ai_example.core.json_repair import parse_model

SYSTEM = """你是工单提取器。只返回 JSON 对象，字段：
- title: 短标题
- priority: P0、P1、P2 或 P3
- labels: 字符串数组
- summary: 一句话摘要
不要 Markdown 代码块，不要解释文字。"""


class Ticket(BaseModel):
    """演示用工单；字段需能被模型稳定填出。"""

    title: str
    priority: str
    labels: list[str] = Field(default_factory=list)
    summary: str


def extract_ticket(text: str, max_attempts: int = 2) -> Ticket:
    """把自然语言解析为 ``Ticket``；用户原文包在 data-boundary 内以防注入。"""
    client = openai_client()
    last_error: Exception | None = None
    system = SYSTEM
    for attempt in range(1, max_attempts + 1):
        response = client.chat.completions.create(
            model=default_model(),
            messages=[
                {"role": "system", "content": system},
                {"role": "user", "content": f"<data-boundary>\n{text}\n</data-boundary>"},
            ],
            response_format={"type": "json_object"},
        )
        raw = response.choices[0].message.content or ""
        try:
            return parse_model(raw, Ticket)  # type: ignore[return-value]
        except Exception as exc:  # noqa: BLE001 — 解析失败要带上原因重试
            last_error = exc
            system = SYSTEM + f"\n上次解析失败：{exc}。请只返回合法 JSON。"
            if attempt == max_attempts:
                break
    raise RuntimeError(f"结构化输出解析失败: {last_error}") from last_error


if __name__ == "__main__":
    ticket = extract_ticket("登录页偶尔 500，P1，标签 backend,auth")
    print(ticket.model_dump_json(indent=2, ensure_ascii=False))
