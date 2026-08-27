"""多 Agent 路由辅助测试（不跑真模型）。"""

from ai_example.samples.multi_agent import route_after_orch


def test_route_researcher():
    assert route_after_orch({"next": "researcher", "messages": [], "materials": [], "traces": []}) == "researcher"


def test_route_writer_default():
    assert route_after_orch({"next": "writer", "messages": [], "materials": [], "traces": []}) == "writer"
    assert route_after_orch({"next": "finish", "messages": [], "materials": [], "traces": []}) == "writer"
