import { CodeHighlight } from '@mantine/code-highlight'
import { List, SegmentedControl, SimpleGrid, Stack, Text, Title } from '@mantine/core'
import { useState } from 'react'
import type { SampleGuideData } from '../guides/types'

type Side = 'backend' | 'frontend'

/**
 * 样例讲解页：概念要点 + 可选底层逻辑 + 后端/前端核心代码切换。
 */
export function SampleGuide({ guide }: { guide: SampleGuideData }) {
  const [side, setSide] = useState<Side>('backend')
  const snippets = side === 'backend' ? guide.backend : guide.frontend

  return (
    <Stack gap="md" className="workbench">
      <div>
        <Title order={2} fz={28}>
          {guide.title}
        </Title>
        <Text c="dimmed" size="sm" mt={4}>
          核心概念、底层逻辑与源码对照
        </Text>
      </div>

      <div className="pane" style={{ padding: '1.25rem', borderRadius: 6 }}>
        <Text fw={600} mb="sm">
          概念要点
        </Text>
        <List size="sm" spacing="xs">
          {guide.concepts.map((item) => (
            <List.Item key={item}>{item}</List.Item>
          ))}
        </List>
      </div>

      {guide.logic ? (
        <div className="pane" style={{ padding: '1.25rem', borderRadius: 6 }}>
          <Text fw={600} mb="sm">
            {guide.logic.title}
          </Text>

          <Stack gap="md">
            <div>
              <Text fw={600} size="sm" mb={4}>
                解决的问题
              </Text>
              <Text size="sm" c="dimmed">
                {guide.logic.problem}
              </Text>
            </div>

            <div>
              <Text fw={600} size="sm" mb={4}>
                目的
              </Text>
              <Text size="sm" c="dimmed">
                {guide.logic.purpose}
              </Text>
            </div>

            <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="md">
              <div>
                <Text fw={600} size="sm" mb={4}>
                  优点
                </Text>
                <List size="sm" spacing="xs">
                  {guide.logic.pros.map((item) => (
                    <List.Item key={item}>
                      <Text size="sm" c="dimmed">
                        {item}
                      </Text>
                    </List.Item>
                  ))}
                </List>
              </div>
              <div>
                <Text fw={600} size="sm" mb={4}>
                  缺点
                </Text>
                <List size="sm" spacing="xs">
                  {guide.logic.cons.map((item) => (
                    <List.Item key={item}>
                      <Text size="sm" c="dimmed">
                        {item}
                      </Text>
                    </List.Item>
                  ))}
                </List>
              </div>
            </SimpleGrid>

            <div>
              <Text fw={600} size="sm" mb={4}>
                应用场景
              </Text>
              <List size="sm" spacing="xs">
                {guide.logic.scenarios.map((item) => (
                  <List.Item key={item}>
                    <Text size="sm" c="dimmed">
                      {item}
                    </Text>
                  </List.Item>
                ))}
              </List>
            </div>

            <div>
              <Text fw={600} size="sm" mb="sm">
                流程步骤
              </Text>
              <List type="ordered" size="sm" spacing="sm">
                {guide.logic.steps.map((step) => (
                  <List.Item key={step.title}>
                    <Text span fw={600} size="sm">
                      {step.title}
                    </Text>
                    <Text size="sm" c="dimmed" mt={2}>
                      {step.detail}
                    </Text>
                  </List.Item>
                ))}
              </List>
            </div>
          </Stack>
        </div>
      ) : null}

      <SegmentedControl
        value={side}
        onChange={(value) => setSide(value as Side)}
        data={[
          { value: 'backend', label: '后端' },
          { value: 'frontend', label: '前端' },
        ]}
        w="fit-content"
      />

      <Stack gap="lg">
        {snippets.map((snippet) => (
          <div key={snippet.label} className="pane" style={{ padding: '1.25rem', borderRadius: 6 }}>
            <Text fw={600} mb="sm" size="sm">
              {snippet.label}
            </Text>
            <CodeHighlight
              code={snippet.code}
              language={snippet.language === 'tsx' ? 'typescript' : 'java'}
              radius="md"
              copyLabel="复制"
              copiedLabel="已复制"
            />
          </div>
        ))}
      </Stack>
    </Stack>
  )
}
