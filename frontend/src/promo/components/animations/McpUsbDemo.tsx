import { motion } from 'motion/react'

/** MCP USB-C 隐喻：Function Calling → MCP → Tools。 */
export function McpUsbDemo() {
  return (
    <div className="demo-panel demo-mcp">
      <div className="demo-mcp-row">
        <div className="demo-mcp-block">
          <span>Function Calling</span>
          <small>模型输出意图</small>
        </div>
        <motion.div
          className="demo-mcp-connector"
          animate={{ scaleX: [0.6, 1, 0.6] }}
          transition={{ repeat: Infinity, duration: 2 }}
        >
          ═══ USB-C ═══
        </motion.div>
        <div className="demo-mcp-block demo-mcp-block--accent">
          <span>MCP 协议</span>
          <small>Streamable HTTP</small>
        </div>
        <motion.span className="demo-tool-arrow" animate={{ opacity: [0.4, 1, 0.4] }} transition={{ repeat: Infinity, duration: 1.5 }}>
          →
        </motion.span>
        <div className="demo-mcp-block">
          <span>统一 Tools</span>
          <small>Host / Client / Server</small>
        </div>
      </div>
    </div>
  )
}
