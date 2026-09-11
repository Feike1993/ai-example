# 工业级第十一阶段：本轮文档附件（含 Office 与扫描 OCR）

对照本机演示：**同名 `document`、数字抽文本、超件数 / 违禁词 422、扫描件 VL 转写选跑**。默认 Playwright 只验鉴权与进模型前的 422，不打 Chat LLM / VL。

这是工业级第十一阶段，不是教学新样例。教学 `/chat` 与生产 Agent **本阶段仍纯文本**。第九阶段识图、第十阶段多图仍见 [industrial-media.md](industrial-media.md)、[industrial-multi-image.md](industrial-multi-image.md)。

入口：`/ai-example/api/v1/**` + [industrial.html](../frontend/industrial.html)。

## 要证明什么

| 切片 | 通过标准 |
| --- | --- |
| 11a 契约 | 允许 mime：`pdf` / `txt` / `md` / `docx` / `xlsx`。探针只给 `{mime,bytes,sha256}`，不抽正文、不 OCR。3 份 txt → **422** `media_too_many`；`.exe` / 加密或无法解析 → **422** `media_unreadable` 或 `media_unsupported` |
| 11f 扫描 OCR | PDFBox 抽出字数 `< ocrMinChars`（默认 20）且有页 → 渲染最多 `ocrMaxPages`（默认 3）页 PNG，现有 VL **只转写**。无 Key → `secret_unavailable`（503）；转写失败 → `media_unreadable`（422）。默认 E2E **不**送扫描件 |
| 11b SSE | `POST /chat/stream` 同名 `document`。抽出（+ 可选 OCR）正文进 `UserMessage` **文本**，不把 PDF/Office 当视觉 Media。空检索有文档仍生成。`meta.hasDocument` / `documentCount` / `extractedChars` / `ocrUsed` |
| 11c 落库 | `[文档]` / `[文档×N]` 占位，排在 `[图片]` 之后。不写 blob、不写抽出/OCR 正文 |
| 11d 页面 | industrial 问答：文档多选（含 Office）、文件名芯片可单件清除、超过 2 件前端先拦。Agent 不显示选图/选文档 |
| 11e E2E | alice 3 个 tiny txt **422** `media_too_many`；含「违禁演示词」的 txt **422** `input_deny`。默认套件**不**打 LLM / VL |

## 前置

与第九 / 十阶段相同：`.env` 已设 `PRODUCTION_KEK`；Compose 或本机 Java + Redis + Postgres。超件数 / 坏 mime / 数字抽取护栏不需要 Chat / VL Key。真扫描 OCR 需要信封里的 `llm.dashscope`。

- 单文件上限仍 `PRODUCTION_MEDIA_MAX_BYTES`（默认 2MiB），与图共用。
- 文档件数 `PRODUCTION_MEDIA_MAX_DOCUMENTS`（默认 **2**）。
- 抽出正文合计 `PRODUCTION_MEDIA_MAX_EXTRACT_CHARS`（默认 **8000**），超出截断并在末尾标记。
- Spring `multipart.max-request-size` 约 **12MB**。
- jpeg/png 识图仍走 `image`；**不要**把图片改成 `document` 抽字。

## 1. 超件数与违禁词（不打模型）

```bash
TOKEN=$(curl -sS http://127.0.0.1:8080/ai-example/api/v1/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"demo"}' | jq -r .token)

echo 'hello' > /tmp/a.txt
curl -sS -D - -o /tmp/too-many.json \
  http://127.0.0.1:8080/ai-example/api/v1/chat/stream \
  -H "Authorization: Bearer $TOKEN" \
  -F 'question=总结这些文件' \
  -F 'document=@/tmp/a.txt;type=text/plain' \
  -F 'document=@/tmp/a.txt;type=text/plain' \
  -F 'document=@/tmp/a.txt;type=text/plain'
```

期望：**422** `{ "code":"media_too_many", ... }`。单个 part 名仍叫 `document`，多份用同名重复，不要改成 `documents`。

```bash
echo '含有违禁演示词' > /tmp/deny.txt
curl -sS -D - -o /tmp/deny.json \
  http://127.0.0.1:8080/ai-example/api/v1/chat/stream \
  -H "Authorization: Bearer $TOKEN" \
  -F 'question=总结这份文件' \
  -F 'document=@/tmp/deny.txt;type=text/plain'
```

期望：**422** `{ "code":"input_deny", ... }`，在进模型前拒绝。

探针 alice 小 pdf / utf-8 txt 应为 **200** `{ mime, bytes, sha256 }`，不调 LLM / VL。

## 2. 带文档问答（本机选跑）

已配 DashScope Key 时：

```bash
curl -N http://127.0.0.1:8080/ai-example/api/v1/chat/stream \
  -H "Authorization: Bearer $TOKEN" \
  -F 'question=请用中文总结这份附件' \
  -F 'document=@/path/to/note.txt;type=text/plain'
```

期望：SSE `meta → sources? → delta* → usage → done`，`meta.hasDocument=true`，`meta.documentCount=1`。刷新后再跟问，历史里只有 `[文档] 请用中文总结这份附件`，不再带正文。两份写 `[文档×2]`。可与图片同轮：图走 VL `Media`，文档只进文本。

检索仍按用户问句；附件不是语料、不进 pgvector。有文档时空检索仍调文本 ChatModel。扫描 PDF：先 VL 只转写，再（若用户没另附 `image`）用普通 ChatModel 基于抽出正文作答——不要把「转写 + 作答」揉成一次 VL。

## 3. 工业页

问答模式：选文档 `accept` 含 pdf/txt/md/docx/xlsx；文件名芯片可单件清除；超过 2 件前端先提示 `media_too_many`，后端仍是权威。Agent 模式没有选图/选文档。

## 4. 可选单测

```bash
cd java
./gradlew test --tests ProductionDocumentExtractorTest --tests ProductionDocumentOcrTest \
  --tests ProductionMediaInspectorTest --tests ProductionMediaMvcTest \
  --tests ProductionChatServiceImplTest --tests ProductionAnswerGeneratorTest
cd ../frontend
pnpm exec tsc -b
pnpm run test -- src/industrial
pnpm test:e2e
```

默认 Playwright **不**打 Chat LLM / VL。真扫描 OCR 本机选跑。

## 刻意不做

- pptx、旧版 `.doc` / `.xls`、Tesseract、视频
- 知识库上传后台、抽出/OCR 正文进 pgvector
- 把 jpeg/png 改走 `document` 抽字（识图仍是 `image`）
- 音频直接进 VL、文生图、Agent 看图或带文档
- 教学多模态 Tab；把 Chat LLM / VL 打进 CI
- 无上限堆文件、把 PDF/Office 字节当视觉 Media 作答
