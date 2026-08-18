# AI Agent 学习样例

独立的 Agent 学习 cookbook。第一期只做最小闭环：**Chat → 结构化输出 → Tool Calling → ReAct Agent Loop**。

Java 用 **Spring Boot 4.1 + Spring AI 2.0 + Gradle**；Python 用 **LangGraph** 做同一概念对照，方便以后把样例拷进自己的业务项目。

## 你将学到什么

| 样例 | 概念 | Java | Python |
| --- | --- | --- | --- |
| Chat | Token、采样参数、SSE / 流式、TTFT | `POST /api/samples/chat` | `python -m ai_example.samples.chat` |
| 结构化输出 | JSON Schema、重试修 JSON | `POST /api/samples/structured/ticket` | `python -m ai_example.samples.structured` |
| Tool Calling | Function Calling、工具粒度 | `POST /api/samples/tools` | `python -m ai_example.samples.tools` |
| Agent Loop | ReAct、maxSteps 熔断 | `POST /api/samples/agent/react` | `python -m ai_example.samples.react_agent` |

对照文档：

- [学习路径](docs/learning-path.md)
- [如何拷进业务项目](docs/integration.md)
- [第二期预告](docs/phase2.md)：MCP / RAG / 上下文压缩 / 多 Agent

## 环境

- JDK **25**
- Python **3.11+**（建议 [uv](https://docs.astral.sh/uv/)）
- 一个 OpenAI 兼容 API Key（默认阿里云 DashScope / Qwen，也可换成任何兼容网关）

```bash
cp .env.example .env
# 填写 AI_API_KEY
```

## 跑 Java 样例

```bash
cd java
./gradlew bootRun
```

然后：

```bash
curl http://localhost:8080/
curl -s http://localhost:8080/api/samples/chat \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"用一句话介绍 Token 和上下文窗口"}'
curl -N 'http://localhost:8080/api/samples/chat/stream?prompt=hello'
curl -s http://localhost:8080/api/samples/structured/ticket \
  -H 'Content-Type: application/json' \
  -d '{"text":"登录页偶尔 500，P1，标签 backend,auth"}'
curl -s http://localhost:8080/api/samples/tools \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"北京天气怎么样？再算 3+5"}'
curl -s http://localhost:8080/api/samples/agent/react \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"北京天气怎么样？再算 3+5"}'
```

`POST /api/samples/agent/framework` 是 Spring AI 自动 tool-calling，用来对照手写 Loop。

测试（不需要真实 Key）：

```bash
cd java && ./gradlew test
```

## 跑 Python 对照

```bash
cd python
uv sync --group dev
uv run python -m ai_example.samples.chat
uv run python -m ai_example.samples.structured
uv run python -m ai_example.samples.tools
uv run python -m ai_example.samples.react_agent
uv run pytest
```
