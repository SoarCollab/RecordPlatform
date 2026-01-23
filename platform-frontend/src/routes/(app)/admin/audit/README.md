# 审计页面组件结构

## 概览

审计页面已从 1316 行的单文件重构为模块化的多组件架构，提供更好的可维护性和用户体验。

## 文件结构

```
src/routes/(app)/admin/audit/
├── +page.svelte                    # 主页面 (207 行)
├── components/
│   ├── AuditDashboard.svelte       # 仪表盘 (235 行)
│   ├── AuditLogList.svelte         # 日志列表 (208 行)
│   ├── SensitiveLogList.svelte     # 敏感操作 (227 行)
│   ├── charts/
│   │   ├── TrendChart.svelte       # 趋势折线图 (125 行)
│   │   ├── ErrorPieChart.svelte    # 错误饼图 (128 行)
│   │   └── HeatmapChart.svelte     # 热力图 (155 行)
│   ├── cards/
│   │   └── KpiCard.svelte          # KPI 卡片 (81 行)
│   └── dialogs/
│       ├── LogDetailDialog.svelte  # 日志详情弹窗 (98 行)
│       └── SettingsDrawer.svelte   # 设置抽屉 (242 行)
```

## 主要改进

### 1. 标签页精简：8 → 3

| 新标签       | 内容                                             |
| ------------ | ------------------------------------------------ |
| **仪表盘**   | KPI 卡片 + 趋势图 + 错误分布 + 热力图 + 高频告警 |
| **日志查询** | 审计日志列表 + 筛选 + 分页                       |
| **敏感操作** | 敏感操作列表 + 筛选 + 分页                       |

**工具和配置** → 右上角设置按钮

### 2. 数据可视化增强

使用 **Apache ECharts** 图表库：

- **TrendChart**: 7天操作趋势折线图 + 面积填充
- **ErrorPieChart**: 错误类型环形图，支持点击交互
- **HeatmapChart**: 24小时 x 7天热力图，带 tooltip

### 3. 交互优化

- **自动刷新**: 仪表盘每 30 秒自动刷新
- **设置抽屉**: 包含审计配置、异常检查、日志备份
- **快捷跳转**: KPI 卡片点击可跳转到对应视图
- **响应式**: 移动端 2x2 网格 KPI 卡片

### 4. 组件职责

#### 主页面 (+page.svelte)

- 标签页切换
- 导出日志
- 设置抽屉管理

#### AuditDashboard

- 加载概览数据
- 显示 KPI 卡片
- 渲染图表
- 30 秒自动刷新

#### AuditLogList / SensitiveLogList

- 日志列表展示
- 筛选和分页
- 详情弹窗

#### 图表组件

- 独立的 ECharts 封装
- 自动响应式调整
- 统一的加载和空状态

#### SettingsDrawer

- 审计配置编辑
- 异常检查工具
- 日志备份工具

## 技术栈

- **Svelte 5**: 使用 runes ($state, $derived, $effect)
- **ECharts 6.0**: 专业图表库
- **bits-ui**: UI 组件基础
- **Tailwind CSS 4**: 样式系统

## 使用示例

### 查看仪表盘

```typescript
// 自动加载并每 30 秒刷新
<AuditDashboard onViewHighFreq={() => activeTab = "sensitive"} />
```

### 自定义图表

```typescript
<TrendChart
  data={[{ date: "2024-01-01", count: 100 }]}
  title="自定义趋势"
  loading={false}
/>
```

### KPI 卡片交互

```typescript
<KpiCard
  title="高频告警"
  value={5}
  icon="alert"
  badge="查看"
  badgeVariant="destructive"
  onclick={() => handleViewAlerts()}
/>
```

## 性能优化

1. **按需加载**: 图表组件仅在标签页激活时渲染
2. **防抖刷新**: 30 秒自动刷新，避免频繁请求
3. **懒加载配置**: 设置抽屉打开时才加载配置数据
4. **Canvas 渲染**: ECharts 使用 Canvas 渲染器提升性能

## 响应式设计

- **桌面端**: 4 列 KPI 卡片，2 列图表
- **平板端**: 2 列 KPI 卡片，1 列图表
- **移动端**: 1 列布局，图表自适应宽度

## 未来扩展

- [ ] 添加更多图表类型（柱状图、雷达图）
- [ ] 支持自定义时间范围
- [ ] 导出图表为图片
- [ ] 实时 WebSocket 更新
- [ ] 自定义仪表盘布局
