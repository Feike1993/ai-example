import { createTheme, type MantineColorsTuple } from '@mantine/core'

/** 海藻绿：主色，避开 Mantine 默认蓝紫。 */
const seaweed: MantineColorsTuple = [
  '#eef6f3',
  '#d4e8e0',
  '#a9d0c2',
  '#7bb8a3',
  '#549e88',
  '#2a6f5f',
  '#245f51',
  '#1c4b40',
  '#153830',
  '#0d2621',
]

/**
 * Playground 主题：墨色字 + 海藻绿强调，工具页而非后台模板。
 */
export const theme = createTheme({
  primaryColor: 'seaweed',
  colors: { seaweed },
  defaultRadius: 6,
  fontFamily: 'Figtree, "Noto Sans SC", sans-serif',
  headings: {
    fontFamily: 'Syne, "Noto Sans SC", sans-serif',
    fontWeight: '700',
  },
  black: '#14241c',
  cursorType: 'pointer',
})
