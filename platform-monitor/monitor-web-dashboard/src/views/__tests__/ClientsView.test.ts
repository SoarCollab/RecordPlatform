import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ClientsView from '../ClientsView.vue'
import { useClientsStore } from '@/stores/clients'
import { useWebSocketStore } from '@/stores/websocket'
import { createMountingOptions, mockClients, flushPromises } from '@/test/utils'

// Mock the API
vi.mock('@/services/api', () => ({
  clientApi: {
    getClients: vi.fn(),
    getClient: vi.fn(),
    updateClient: vi.fn(),
    deleteClient: vi.fn(),
    getClientMetrics: vi.fn(),
  }
}))

describe('ClientsView', () => {
  let wrapper: any
  let clientsStore: any
  let webSocketStore: any

  beforeEach(() => {
    const mountingOptions = createMountingOptions()
    wrapper = mount(ClientsView, mountingOptions)
    
    clientsStore = useClientsStore()
    webSocketStore = useWebSocketStore()
    
    vi.clearAllMocks()
  })

  it('should render correctly', () => {
    expect(wrapper.find('.clients-view').exists()).toBe(true)
    expect(wrapper.find('.clients-title').text()).toBe('Clients')
    expect(wrapper.find('.filters-section').exists()).toBe(true)
  })

  it('should display clients when loaded', async () => {
    // Mock clients data
    clientsStore.clients = mockClients
    clientsStore.isLoading = false
    
    await wrapper.vm.$nextTick()
    
    expect(wrapper.findAll('.client-card')).toHaveLength(2)
    expect(wrapper.text()).toContain('Test Server 1')
    expect(wrapper.text()).toContain('Test Server 2')
  })

  it('should show loading state', async () => {
    clientsStore.isLoading = true
    clientsStore.clients = []
    
    await wrapper.vm.$nextTick()
    
    expect(wrapper.find('.loading-state').exists()).toBe(true)
    expect(wrapper.text()).toContain('Loading clients...')
  })

  it('should show empty state when no clients', async () => {
    clientsStore.isLoading = false
    clientsStore.clients = []
    
    await wrapper.vm.$nextTick()
    
    expect(wrapper.find('.empty-state').exists()).toBe(true)
    expect(wrapper.text()).toContain('No clients found')
  })

  it('should filter clients by search query', async () => {
    clientsStore.clients = mockClients
    clientsStore.filteredClients = mockClients.filter(c => 
      c.name.toLowerCase().includes('server 1')
    )
    
    const searchInput = wrapper.find('.search-input')
    await searchInput.setValue('server 1')
    
    await wrapper.vm.$nextTick()
    
    // Should show filtered results
    expect(wrapper.findAll('.client-card')).toHaveLength(1)
    expect(wrapper.text()).toContain('Test Server 1')
    expect(wrapper.text()).not.toContain('Test Server 2')
  })

  it('should filter clients by status', async () => {
    clientsStore.clients = mockClients
    clientsStore.filteredClients = mockClients.filter(c => c.status === 'active')
    
    const statusSelect = wrapper.find('select[value="all"]').at(0)
    await statusSelect.setValue('active')
    
    await wrapper.vm.$nextTick()
    
    // Should show only active clients
    expect(wrapper.findAll('.client-card')).toHaveLength(1)
  })

  it('should navigate to client detail when card is clicked', async () => {
    clientsStore.clients = mockClients
    
    await wrapper.vm.$nextTick()
    
    const clientCard = wrapper.find('.client-card')
    await clientCard.trigger('click')
    
    // Should navigate to client detail page
    expect(wrapper.vm.$router.currentRoute.value.name).toBe('client-detail')
    expect(wrapper.vm.$router.currentRoute.value.params.id).toBe('1')
  })

  it('should refresh clients when refresh button is clicked', async () => {
    const fetchClientsSpy = vi.spyOn(clientsStore, 'fetchClients').mockResolvedValue()
    
    const refreshButton = wrapper.find('button:has-text("Refresh")')
    await refreshButton.trigger('click')
    
    expect(fetchClientsSpy).toHaveBeenCalled()
  })

  it('should display client status correctly', async () => {
    clientsStore.clients = mockClients
    
    await wrapper.vm.$nextTick()
    
    const clientCards = wrapper.findAll('.client-card')
    
    // First client should be active
    expect(clientCards[0].find('.client-status.active').exists()).toBe(true)
    expect(clientCards[0].text()).toContain('active')
    
    // Second client should be inactive
    expect(clientCards[1].find('.client-status.inactive').exists()).toBe(true)
    expect(clientCards[1].text()).toContain('inactive')
  })

  it('should display client metrics from WebSocket', async () => {
    clientsStore.clients = mockClients
    
    // Mock WebSocket metrics
    webSocketStore.realtimeMetrics.set('client-1', {
      clientId: 'client-1',
      timestamp: new Date().toISOString(),
      cpuUsage: 75,
      memoryUsage: 60,
      diskUsage: 45,
      networkIn: 1000,
      networkOut: 500,
      loadAverage: 1.5,
      status: 'active'
    })
    
    await wrapper.vm.$nextTick()
    
    const firstClientCard = wrapper.findAll('.client-card')[0]
    expect(firstClientCard.text()).toContain('75%') // CPU usage
    expect(firstClientCard.text()).toContain('60%') // Memory usage
    expect(firstClientCard.text()).toContain('45%') // Disk usage
  })

  it('should subscribe to WebSocket updates for active clients', async () => {
    const subscribeToClientSpy = vi.spyOn(webSocketStore, 'subscribeToClient')
    
    clientsStore.clients = mockClients
    
    await wrapper.vm.$nextTick()
    
    // Should subscribe to active clients
    expect(subscribeToClientSpy).toHaveBeenCalledWith('client-1')
    // Should not subscribe to inactive clients
    expect(subscribeToClientSpy).not.toHaveBeenCalledWith('client-2')
  })

  it('should handle route query parameters for search', async () => {
    // Mock route with search query
    wrapper.vm.$route.query = { search: 'server1' }
    
    const setFilterSpy = vi.spyOn(clientsStore, 'setFilter')
    
    // Trigger the watcher manually
    await wrapper.vm.$options.watch['$route.query'].call(wrapper.vm, { search: 'server1' })
    
    expect(setFilterSpy).toHaveBeenCalledWith({ search: 'server1' })
  })

  it('should format heartbeat time correctly', () => {
    const recentTime = new Date(Date.now() - 60000).toISOString() // 1 minute ago
    
    // Call the formatHeartbeat method directly
    const formatted = wrapper.vm.formatHeartbeat(recentTime)
    
    expect(formatted).toContain('minute')
    expect(formatted).toContain('ago')
  })

  it('should handle client environment badges', async () => {
    clientsStore.clients = mockClients
    
    await wrapper.vm.$nextTick()
    
    const clientCards = wrapper.findAll('.client-card')
    
    // Check environment badges
    expect(clientCards[0].text()).toContain('prod')
    expect(clientCards[1].text()).toContain('staging')
  })

  it('should handle client region display', async () => {
    clientsStore.clients = mockClients
    
    await wrapper.vm.$nextTick()
    
    const clientCards = wrapper.findAll('.client-card')
    
    // Check region display
    expect(clientCards[0].text()).toContain('us-east-1')
    expect(clientCards[1].text()).toContain('us-west-1')
  })

  it('should handle multiple filter combinations', async () => {
    clientsStore.clients = mockClients
    
    // Mock filtered results for multiple filters
    clientsStore.filteredClients = mockClients.filter(c => 
      c.status === 'active' && c.environment === 'prod'
    )
    
    await wrapper.vm.$nextTick()
    
    // Should show only clients matching all filters
    expect(wrapper.findAll('.client-card')).toHaveLength(1)
    expect(wrapper.text()).toContain('Test Server 1')
  })

  it('should handle error state gracefully', async () => {
    clientsStore.isLoading = false
    clientsStore.error = 'Failed to load clients'
    clientsStore.clients = []
    
    await wrapper.vm.$nextTick()
    
    // Should show error message or empty state
    expect(wrapper.find('.empty-state').exists()).toBe(true)
  })
})