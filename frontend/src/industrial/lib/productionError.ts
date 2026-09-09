/** Spring Boot 默认错误体里的英文 status 短语，不能当用户文案。 */
const BOOT_ERROR_PHRASES = new Set([
  'Bad Request',
  'Unauthorized',
  'Forbidden',
  'Not Found',
  'Method Not Allowed',
  'Conflict',
  'Gone',
  'Unprocessable Entity',
  'Too Many Requests',
  'Internal Server Error',
  'Not Implemented',
  'Service Unavailable',
])

/**
 * 解析工业级失败体：优先中文 {@code message}，忽略 Boot 的 {@code error: Forbidden}。
 *
 * @param status HTTP 状态
 * @param body   响应正文
 * @returns 给用户看的句子；有机器码则带上但不当前主文案
 */
export function parseProductionError(status: number, body: string): { message: string; code?: string } {
  try {
    const parsed: unknown = JSON.parse(body)
    if (typeof parsed === 'object' && parsed !== null) {
      const record = parsed as Record<string, unknown>
      const code = typeof record.code === 'string' && record.code ? record.code : undefined
      if (typeof record.message === 'string' && record.message.trim()) {
        return { message: record.message, code }
      }
      if (typeof record.error === 'string' && record.error.trim() && !BOOT_ERROR_PHRASES.has(record.error)) {
        return { message: record.error, code }
      }
      if (code) {
        return { message: `HTTP ${status}`, code }
      }
    }
  } catch {
    // 非 JSON 错误体仍保留原文展示
  }
  const trimmed = body.trim()
  if (!trimmed) {
    return { message: `HTTP ${status}` }
  }
  return { message: trimmed.startsWith('{') ? `HTTP ${status}` : trimmed }
}
