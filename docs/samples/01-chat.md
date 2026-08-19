# 01 Chat

## 概念

- Token 是计费和上下文窗口的单位，不是字符
- 上下文窗口 = system + user + 历史 + 工具定义 + 输出
- temperature 低更确定，高更发散；面试评分 / 结构化抽取用低温度
- 流式降低 **TTFT**（首 token 时间），总耗时不一定变短

## 怎么跑

```bash
# Java
curl -s http://localhost:8080/ai-example/chat \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"用一句话介绍 Token","temperature":0.2}'

# Python
cd python && uv run python -m ai_example.samples.chat
```

## 对照 / 拷贝

- Java：`ChatSampleService`、`LlmProviderRegistry`
- 拷贝清单见 [integration.md](../integration.md)
