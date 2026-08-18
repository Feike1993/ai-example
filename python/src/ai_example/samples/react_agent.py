"""LangGraph ``create_react_agent`` 对照，对应 Java 的框架托管 tool-calling。"""

from langchain_core.tools import tool
from langchain_openai import ChatOpenAI
from langgraph.prebuilt import create_react_agent

from ai_example.core.env import settings
from ai_example.core.client import _versioned


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


if __name__ == "__main__":
    print(run("北京天气怎么样？再算 3+5"))
