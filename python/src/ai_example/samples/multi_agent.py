"""多 Agent 对照：LangGraph 最小 Orchestrator → researcher / writer。"""

from __future__ import annotations

import json
from typing import Annotated, Literal, TypedDict

from langgraph.graph import END, StateGraph
from langgraph.graph.message import add_messages

from ai_example.core.client import default_model, openai_client


class AgentState(TypedDict):
    """图状态。"""

    messages: Annotated[list, add_messages]
    materials: list[str]
    next: str
    traces: list[dict]


def orchestrator(state: AgentState) -> dict:
    """结构化决定 next=researcher|writer。"""
    client = openai_client()
    material = "\n".join(state.get("materials") or []) or "（无）"
    user_task = ""
    for msg in reversed(state["messages"]):
        if getattr(msg, "type", None) == "human" or (isinstance(msg, dict) and msg.get("role") == "user"):
            user_task = msg.content if hasattr(msg, "content") else msg.get("content", "")
            break
    response = client.chat.completions.create(
        model=default_model(),
        messages=[
            {
                "role": "system",
                "content": (
                    "你是编排者。只返回 JSON："
                    '{"next":"researcher|writer","task":"...","reason":"..."}。'
                    "需要天气/加法时选 researcher，否则 writer。"
                ),
            },
            {
                "role": "user",
                "content": f"任务：{user_task}\n材料：{material}",
            },
        ],
    )
    raw = response.choices[0].message.content or "{}"
    raw = raw.strip()
    if raw.startswith("```"):
        raw = raw.split("\n", 1)[-1]
        if raw.endswith("```"):
            raw = raw[: -3]
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        data = {"next": "writer", "task": "", "reason": "解析失败，降级执笔"}
    nxt = str(data.get("next", "writer"))
    traces = list(state.get("traces") or [])
    traces.append({"name": "orchestrator", "decision": data})
    return {"next": nxt, "traces": traces, "materials": state.get("materials") or []}


def researcher(state: AgentState) -> dict:
    """挂工具跑一轮简易 tool loop。"""
    from ai_example.samples.tools import chat_with_tools

    task = ""
    for t in reversed(state.get("traces") or []):
        if t.get("name") == "orchestrator":
            task = (t.get("decision") or {}).get("task") or ""
            break
    if not task:
        for msg in state["messages"]:
            if getattr(msg, "type", None) == "human":
                task = msg.content
                break
    answer = chat_with_tools(task or "查北京天气")
    materials = list(state.get("materials") or [])
    materials.append(f"【调研】{answer}")
    traces = list(state.get("traces") or [])
    traces.append({"name": "researcher", "answer": answer})
    return {"materials": materials, "traces": traces, "next": "orchestrator"}


def writer(state: AgentState) -> dict:
    """根据材料写最终答复。"""
    client = openai_client()
    user_task = ""
    for msg in state["messages"]:
        if getattr(msg, "type", None) == "human":
            user_task = msg.content
            break
    material = "\n".join(state.get("materials") or []) or "（无）"
    response = client.chat.completions.create(
        model=default_model(),
        messages=[
            {
                "role": "system",
                "content": "你是执笔专员。只根据材料回答，用简体中文。",
            },
            {"role": "user", "content": f"任务：{user_task}\n材料：{material}"},
        ],
    )
    answer = response.choices[0].message.content or ""
    traces = list(state.get("traces") or [])
    traces.append({"name": "writer", "answer": answer})
    return {"traces": traces, "messages": [{"role": "assistant", "content": answer}]}


def route_after_orch(state: AgentState) -> Literal["researcher", "writer"]:
    """Orchestrator 出口路由。"""
    nxt = (state.get("next") or "writer").lower()
    if nxt in {"researcher", "worker_a"}:
        return "researcher"
    return "writer"


def build_graph():
    """编译最小多 Agent 图。"""
    graph = StateGraph(AgentState)
    graph.add_node("orchestrator", orchestrator)
    graph.add_node("researcher", researcher)
    graph.add_node("writer", writer)
    graph.set_entry_point("orchestrator")
    graph.add_conditional_edges("orchestrator", route_after_orch)
    graph.add_edge("researcher", "orchestrator")
    graph.add_edge("writer", END)
    return graph.compile()


def run(prompt: str) -> dict:
    """执行一次多 Agent。"""
    app = build_graph()
    result = app.invoke(
        {
            "messages": [{"role": "user", "content": prompt}],
            "materials": [],
            "next": "",
            "traces": [],
        }
    )
    return result


def main() -> None:
    """命令行入口。"""
    result = run("查一下北京天气，再写一句给游客的出行建议")
    print("traces:", json.dumps(result.get("traces"), ensure_ascii=False, indent=2))
    msgs = result.get("messages") or []
    if msgs:
        last = msgs[-1]
        content = last.content if hasattr(last, "content") else last.get("content")
        print("final:", content)


if __name__ == "__main__":
    main()
