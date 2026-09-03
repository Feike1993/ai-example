import type { SampleGuideData } from './types'

/** RAG 强制 citation 讲解（第十一期）。 */
export const ragCitationGuide: SampleGuideData = {
  title: 'RAG 强制 citation',
  concepts: [
    'A3 管空检索；本样例管「有 hits 也必须带可校验引用」。',
    'citationMode=required → 结构化 {answer,citations}。',
    'Prompt 用 C1..Cn 别名，避免模型抄文件名；校验前 resolve 再严格 validate。',
    '后校验：归一化后的 sourceId ∈ sources[].id；失败 citationValid=false。',
    '默认 none 保持样例 06 自由文本。',
  ],
  logic: {
    title: '有出处才答',
    problem: '自由文本答案旁虽有 sources，但模型可能未真正依据它们；且不同模型对 sourceId 形态理解不一致。',
    purpose: '强制结构化引用并用短别名消歧，失败则拒答而非悄悄采信。',
    pros: ['可机器校验', '跨模型更稳', '与 A3 字段区分', '复用 StructuredOutputInvoker'],
    cons: ['多一次结构化约束成本', '流式路径未强制 citation', '同文件多 chunk 时文件名无法自动映射'],
    scenarios: ['知识库问答要可追溯', '对照 none vs required'],
    steps: [
      { title: '检索', detail: '与现有 RAG 相同；空检索仍走 A3。' },
      { title: '别名上下文', detail: '拼 C1..Cn + allowlist，正文不再暴露 filename 作 id。' },
      { title: '结构化答', detail: 'GroundedAnswer + citations。' },
      { title: '归一化与校验', detail: 'resolveCitations 后 validate；失败返回 CITATION_REFUSAL。' },
    ],
  },
  backend: [
    {
      label: 'resolve → validate',
      language: 'java',
      code: `var aliases = CitationValidator.aliasMap(sources);
var resolved = CitationValidator.resolveCitations(raw, aliases, sources);
Result r = CitationValidator.validate(resolved, idsOf(sources));
if (!r.valid()) return refusal + citationValid=false;`,
    },
  ],
  frontend: [
    {
      label: 'citationMode=required',
      language: 'tsx',
      code: `await postJson('/ai-example/rag/query', {
  question, provider, citationMode: 'required',
})`,
    },
  ],
}
