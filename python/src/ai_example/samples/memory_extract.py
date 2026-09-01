"""自动抽记忆：假对话 → Chat 抽事实 JSON → remember。"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass

from openai import OpenAI

from ai_example.core.client import default_model, openai_client
from ai_example.samples.long_term_memory import MemoryStore, RememberResult, remember

DEFAULT_EXTRACT_MAX_FACTS = 5

EXTRACT_SYSTEM = (
    "你从对话中抽取适合写入长期记忆的短事实句。"
    "只输出 JSON 字符串数组，例如 [\"用户叫小明\",\"用户住在杭州\"]；不要解释、不要 Markdown 围栏。"
    "每条一句、陈述句、简体中文；不要编造对话未出现的信息。"
)


@dataclass
class ExtractResult:
    """抽取结果。"""

    user_id: str
    facts: list[str]
    remembered: list[RememberResult]
    skipped_duplicates: int


def parse_fact_list(raw: str | None, max_facts: int = DEFAULT_EXTRACT_MAX_FACTS) -> list[str]:
    """解析模型输出的 JSON 字符串数组；失败返回空列表。"""
    if not raw or not raw.strip():
        return []
    text = raw.strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text)
        text = re.sub(r"\s*```$", "", text)
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        return []
    if not isinstance(parsed, list):
        return []
    facts: list[str] = []
    for item in parsed:
        if not isinstance(item, str) or not item.strip():
            continue
        facts.append(item.strip())
        if len(facts) >= max_facts:
            break
    return facts


def extract(
    store: MemoryStore,
    messages: list[dict[str, str]],
    user_id: str = "demo",
    *,
    client: OpenAI | None = None,
    max_facts: int = DEFAULT_EXTRACT_MAX_FACTS,
    chat_fn=None,
) -> ExtractResult:
    """对对话抽事实并 remember；chat_fn 可注入假响应便于单测。"""
    dialogue = [m for m in messages if m.get("content", "").strip()]
    if not dialogue:
        raise ValueError("messages 不能为空")

    transcript = "\n".join(
        f"{m.get('role', 'user')}: {m['content'].strip()}" for m in dialogue
    )
    if chat_fn is not None:
        raw = chat_fn(transcript, max_facts)
    else:
        llm = client or openai_client()
        response = llm.chat.completions.create(
            model=default_model(),
            messages=[
                {"role": "system", "content": EXTRACT_SYSTEM},
                {
                    "role": "user",
                    "content": f"最多抽取 {max_facts} 条事实。\n\n对话：\n{transcript}",
                },
            ],
        )
        raw = response.choices[0].message.content or ""

    facts = parse_fact_list(raw, max_facts)
    remembered: list[RememberResult] = []
    skipped = 0
    for fact in facts:
        result = remember(store, fact, user_id=user_id, client=client)
        remembered.append(result)
        if result.duplicate:
            skipped += 1
    return ExtractResult(
        user_id=user_id,
        facts=facts,
        remembered=remembered,
        skipped_duplicates=skipped,
    )


def main() -> None:
    """假对话抽取演示。"""
    store = MemoryStore()
    messages = [
        {"role": "user", "content": "我叫小明，住在杭州，喜欢北京烤鸭"},
        {"role": "assistant", "content": "好的，已记下。"},
    ]
    result = extract(store, messages, user_id="demo")
    print("facts:", result.facts)
    print("skipped_duplicates:", result.skipped_duplicates)
    print("store size:", len(store.items))


if __name__ == "__main__":
    main()
