# 工业级第十阶段：一轮多图

对照本机演示：**同名多 `image`、超张数 422、落库占位、不打 VL**。默认 Playwright 只验鉴权与张数上限。

这是工业级第十阶段，不是教学新样例。教学 `/chat` 与生产 Agent 本阶段仍只走文本。第九阶段单图识图仍见 [industrial-media.md](industrial-media.md)。

入口：`/ai-example/api/v1/**` + [industrial.html](../frontend/industrial.html)。

## 要证明什么

| 切片 | 通过标准 |
| --- | --- |
| 10a 张数 | `POST /chat/stream` 4 张小 jpeg → **422** `media_too_many`，不调生成。单张 `.txt` 仍 422 `media_unsupported`。探针仍单文件 |
| 10b 多图 SSE | 同名重复 part `image`。两张 tiny jpeg（空检索亦可）SSE 终态 `done`，`meta.hasImage=true`、`meta.imageCount=2`。无 Key 时坏文件仍在校验阶段失败 |
| 10c 落库 | 0 张原文；1 张 `[图片] `；N>1 `[图片×N] `。不写二进制 |
| 10d 页面 | industrial 问答：多选预览、可单张清除、超过 3 张前端先拦。Agent 模式不显示选图 |
| 10e E2E | 无 JWT `POST /chat/stream` **401**；alice 4 张 jpeg **422** `media_too_many`。默认套件**不**打 VL |

## 前置

与第九阶段相同：`.env` 已设 `PRODUCTION_KEK`；Compose 或本机 Java + Redis + Postgres。超张数 / 坏 mime 不需要 VL Key。

单张上限仍 `PRODUCTION_MEDIA_MAX_BYTES`（默认 2MiB）。张数上限 `PRODUCTION_MEDIA_MAX_IMAGES`（默认 **3**）。Spring `multipart.max-request-size` 约 **12MB**（第十一阶段起），让超张/超体积走到业务 422，而不是容器先掐。

## 1. 超张数（不打模型）

```bash
TOKEN=$(curl -sS http://127.0.0.1:8080/ai-example/api/v1/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"demo"}' | jq -r .token)

curl -sS -D - -o /tmp/too-many.json \
  http://127.0.0.1:8080/ai-example/api/v1/chat/stream \
  -H "Authorization: Bearer $TOKEN" \
  -F 'question=对比这些图' \
  -F 'image=@/tmp/tiny.jpg;type=image/jpeg' \
  -F 'image=@/tmp/tiny.jpg;type=image/jpeg' \
  -F 'image=@/tmp/tiny.jpg;type=image/jpeg' \
  -F 'image=@/tmp/tiny.jpg;type=image/jpeg'
```

期望：**422** `{ "code":"media_too_many", ... }`。兼容第九阶段：单个 part 名仍叫 `image`，多张用同名重复，不要改成 `images`。

无令牌应为 **401** `auth_missing_token`。

## 2. 多图问答（本机选跑）

已配 DashScope Key 时：

```bash
curl -N http://127.0.0.1:8080/ai-example/api/v1/chat/stream \
  -H "Authorization: Bearer $TOKEN" \
  -F 'question=请用中文对比这两张图' \
  -F 'image=@/path/to/a.jpg;type=image/jpeg' \
  -F 'image=@/path/to/b.jpg;type=image/jpeg'
```

期望：SSE `meta → sources? → delta* → usage → done`，`meta.hasImage=true`，`meta.imageCount=2`。刷新后再跟问，历史里只有 `[图片×2] 请用中文对比这两张图`，不再带原图。单张仍写 `[图片]`，兼容旧历史。

有任意一张图时空检索仍调 VL（沿用第九阶段例外）；无图规则不变。文件不落盘、不进 pgvector。

## 3. 工业页

问答模式：`<input multiple>` 预览列表可单张清除；超过 3 张前端先提示 `media_too_many`，后端仍是权威。Agent 模式没有选图。

## 4. 可选单测

```bash
cd java
./gradlew test --tests ProductionMediaInspectorTest --tests ProductionMediaMvcTest \
  --tests ProductionChatServiceImplTest
cd ../frontend
pnpm exec tsc -b
pnpm run test -- src/industrial
pnpm test:e2e
```

默认 Playwright **不**打 VL。

## 刻意不做

- PDF / Office / 视频、文档抽文本进本轮（第十一阶段已覆盖 pdf / txt / md / docx / xlsx 与扫描 VL OCR；pptx / 旧 OLE `.doc`/`.xls` 仍不做，见 [industrial-doc-attach.md](industrial-doc-attach.md)）
- 音频直接进 VL、知识库上传后台、图片进 pgvector
- 文生图、Agent 看图、教学多模态 Tab
- 把 VL 打进 CI；无上限堆图
