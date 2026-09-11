# 工业级第十二阶段：生产 Agent 本轮看图 / 带文档

对照本机演示：**`POST /agent/stream` 同名 `image`/`document`、超件数 / 违禁词 422、有图先 VL 转写再文本工具循环**。默认 Playwright 只验鉴权与进循环前的 422，不打 Chat LLM / VL。

这是工业级第十二阶段，不是教学新样例。教学 `/agent` **仍纯文本**。问答侧多图 / 文档仍见 [industrial-multi-image.md](industrial-multi-image.md)、[industrial-doc-attach.md](industrial-doc-attach.md)。

入口：`/ai-example/api/v1/**` + [industrial.html](../frontend/industrial.html)。

## 要证明什么

| 切片 | 通过标准 |
| --- | --- |
| 12a 契约 | `POST /agent/stream` 复用问答的 `image`/`document`、件数/体积上限。alice 3 个 tiny txt → **422** `media_too_many`，不进 Agent 循环；含「违禁演示词」的 txt → **422** `input_deny`；无 JWT → **401** |
| 12b 先转写再循环 | 有图：现有 VL **只转写/简述**（不调工具、不作答），正文拼进首条纯文本 `UserMessage`，循环仍 `chatModel` + 现有工具。仅文档：抽出正文进用户文本，不调 VL。`UserMessage` **无 Media**。`meta.hasImage` / `imageCount` / `hasDocument` / `documentCount` / `ocrUsed` / `visionTranscribed` |
| 12c 落库 | 复用 `[图片]` / `[文档]` 占位，不写 blob / 转写 / 抽出正文 |
| 12d 页面 | industrial Agent 模式也显示选图/选文档；有附件 `POST /agent/stream`，无附件仍 `GET /agent/stream` |
| 12e E2E | alice `POST /agent/stream` 3 txt **422** `media_too_many`；违禁 txt **422** `input_deny`。默认套件**不**打 LLM / VL / 真循环 |

## 为什么不是 VL + tools 同轮

本仓默认视觉是 DashScope `qwen-vl-plus`（Qwen2.5-VL），**官方不支持 Function Calling**。默认文本 Agent 常走 DeepSeek，工具循环已经在用，但本仓识图不走 DeepSeek。

因此有图时与第十一阶段扫描 OCR 同一套路：先 VL 只转写，再用文本模型跑 `search_kb` / `add` / `get_weather`。不要把默认视觉升到 `qwen3-vl-plus`，也不要把 DeepSeek 当本仓 VL。

## 前置

与第九–十一阶段相同：`.env` 已设 `PRODUCTION_KEK`；Compose 或本机 Java + Redis + Postgres。超件数 / 坏 mime / 抽出护栏不需要 Chat / VL Key。真看图需要信封里的 `llm.dashscope`。

- part 名与问答相同：同名重复 `image`、`document`。
- 上限共用 `maxImages`（默认 3）/ `maxDocuments`（默认 2）/ `maxBytes`（默认 2MiB）。
- `GET /agent/stream` 保持纯文本。带附件只走 **`POST /agent/stream` multipart**。
- 同步 `POST /agent` JSON **本阶段不带附件**。

## 1. 超件数与违禁词（不打模型）

```bash
TOKEN=$(curl -sS http://127.0.0.1:8080/ai-example/api/v1/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"demo"}' | jq -r .token)

echo 'hello' > /tmp/a.txt
curl -sS -D - -o /tmp/agent-too-many.json \
  http://127.0.0.1:8080/ai-example/api/v1/agent/stream \
  -H "Authorization: Bearer $TOKEN" \
  -F 'question=总结这些文件' \
  -F 'document=@/tmp/a.txt;type=text/plain' \
  -F 'document=@/tmp/a.txt;type=text/plain' \
  -F 'document=@/tmp/a.txt;type=text/plain'
```

期望：**422** `{ "code":"media_too_many", ... }`，不进 `ProductionAgentLoop`。

```bash
echo '含有违禁演示词' > /tmp/deny.txt
curl -sS -D - -o /tmp/agent-deny.json \
  http://127.0.0.1:8080/ai-example/api/v1/agent/stream \
  -H "Authorization: Bearer $TOKEN" \
  -F 'question=总结这份文件' \
  -F 'document=@/tmp/deny.txt;type=text/plain'
```

期望：**422** `{ "code":"input_deny", ... }`。

无 JWT 的 `POST /agent/stream` 为 **401** `auth_missing_token`。

## 2. 带文档 / 看图 Agent（本机选跑）

已配 Chat Key（文档）或 DashScope VL Key（图片）时：

```bash
curl -N http://127.0.0.1:8080/ai-example/api/v1/agent/stream \
  -H "Authorization: Bearer $TOKEN" \
  -F 'question=请用中文总结这份附件，必要时 search_kb' \
  -F 'document=@/path/to/note.txt;type=text/plain'
```

期望：SSE `meta → step* → delta* → usage → done`，`meta.mode=agent`，`meta.hasDocument=true`。刷新后再跟问，历史里只有 `[文档] …`，不再带抽出正文。

有图：先 VL 转写，`meta.visionTranscribed=true`，循环的 `UserMessage` 只有文本。图转写失败（无 Key / VL 挂）与 OCR 相同：`secret_unavailable`（503）或 `media_unreadable`（422），不要落到空循环瞎编。扫描 PDF 仍走现有 OCR；渲染页不塞进 Agent Media。

无附件时工业页仍走 `GET /agent/stream`。

## 3. 工业页

Agent 模式显示选图/选文档（同一套上限）。有附件 `POST`，无附件 `GET`。问答模式识图仍把 jpeg/png 当视觉 `Media`；Agent 有图则先转写再文本循环。

## 4. 可选单测

```bash
cd java
./gradlew test --tests ProductionImageDescribeTest --tests ProductionAgentServiceImplTest \
  --tests ProductionMediaMvcTest --tests ProductionChatServiceImplTest
cd ../frontend
pnpm exec tsc -b
pnpm run test -- src/industrial
pnpm test:e2e
```

默认 Playwright **不**打 Chat LLM / VL / 真 Agent 循环。

## 刻意不做

- 用 `qwen-vl-plus` 跑带 tools 的 Agent 循环
- 把默认视觉升到 `qwen3-vl-plus` / 把 DeepSeek 当本仓 VL
- pptx、旧 OLE、Tesseract、视频、文生图、音频进 VL
- 附件进 pgvector / 知识库上传后台
- 新的 Agent「看图」工具、教学多模态 Tab
- 把 Chat LLM / VL / 真 Agent 循环打进 CI
- 同步 `POST /agent` JSON 带 multipart
