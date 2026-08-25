import { Accordion } from '@mantine/core'
import { JsonBlock } from './JsonBlock'

/**
 * 可折叠的原始 JSON，主展示旁对照接口形态。
 */
export function RawJsonAccordion({ value }: { value: unknown }) {
  return (
    <Accordion variant="contained" radius="md">
      <Accordion.Item value="raw">
        <Accordion.Control>原始 JSON</Accordion.Control>
        <Accordion.Panel>
          <JsonBlock value={value} />
        </Accordion.Panel>
      </Accordion.Item>
    </Accordion>
  )
}
