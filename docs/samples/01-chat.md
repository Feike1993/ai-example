# 01 Chat

## 概念

- Token 是计费和上下文窗口的单位，不是字符
- 上下文窗口 = system + user + 历史 + 工具定义 + 输出
- temperature 低更确定，高更发散；面试评分 / 结构化抽取用低温度
- 流式降低 **TTFT**（首 token 时间），总耗时不一定变短

## 对话底层逻辑

1. **消息是对话的原子单位** — 一轮对话由带角色的消息组成：system（人设/约束）、user（提问）、assistant（模型回复）。本样例多为单轮 user；多轮则把历史 assistant/user 依次堆叠进请求。
2. **每次请求都是「整段上下文」** — 模型本身没有跨请求记忆。「记得上文」= 客户端把历史消息再塞进本次窗口。窗口大致 = system + 历史 + 本轮 user +（预留）输出；超窗会被截断或拒收。
3. **补全 = 自回归预测下一个 Token** — 给定当前序列，模型产出下一 token 的概率分布，选出一个 token 拼回序列，再继续预测，直到遇到停止条件（结束符、长度上限等）。
4. **采样决定「选哪个 next token」** — temperature 等参数调节分布的尖锐或平坦：低温度更确定（评分、结构化抽取），高温度更发散（创意写作）。
5. **同步与流式只是交付方式** — 生成仍是逐 token。同步等拼完再返回；流式边生成边推给前端，所以 TTFT（首 token 时间）变短，总生成时间通常差不多。
6. **计费与截断都盯 Token** — 输入与输出一般按 token 计费；历史越长，窗口占用与费用越高，因此要主动控制对话历史长度。

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
