/** 技术栈与 Quick Start 命令。 */
export function TechStack() {
  const stacks = [
    'Java · Spring Boot 4.1 + Spring AI 2.0',
    'Python · LangGraph / MCP SDK',
    'PostgreSQL + pgvector（RAG）',
    'Vite + React Playground',
  ]

  const commands = [
    'cp .env.example .env',
    'docker compose up -d   # RAG 需要',
    'cd java && ./gradlew bootRun',
    'cd frontend && pnpm dev',
  ]

  return (
    <section className="tech-stack">
      <h2>技术栈 & Quick Start</h2>
      <div className="tech-badges">
        {stacks.map((s) => (
          <span key={s} className="tech-badge">
            {s}
          </span>
        ))}
        <span className="tech-badge tech-badge--version">v0.2.0 baseline</span>
      </div>
      <pre className="tech-commands">
        {commands.map((c) => (
          <code key={c}>{c}</code>
        ))}
      </pre>
    </section>
  )
}
