import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import RealtimeMetricsWidget from '@/components/dashboard/RealtimeMetricsWidget.vue'

// Mock the LineChart component
vi.mock('@/components/charts/LineChart.vue', () => ({
  default: {
    name: 'LineChart',
    template: '<div class="mock-line-chart"></div>',
    props: ['datasets', 'height', 'showLegend', 'showGrid', 'animate', 'yAxisMin', 'yAxisMax', 'updateMode']
  }
}))

describe('RealtimeMetricsWidget', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  const mockData = [
    { timestamp: new Date('2024-01-01T00:00:00Z'), value: 50 },
    { timestamp: new Date('2024-01-01T00:01:00Z'), value: 55 },
    { timestamp: new Date('2024-01-01T00:02:00Z'), value: 60 },
  ]

  it('should render correctly with basic props', () => {
    const wrapper = mount(RealtimeMetricsWidget, {
      props: {
        title: 'CPU Usage',
        metricKey: 'cpuUsage',
        unit: '%',
        data: mockData
      }
    })

    expect(wrapper.find('.metric-widget').exists()).toBe(true)
    expect(wrapper.text()).toContain('CPU Usage')
    expect(wrapper.text()).toContain('60.0%') // Current value (last data point)
  })

  it('should display current value with correct formatting', () => {
    const largeValueData = [
      { timestamp: new Date(), value: 1500000 }
    ]

    const wrapper = mount(RealtimeMetricsWidget, {
      props: {
        title: 'Network Traffic',
        metricKey: 'networkTraffic',
        unit: ' MB/s',
        data: largeValueData
      }
    })

    expect(wrapper.text()).toContain('1.5M MB/s')
  })

  it('should show correct status class based on thresholds', () => {
    const wrapper = mount(RealtimeMetricsWidget, {
      props: {
        title: 'CPU Usage',
        metricKey: 'cpuUsage',
        unit: '%',
        data: [{ timestamp: new Date(), value: 85 }],
        warningThreshold: 70,
        criticalThreshold: 90
      }
    })

    expect(wrapper.find('.value-warning').exists()).toBe(true)
  })

  it('should show critical status for values above critical threshold', () => {
    const wrapper = mount(RealtimeMetricsWidget, {
      props: {
        title: 'CPU Usage',
        metricKey: 'cpuUsage',
        unit: '%',
        data: [{ timestamp: new Date(), value: 95 }],
        warningThreshold: 70,
        criticalThreshold: 90
      }
    })

    expect(wrapper.find('.value-critical').exists()).toBe(true)
  })

  it('should calculate and display trend correctly', async () => {
    const trendData = [
      { timestamp: new Date('2024-01-01T00:00:00Z'), value: 50 },
      { timestamp: new Date('2024-01-01T00:01:00Z'), value: 55 },
      { timestamp: new Date('2024-01-01T00:02:00Z'), value: 60 },
    ]

    const wrapper = mount(RealtimeMetricsWidget, {
      props: {
        title: 'CPU Usage',
        metricKey: 'cpuUsage',
        unit: '%',
        data: trendData
      }
    })

    await wrapper.vm.$nextTick()

    // Should show upward trend (20% increase from 50 to 60)
    expect(wrapper.find('.trend-up').exists()).toBe(true)
    expect(wrapper.text()).toContain('↗')
  })

  it('should show thresholds when enabled', () => {
    const wrapper = mount(RealtimeMetricsWidget, {
      props: {
        title: 'CPU Usage',
        metricKey: 'cpuUsage',
        unit: '%',
        data: mockData,
        showThresholds: true,
        warningThreshold: 70,
        criticalThreshold: 90
      }
    })

    expect(wrapper.text()).toContain('Warning')
    expect(wrapper.text()).toContain('70%')
    expect(wrapper.text()).toContain('Critical')
    expect(wrapper.text()).toContain('90%')
  })

  it('should hide thresholds when disabled', () => {
    const wrapper = mount(RealtimeMetricsWidget, {
      props: {
        title: 'CPU Usage',
        metricKey: 'cpuUsage',
        unit: '%',
        data: mockData,
        showThresholds: false,
        warningThreshold: 70,
        criticalThreshold: 90
      }
    })

    expect(wrapper.find('.thresholds').exists()).toBe(false)
  })

  it('should emit refresh event when refresh button is clicked', async () => {
    const wrapper = mount(RealtimeMetricsWidget, {
      props: {
        title: 'CPU Usage',
        metricKey: 'cpuUsage',
        unit: '%',
        data: mockData
      }
    })

    await wrapper.find('.widget-action-btn').trigger('click')
    expect(wrapper.emitted('refresh')).toBeTruthy()
  })

  it('should auto-refresh at specified interval', async () => {
    const wrapper = mount(RealtimeMetricsWidget, {
      props: {
        title: 'CPU Usage',
        metricKey: 'cpuUsage',
        unit: '%',
        data: mockData,
        refreshInterval: 1000
      }
    })

    // Fast-forward time by 1 second
    vi.advanceTimersByTime(1000)
    await wrapper.vm.$nextTick()

    expect(wrapper.emitted('refresh')).toBeTruthy()
  })

  it('should limit data points to maxDataPoints', () => {
    const manyDataPoints = Array.from({ length: 100 }, (_, i) => ({
      timestamp: new Date(Date.now() + i * 1000),
      value: Math.random() * 100
    }))

    const wrapper = mount(RealtimeMetricsWidget, {
      props: {
        title: 'CPU Usage',
        metricKey: 'cpuUsage',
        unit: '%',
        data: manyDataPoints,
        maxDataPoints: 20
      }
    })

    const chartComponent = wrapper.findComponent({ name: 'LineChart' })
    const datasets = chartComponent.props('datasets')
    
    expect(datasets[0].data.length).toBe(20)
  })

  it('should handle loading state', () => {
    const wrapper = mount(RealtimeMetricsWidget, {
      props: {
        title: 'CPU Usage',
        metricKey: 'cpuUsage',
        unit: '%',
        data: mockData,
        isLoading: true
      }
    })

    expect(wrapper.find('.widget-loading').exists()).toBe(true)
  })

  it('should handle error state', () => {
    const wrapper = mount(RealtimeMetricsWidget, {
      props: {
        title: 'CPU Usage',
        metricKey: 'cpuUsage',
        unit: '%',
        data: mockData,
        error: 'Failed to load data'
      }
    })

    expect(wrapper.text()).toContain('Failed to load data')
  })

  it('should pass correct props to LineChart', () => {
    const wrapper = mount(RealtimeMetricsWidget, {
      props: {
        title: 'CPU Usage',
        metricKey: 'cpuUsage',
        unit: '%',
        data: mockData,
        chartHeight: '200px',
        showGrid: false,
        yAxisMin: 0,
        yAxisMax: 100
      }
    })

    const chartComponent = wrapper.findComponent({ name: 'LineChart' })
    
    expect(chartComponent.props('height')).toBe('200px')
    expect(chartComponent.props('showGrid')).toBe(false)
    expect(chartComponent.props('yAxisMin')).toBe(0)
    expect(chartComponent.props('yAxisMax')).toBe(100)
    expect(chartComponent.props('showLegend')).toBe(false)
    expect(chartComponent.props('animate')).toBe(false)
  })
})