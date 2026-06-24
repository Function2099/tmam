import { defineStore } from 'pinia'
import { ref } from 'vue'
import { extractErrorMessage, tomcatsApi } from '@/api/tmam'

export const useTomcatInstancesStore = defineStore('tomcatInstances', () => {
  const instances = ref([])
  const statusMap = ref({})
  const loading = ref(false)
  const actionLoading = ref(false)

  async function refresh() {
    loading.value = true
    try {
      const [listRes, statusRes] = await Promise.all([
        tomcatsApi.list(),
        tomcatsApi.allStatus(),
      ])
      instances.value = listRes.data ?? []
      statusMap.value = statusRes.data ?? {}
    } catch {
      instances.value = []
      statusMap.value = {}
    } finally {
      loading.value = false
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
    displayStatus,
    extractErrorMessage,
  }
})
