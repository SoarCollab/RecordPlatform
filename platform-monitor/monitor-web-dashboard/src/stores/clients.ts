import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { clientApi } from '@/services/api'
import type { Client, ClientMetrics, ClientFilter } from '@/types/client'

export const useClientsStore = defineStore('clients', () => {
  const clients = ref<Client[]>([])
  const selectedClient = ref<Client | null>(null)
  const clientMetrics = ref<Map<string, ClientMetrics>>(new Map())
  const isLoading = ref(false)
  const error = ref<string | null>(null)
  const filter = ref<ClientFilter>({
    search: '',
    status: 'all',
    region: 'all',
    environment: 'all'
  })

  const filteredClients = computed(() => {
    return clients.value.filter(client => {
      const matchesSearch = !filter.value.search || 
        client.name.toLowerCase().includes(filter.value.search.toLowerCase()) ||
        client.hostname.toLowerCase().includes(filter.value.search.toLowerCase())
      
      const matchesStatus = filter.value.status === 'all' || client.status === filter.value.status
      const matchesRegion = filter.value.region === 'all' || client.region === filter.value.region
      const matchesEnvironment = filter.value.environment === 'all' || client.environment === filter.value.environment
      
      return matchesSearch && matchesStatus && matchesRegion && matchesEnvironment
    })
  })

  const activeClients = computed(() => 
    clients.value.filter(client => client.status === 'active')
  )

  const clientsCount = computed(() => ({
    total: clients.value.length,
    active: activeClients.value.length,
    inactive: clients.value.filter(c => c.status === 'inactive').length,
    maintenance: clients.value.filter(c => c.status === 'maintenance').length
  }))

  const fetchClients = async (): Promise<void> => {
    isLoading.value = true
    error.value = null
    
    try {
      clients.value = await clientApi.getClients()
    } catch (err: any) {
      error.value = err.message || 'Failed to fetch clients'
      throw err
    } finally {
      isLoading.value = false
    }
  }

  const fetchClient = async (clientId: string): Promise<Client> => {
    try {
      const client = await clientApi.getClient(clientId)
      selectedClient.value = client
      
      // Update client in the list if it exists
      const index = clients.value.findIndex(c => c.id === clientId)
      if (index !== -1) {
        clients.value[index] = client
      }
      
      return client
    } catch (err: any) {
      error.value = err.message || 'Failed to fetch client'
      throw err
    }
  }

  const fetchClientMetrics = async (clientId: string, timeRange?: string): Promise<ClientMetrics> => {
    try {
      const metrics = await clientApi.getClientMetrics(clientId, timeRange)
      clientMetrics.value.set(clientId, metrics)
      return metrics
    } catch (err: any) {
      error.value = err.message || 'Failed to fetch client metrics'
      throw err
    }
  }

  const updateClient = async (clientId: string, updates: Partial<Client>): Promise<void> => {
    try {
      const updatedClient = await clientApi.updateClient(clientId, updates)
      
      const index = clients.value.findIndex(c => c.id === clientId)
      if (index !== -1) {
        clients.value[index] = updatedClient
      }
      
      if (selectedClient.value?.id === clientId) {
        selectedClient.value = updatedClient
      }
    } catch (err: any) {
      error.value = err.message || 'Failed to update client'
      throw err
    }
  }

  const deleteClient = async (clientId: string): Promise<void> => {
    try {
      await clientApi.deleteClient(clientId)
      
      clients.value = clients.value.filter(c => c.id !== clientId)
      clientMetrics.value.delete(clientId)
      
      if (selectedClient.value?.id === clientId) {
        selectedClient.value = null
      }
    } catch (err: any) {
      error.value = err.message || 'Failed to delete client'
      throw err
    }
  }

  const setFilter = (newFilter: Partial<ClientFilter>): void => {
    filter.value = { ...filter.value, ...newFilter }
  }

  const clearFilter = (): void => {
    filter.value = {
      search: '',
      status: 'all',
      region: 'all',
      environment: 'all'
    }
  }

  return {
    clients,
    selectedClient,
    clientMetrics,
    isLoading,
    error,
    filter,
    filteredClients,
    activeClients,
    clientsCount,
    fetchClients,
    fetchClient,
    fetchClientMetrics,
    updateClient,
    deleteClient,
    setFilter,
    clearFilter
  }
})