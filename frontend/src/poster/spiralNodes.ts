import { baselineSamples } from '../shared/samples'

export type SpiralNode = {
  cx: number
  cy: number
  index: number
  phase: 1 | 2 | 3
}

/** 宣传页 HeroSpiral 同源的 8 节点坐标（viewBox 420×320）。 */
export function getSpiralNodePositions(): SpiralNode[] {
  return baselineSamples.map((sample, i) => {
    const phase = sample.phase as 1 | 2 | 3
    const x = 40 + (i % 4) * 95
    const y = phase === 1 ? 200 : phase === 2 ? 120 : 40
    return {
      cx: x + 36,
      cy: y + 18,
      index: sample.index,
      phase,
    }
  })
}

export function buildSpiralPath(nodes: SpiralNode[]): string {
  return nodes
    .map((node, i) => `${i === 0 ? 'M' : 'L'} ${node.cx} ${node.cy}`)
    .join(' ')
}

export const SPIRAL_VIEWBOX = { width: 420, height: 320 } as const
