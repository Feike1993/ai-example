"""可观测 ReAct：逐步事件顺序与 usage 累加。"""

from ai_example.samples.react_agent import TokenUsage, run_observable_demo, sum_usage


def test_sum_usage():
    a = TokenUsage(prompt=10, completion=5, total=15)
    b = TokenUsage(prompt=8, completion=12, total=20)
    s = sum_usage(a, b)
    assert s is not None
    assert s.prompt == 18
    assert s.completion == 17
    assert s.total == 35


def test_observable_demo_event_order():
    trace = run_observable_demo(
        [("getWeather", '{"city":"北京"}', "晴"), ("add", '{"a":3,"b":5}', "8")],
        final_answer="晴，和为 8",
    )
    kinds = [e.kind for e in trace.events]
    assert kinds == ["tool_call", "tool_result", "tool_call", "tool_result"]
    assert trace.usage_calls == 3
    assert trace.usage is not None
    assert trace.usage.prompt == 10 + 10 + 8
    assert trace.final_answer == "晴，和为 8"
