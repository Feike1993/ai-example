"""LangGraph create_react_agent 对照 + 第十期可观测：逐步 tool 事件与假 usage 累加。"""

from __future__ import annotations

from dataclasses import dataclass, field

from langchain_core.tools import tool
from langchain_openai import ChatOpenAI
from langgraph.prebuilt import create_react_agent

from ai_example.core.client import _versioned
from ai_example.core.env import settings


@tool
def get_weather(city: str) -> str:
    """查询指定城市的天气。演示用，返回模拟数据。"""
    mapping = {
        "北京": "北京：晴，25°C，北风 2 级",
        "上海": "上海：多云，28°C，东南风 3 级",
    }
    return mapping.get(city, f"{city}：阴，22°C")


@tool
def add(a: float, b: float) -> str:
    """计算两个数字的和。"""
    return str(a + b)


@dataclass
class TokenUsage:
    """对照 Java TokenUsage。"""

    prompt: int | None = None
    completion: int | None = None
    total: int | None = None


@dataclass
class ToolEvent:
    """逐步工具事件。"""

    kind: str  # tool_call | tool_result
    index: int
    tool_name: str
    tool_args: str = ""
    tool_result: str = ""


@dataclass
class ObservableTrace:
    """可观测运行结果。"""

    final_answer: str
    events: list[ToolEvent] = field(default_factory=list)
    usage: TokenUsage | None = None
    usage_calls: int = 0


def sum_usage(left: TokenUsage | None, right: TokenUsage | None) -> TokenUsage | None:
    """累加两次用量。"""
    if left is None:
        return right
    if right is None:
        return left
    prompt = (left.prompt or 0) + (right.prompt or 0)
    completion = (left.completion or 0) + (right.completion or 0)
    total = (left.total or 0) + (right.total or 0)
    if prompt == 0 and completion == 0 and total == 0:
        return None
    return TokenUsage(
        prompt=prompt or None,
        completion=completion or None,
        total=total or (prompt + completion) or None,
    )


def build_agent():
    """用当前 .env 构造 ReAct Agent；temperature 固定 0.2，便于和 Java 对照。"""
    cfg = settings()
    model = ChatOpenAI(
        model=cfg["model"],
        api_key=cfg["api_key"] or "missing-key",
        base_url=_versioned(cfg["base_url"]),
        temperature=0.2,
    )
    return create_react_agent(
        model,
        tools=[get_weather, add],
        prompt="你是会使用工具的助手。需要天气或加法时调用工具，不要编造数字。用中文给出最终答案。",
    )


def run(prompt: str) -> str:
    """执行一轮 Agent，返回最后一条消息文本。"""
    agent = build_agent()
    result = agent.invoke({"messages": [{"role": "user", "content": prompt}]})
    messages = result.get("messages", [])
    if not messages:
        return ""
    last = messages[-1]
    content = getattr(last, "content", last)
    return str(content)


def run_observable_demo(
    tool_plan: list[tuple[str, str, str]],
    *,
    final_answer: str,
    per_call_usage: TokenUsage | None = None,
) -> ObservableTrace:
    """不连真模型：按计划发射 tool 事件并累加假 usage（单测 / 离线对照）。

    tool_plan: [(tool_name, args, result), ...]
    """
    events: list[ToolEvent] = []
    usage: TokenUsage | None = None
    calls = 0
    for index, (name, args, result) in enumerate(tool_plan, start=1):
        events.append(ToolEvent("tool_call", index, name, tool_args=args))
        events.append(ToolEvent("tool_result", index, name, tool_result=result))
        # 每轮工具前一次 LLM call
        call_usage = per_call_usage or TokenUsage(prompt=10, completion=5, total=15)
        usage = sum_usage(usage, call_usage)
        calls += 1
    # 终答再一次 call
    usage = sum_usage(usage, per_call_usage or TokenUsage(prompt=8, completion=12, total=20))
    calls += 1
    return ObservableTrace(
        final_answer=final_answer,
        events=events,
        usage=usage,
        usage_calls=calls,
    )


if __name__ == "__main__":
    print(run("北京天气怎么样？再算 3+5"))
