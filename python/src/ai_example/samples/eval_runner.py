"""Eval golden 对照：读 JSON schema，跑 tools / react 最小断言。"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from ai_example.samples import tools as tools_sample
from ai_example.samples.react_agent import run as react_run


@dataclass
class EvalCase:
    """与 Java EvalGoldenLoader.EvalCase 对齐。"""

    id: str
    target: str
    prompt: str
    must_contain: list[str]
    must_not_contain: list[str]
    max_steps: int | None = None
    expect_tool_name: str | None = None


def load_cases() -> list[EvalCase]:
    """加载仓库内 golden（与 Java classpath 对齐的路径）。"""
    root = Path(__file__).resolve().parents[4] / "java" / "src" / "main" / "resources" / "eval" / "golden"
    cases: list[EvalCase] = []
    if not root.is_dir():
        return cases
    for path in sorted(root.glob("*.json")):
        raw = json.loads(path.read_text(encoding="utf-8"))
        cases.append(
            EvalCase(
                id=raw["id"],
                target=raw["target"],
                prompt=raw["prompt"],
                must_contain=list(raw.get("mustContain") or []),
                must_not_contain=list(raw.get("mustNotContain") or []),
                max_steps=raw.get("maxSteps"),
                expect_tool_name=raw.get("expectToolName"),
            )
        )
    return cases


def assert_text(case: EvalCase, answer: str) -> list[str]:
    """字符串断言。"""
    errors: list[str] = []
    for fragment in case.must_contain:
        if fragment not in answer:
            errors.append(f"missing mustContain: {fragment}")
    for fragment in case.must_not_contain:
        if fragment in answer:
            errors.append(f"hit mustNotContain: {fragment}")
    return errors


def run_case(case: EvalCase) -> tuple[bool, str, list[str]]:
    """跑单条 tools / agentReact；rag / multiagent 需 Java 侧。"""
    if case.target == "tools":
        answer = tools_sample.chat_with_tools(case.prompt, max_steps=case.max_steps or 8)
        errors = assert_text(case, answer)
        return len(errors) == 0, answer, errors
    if case.target == "agentReact":
        answer = react_run(case.prompt)
        errors = assert_text(case, answer)
        if case.expect_tool_name:
            errors.append("python react_agent 暂不校验 expectToolName，请用 Java /eval/run")
        return len(errors) == 0, answer, errors
    return False, "", [f"skip target {case.target} in python runner"]


def main() -> None:
    """跑 golden 子集并打印通过率。"""
    cases = [c for c in load_cases() if c.target in {"tools", "agentReact"}]
    passed = 0
    for case in cases:
        ok, answer, errors = run_case(case)
        status = "PASS" if ok else "FAIL"
        print(f"{status} {case.id}")
        if not ok:
            print(" ", "; ".join(errors))
        else:
            print(" ", (answer or "")[:80])
        passed += int(ok)
    print(f"--- {passed}/{len(cases)} passed ---")


if __name__ == "__main__":
    main()
