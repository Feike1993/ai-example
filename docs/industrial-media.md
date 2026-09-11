# 工业级第九阶段：图文多模态与语音

对照本机演示：**探针不打模型、图文 SSE、ASR/TTS 本机选跑**。默认 Playwright 只验鉴权 / mime / 体积。

这是工业级第九阶段，不是教学新样例。教学 `/chat` 与生产 Agent 本阶段仍只走文本。第六 / 七 / 八阶段仍见 [industrial-ha.md](industrial-ha.md)、[industrial-ops.md](industrial-ops.md)、[industrial-agent.md](industrial-agent.md)。第十阶段一轮多图见 [industrial-multi-image.md](industrial-multi-image.md)。

入口：`/ai-example/api/v1/**` + [industrial.html](../frontend/industrial.html)。

## 要证明什么

| 切片 | 通过标准 |
| --- | --- |
| 9a 探针 | `POST /media/probe`：无 JWT **401**；`.txt` **422** `media_unsupported`；超 2MiB **422** `media_too_large`。alice 小 jpeg **200** `{ mime, bytes, sha256 }`，不调 LLM |
| 9b 图文 | 文本仍用 `GET /chat/stream`。`POST /chat/stream` multipart（`question` + 可选 `image`）SSE 契约不变；`meta.hasImage`。有图用 Spring AI `UserMessage` + `Media`，模型 `PRODUCTION_VISION_MODEL`（默认 `qwen-vl-plus`），Provider 默认 dashscope。检索仍按文本；落库 `[图片]` 占位，不写 blob。当时只允许一张；多图见 [industrial-multi-image.md](industrial-multi-image.md) |
| 9c ASR | `POST /speech/transcribe` → `{ text }`。默认套件只打坏 mime |
| 9d TTS | `POST /speech/speak` JSON；空文本 400；违禁词 422 `input_deny`。音色 `PRODUCTION_TTS_VOICE` |
| 9e 页面 | industrial 问答：选图 / 麦克风转写填问题 / 终答朗读。Agent 模式不显示选图 |

## 前置

与第七阶段相同：`.env` 已设 `PRODUCTION_KEK`；Compose 或本机 Java + Redis + Postgres。探针本身不需要 Embedding / Chat / VL Key。

本机选跑识图 / 转写 / 朗读需要信封里的 `llm.dashscope`（与 Embedding 同源）。继续排除 `OpenAiImageAutoConfiguration`（那是文生图）。ASR/TTS 手写 HTTP，不打开 OpenAI Audio 自动配置。

## 1. 无模型探针

```bash
TOKEN=$(curl -sS http://127.0.0.1:8080/ai-example/api/v1/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"demo"}' | jq -r .token)

curl -sS -D - -o /tmp/probe.json \
  http://127.0.0.1:8080/ai-example/api/v1/media/probe \
  -H "Authorization: Bearer $TOKEN" \
  -F 'file=@/tmp/tiny.jpg;type=image/jpeg'
```

期望：**200**，body 含 `mime` / `bytes` / `sha256`，没有文件体。审计 `media.probe` 只有 sha256。

无令牌应为 **401** `auth_missing_token`。把 `.txt` 或超过 `PRODUCTION_MEDIA_MAX_BYTES`（默认 2MiB）的文件送上去应为 **422**。

可观测快照应能看到 `mediaAccepted` / `mediaRejected`。

## 2. 图文问答（本机选跑）

无 Key 时带图请求应在打网关前完成 mime / 大小校验（坏文件直接 422）。

已配 DashScope Key 时：

```bash
curl -N http://127.0.0.1:8080/ai-example/api/v1/chat/stream \
  -H "Authorization: Bearer $TOKEN" \
  -F 'question=请用中文描述这张图' \
  -F 'image=@/path/to/photo.jpg;type=image/jpeg'
```

期望：SSE `meta → sources? → delta* → usage → done`，`meta.hasImage=true`，中文描述。刷新后再跟问，历史里只有 `[图片] 请用中文描述这张图`，不再带原图。

无图时 `POST` 与 `GET /chat/stream` 行为一致。有图且检索为空时仍会调视觉模型（图片本身就是上下文）；无图时空检索规则不变。

## 3. 转写 / 朗读（本机选跑）

```bash
curl -sS http://127.0.0.1:8080/ai-example/api/v1/speech/transcribe \
  -H "Authorization: Bearer $TOKEN" \
  -F 'audio=@/tmp/hello.wav;type=audio/wav' | jq .

curl -sS http://127.0.0.1:8080/ai-example/api/v1/speech/speak \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"text":"你好，这是工业级语音演示"}' \
  --output /tmp/speak.mp3
```

空文本 **400**；`{"text":"违禁演示词"}` **422** `input_deny`。音频不写入会话。

工业页：问答模式选图预览、麦克风录音后填入问题框再流式提问、终答点「朗读」。Agent 模式没有选图。

## 4. 可选单测

```bash
cd java
./gradlew test --tests ProductionMediaInspectorTest --tests ProductionMediaMvcTest \
  --tests ProductionChatServiceImplTest --tests ProductionJwtMvcTest
cd ../frontend
pnpm exec tsc -b
pnpm run test -- src/industrial
pnpm test:e2e
```

默认 Playwright **不**打 VL / ASR / TTS。

## 刻意不做

- 对象存储、CDN、把图片进 pgvector、知识库上传后台
- 文生图、实时全双工通话、Agent 工具看图
- 教学样例多模态 Tab；根目录 `pnpm start`
- 把 VL / ASR / TTS 打进 CI
- 图生图（多图已由第十阶段覆盖，见 [industrial-multi-image.md](industrial-multi-image.md)）
