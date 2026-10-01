import { defineStore } from 'pinia'
import { ref } from 'vue'
import { extractErrorMessage, tomcatsApi } from '@/api/tmam'

export const useTomcatInstancesStore = defineStore('tomcatInstances', () => {
  const instances = ref([])
  const statusMap = ref({})
  const loading = ref(false)
  const actionLoading = ref(false)

  async function fetchInstances() {
    const [listRes, statusRes] = await Promise.all([
      tomcatsApi.list(),
      tomcatsApi.allStatus(),
    ])
    instances.value = listRes.data ?? []
    statusMap.value = statusRes.data ?? {}
  }

  async function refresh() {
    loading.value = true
    try {
      await fetchInstances()
    } catch {
      instances.value = []
      statusMap.value = {}
    } finally {
      loading.value = false
    }
  }

  async function refreshStatus() {
    try {
      await fetchInstances()
    } catch {
      /* keep last known data */
    }
  }

  function displayStatus(id) {
    return statusMap.value[id] ?? instances.value.find((i) => i.id === id)?.status ?? 'STOPPED'
  }

  return {
    instances,
    statusMap,
    loading,
    actionLoading,
    refresh,
    refreshStatus,
    displayStatus,
    extractErrorMessage,
  }
})
