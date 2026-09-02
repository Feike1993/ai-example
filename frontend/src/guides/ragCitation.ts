import type { SampleGuideData } from './types'

/** RAG 强制 citation 讲解（第十一期）。 */
export const ragCitationGuide: SampleGuideData = {
  title: 'RAG 强制 citation',
  concepts: [
    'A3 管空检索；本样例管「有 hits 也必须带可校验引用」。',
    'citationMode=required → 结构化 {answer,citations}。',
    '后校验：sourceId ∈ sources[].id；失败 citationValid=false。',
    '默认 none 保持样例 06 自由文本。',
  ],
  logic: {
    title: '有出处才答',
    problem: '自由文本答案旁虽有 sources，但模型可能未真正依据它们。',
    purpose: '强制结构化引用并校验 id，失败则拒答而非悄悄采信。',
    pros: ['可机器校验', '与 A3 字段区分', '复用 StructuredOutputInvoker'],
    cons: ['多一次结构化约束成本', '流式路径未强制 citation'],
    scenarios: ['知识库问答要可追溯', '对照 none vs required'],
    steps: [
      { title: '检索', detail: '与现有 RAG 相同；空检索仍走 A3。' },
      { title: '结构化答', detail: 'GroundedAnswer + citations。' },
      { title: '校验', detail: 'CitationValidator；失败返回 CITATION_REFUSAL。' },
    ],
  },
  backend: [
    {
      label: 'CitationValidator',
      language: 'java',
      code: `Result r = CitationValidator.validate(citations, idsOf(sources));
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
