import { Badge, Group, Select, Text } from '@mantine/core'
import type { ProviderView } from '../api'

type ModelSelectorProps = {
  providers: ProviderView[]
  value: string
  onChange: (value: string | null) => void
  capability?: string
  label?: string
}

/**
 * 按能力过滤的模型选择器。
 * Provider 仍是连接和密钥的归属；能否被某条路由使用由 capability 决定。
 */
export function ModelSelector({
  providers,
  value,
  onChange,
  capability = 'chat',
  label = '聊天模型',
}: ModelSelectorProps) {
  const capabilitiesOf = (item: ProviderView) => item.capabilities ?? ['chat']
  const candidates = providers.filter((item) => capabilitiesOf(item).includes(capability))
  const selected = candidates.find((item) => item.id === value)

  return (
    <Select
      label={label}
      size="xs"
      description={selected ? selected.model : `没有可用的${label}`}
      data={candidates.map((item) => ({
        value: item.id,
        label: item.configured ? `${item.label} · ${item.model}` : `${item.label} · ${item.model}（未配置）`,
      }))}
      value={value}
      onChange={onChange}
      allowDeselect={false}
      renderOption={({ option }) => {
        const provider = candidates.find((item) => item.id === option.value)
        return (
          <Group justify="space-between" w="100%" gap="xs">
            <Text size="xs">{option.label}</Text>
            <Group gap={4}>
              {provider && capabilitiesOf(provider).filter((item) => item !== 'chat').map((item) => (
                <Badge key={item} size="xs" variant="light">
                  {item}
                </Badge>
              ))}
            </Group>
          </Group>
        )
      }}
    />
  )
}
