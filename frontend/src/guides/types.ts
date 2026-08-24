/** 讲解页中的一段核心代码。 */
export type CodeSnippet = {
  /** 片段标题，例如「同步补全」 */
  label: string
  language: 'java' | 'tsx'
  code: string
}

/** 单个样例的讲解内容：概念 + 后端/前端核心代码。 */
export type SampleGuideData = {
  title: string
  concepts: string[]
  backend: CodeSnippet[]
  frontend: CodeSnippet[]
}
