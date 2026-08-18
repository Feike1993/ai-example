"""Chat 对照：同步补全与流式打印，对应 Java ``ChatSampleService``。"""

from ai_example.core.client import default_model, openai_client


def chat(prompt: str, temperature: float | None = None) -> str:
    """一次性返回完整回复；temperature 为 None 时沿用服务端默认。"""
    kwargs: dict = {
        "model": default_model(),
        "messages": [{"role": "user", "content": prompt}],
    }
    if temperature is not None:
        kwargs["temperature"] = temperature
    response = openai_client().chat.completions.create(**kwargs)
    return response.choices[0].message.content or ""


def stream_chat(prompt: str) -> None:
    """把增量 token 打到 stdout，用来感受 TTFT；无返回值。"""
    stream = openai_client().chat.completions.create(
        model=default_model(),
        messages=[{"role": "user", "content": prompt}],
        stream=True,
    )
    for chunk in stream:
        delta = chunk.choices[0].delta.content
        if delta:
            print(delta, end="", flush=True)
    print()


if __name__ == "__main__":
    import sys

    prompt = " ".join(sys.argv[1:]) or "用一句话介绍 Token 和上下文窗口。"
    print(chat(prompt))
