"""手写 Function Calling 循环，对应 Java ``ToolSampleService`` / ``ReactAgentLoop`` 的协议层。"""

import json
from collections.abc import Callable

from ai_example.core.client import default_model, openai_client

TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "get_weather",
            "description": "查询指定城市的天气。演示用，返回模拟数据。",
            "parameters": {
                "type": "object",
                "properties": {"city": {"type": "string", "description": "城市名，例如 北京"}},
                "required": ["city"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "add",
            "description": "计算两个数字的和",
            "parameters": {
                "type": "object",
                "properties": {
                    "a": {"type": "number", "description": "第一个加数"},
                    "b": {"type": "number", "description": "第二个加数"},
                },
                "required": ["a", "b"],
            },
        },
    },
]


def get_weather(city: str) -> str:
    """返回写死的演示天气，避免样例依赖外部 API。"""
    mapping = {
        "北京": "北京：晴，25°C，北风 2 级",
        "上海": "上海：多云，28°C，东南风 3 级",
    }
    return mapping.get(city, f"{city}：阴，22°C")


def add(a: float, b: float) -> str:
    """两数求和，结果转成字符串以便塞进 tool 消息。"""
    return str(a + b)


HANDLERS: dict[str, Callable[..., str]] = {
    "get_weather": lambda **kwargs: get_weather(str(kwargs["city"])),
    "add": lambda **kwargs: add(float(kwargs["a"]), float(kwargs["b"])),
}


def chat_with_tools(prompt: str, max_steps: int = 8) -> str:
    """执行 tool_calls 直到模型给出最终文本，或触达 max_steps 熔断。"""
    client = openai_client()
    messages: list[dict] = [
        {"role": "system", "content": "你是助手。需要天气或加法时必须调用工具，不要编造。"},
        {"role": "user", "content": prompt},
    ]
    for _ in range(max_steps):
        response = client.chat.completions.create(
            model=default_model(),
            messages=messages,
            tools=TOOLS,
        )
        message = response.choices[0].message
        messages.append(message.model_dump(exclude_unset=True))
        if not message.tool_calls:
            return message.content or ""
        for call in message.tool_calls:
            args = json.loads(call.function.arguments or "{}")
            handler = HANDLERS.get(call.function.name)
            result = handler(**args) if handler else f"unknown tool: {call.function.name}"
            messages.append(
                {
                    "role": "tool",
                    "tool_call_id": call.id,
                    "content": str(result),
                }
            )
    return f"已达到最大步数 {max_steps}，已停止以防无限循环。"


if __name__ == "__main__":
    print(chat_with_tools("北京天气怎么样？再算 3+5"))
