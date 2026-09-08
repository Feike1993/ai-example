/**
 * SSE 测试夹具：把「服务端逐块吐字节」这件事变成可控输入。
 *
 * 真实网络下一个事件可能被拆进两个 chunk、心跳会插在事件之间、连接可能在任意位置断掉，
 * 这些都是解析器最容易出错的地方，夹具的存在就是为了让它们能被稳定复现。
 */

/**
 * 拼一条 SSE 帧文本。
 *
 * @param seq   事件序号，写进 id 字段；传 null 表示故意不带 id
 * @param event 事件名
 * @param data  已序列化的负载
 * @returns 完整帧（含结尾空行）
 */
export function frame(seq: number | null, event: string, data: string): string {
  const id = seq === null ? '' : `id:${seq}\n`
  return `${id}event:${event}\ndata:${data}\n\n`
}

/**
 * 造一个逐块吐出文本的 SSE 响应。
 *
 * 单独导出是为了让需要按 URL 分流的用例（会话接口和流式接口混在一起时）
 * 能自己组装 fetch，而不必再造一遍流。
 *
 * @param chunks  依次吐出的文本块
 * @param signal  取消信号；已取消时让流报 AbortError
 * @param delayMs 每块之间的间隔，用于制造「还在生成中」的窗口
 * @returns text/event-stream 响应
 */
export function sseResponse(chunks: string[], signal?: AbortSignal | null, delayMs = 0): Response {
  const encoder = new TextEncoder()
  let index = 0

  const stream = new ReadableStream<Uint8Array>({
    async pull(controller) {
      if (signal?.aborted) {
        controller.error(new DOMException('Aborted', 'AbortError'))
        return
      }
      if (index >= chunks.length) {
        controller.close()
        return
      }
      if (delayMs) {
        await new Promise((resolve) => setTimeout(resolve, delayMs))
      }
      controller.enqueue(encoder.encode(chunks[index]))
      index += 1
    },
  })

  return new Response(stream, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  })
}

/**
 * 构造一个返回给定文本块的 fetch 替身。
 *
 * @param chunks   依次吐出的文本块
 * @param options  status 非 2xx 时模拟 HTTP 失败；delayMs 拉长每块之间的间隔
 * @returns 可传给 streamRun 的 fetchImpl
 */
export function fakeFetch(
  chunks: string[],
  options: { status?: number; body?: string; delayMs?: number } = {},
): typeof fetch {
  const status = options.status ?? 200

  return (async (_url: string, init?: RequestInit) => {
    if (status >= 400) {
      return new Response(options.body ?? '后端炸了', { status })
    }
    return sseResponse(chunks, init?.signal, options.delayMs)
  }) as unknown as typeof fetch
}
