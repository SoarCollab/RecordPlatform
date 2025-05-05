import {
  defineConfig,
  presetAttributify,
  presetIcons,
  presetTypography,
  presetUno,
  transformerDirectives,
  transformerVariantGroup,
} from 'unocss'
import presetChinese from 'unocss-preset-chinese'
import presetEase from 'unocss-preset-ease'


// 导出一个默认配置对象，用于配置UnoCSS
export default defineConfig({
  // UnoCSS选项，用于定义配置的具体内容
  safelist: [
    'py-16px',
    'pb-16px',
  ],
  theme: {
    // 自定义主题设置，可以在此处添加颜色、字体等主题相关配置
    colors: {
      primary: 'var(--el-color-primary)',
      primary1: 'var(--el-color-primary-light-1)',
      primary2: 'var(--el-color-primary-light-2)',
      primary3: 'var(--el-color-primary-light-3)',
      primary4: 'var(--el-color-primary-light-4)',
      primary5: 'var(--el-color-primary-light-5)',
      primary6: 'var(--el-color-primary-light-6)',
      primary7: 'var(--el-color-primary-light-7)',
      primary8: 'var(--el-color-primary-light-8)',
      primary9: 'var(--el-color-primary-light-9)',
    }
  },
  presets: [
    // 预设配置，用于加载不同的样式和功能
    presetUno(),
    presetAttributify(),
    presetChinese(),
    presetEase(),
    presetTypography(),
    // 图标预设，用于配置图标的相关属性
    presetIcons({
      scale: 1.2,
      warn: true,
    }),
    // presetRemToPx({
    //     baseFontSize: 4,
    // }),
  ],
  shortcuts: [
    // 快捷方式定义，用于简化常用样式的编写
    ['flex-center', 'flex items-center justify-center'],
    ['flex-between', 'flex items-center justify-between'],
    ['flex-end', 'flex items-end justify-between'],
  ],
  transformers: [transformerDirectives(), transformerVariantGroup()],
  // 变换器配置，用于加载不同的指令和变体处理功能
})