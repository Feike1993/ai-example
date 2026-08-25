/** 讲解页中的一段核心代码。 */
export type CodeSnippet = {
  /** 片段标题，例如「同步补全」 */
  label: string
  language: 'java' | 'tsx'
  code: string
}

/** 讲解中的一步底层逻辑（标题 + 说明）。 */
export type LogicStep = {
  title: string
  detail: string
}

/** 单个样例的讲解内容：概念 + 可选底层逻辑 + 后端/前端核心代码。 */
export type SampleGuideData = {
  title: string
  concepts: string[]
  /** 可选：对话/机制底层逻辑拆解 */
  logic?: {
    title: string
    steps: LogicStep[]
  }
  backend: CodeSnippet[]
  frontend: CodeSnippet[]
}
