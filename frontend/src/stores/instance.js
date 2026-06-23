import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { instanceApi, projectApi } from '@/api/tmam'

export const useInstanceStore = defineStore('instance', () => {
  const projects = ref([])
  const statusMap = ref({})
  const conflicts = ref([])
  const loading = ref(false)
  const pendingActions = ref({})

  const hasConflicts = computed(() => conflicts.value.length > 0)

  function setPending(name, action) {
    pendingActions.value = { ...pendingActions.value, [name]: action }
  }

  function clearPending(name) {
    const next = { ...pendingActions.value }
    delete next[name]
    pendingActions.value = next
  }

  function displayStatus(name) {
    if (pendingActions.value[name] === 'start' || pendingActions.value[name] === 'restart') {
      return 'STARTING'
    }
    return statusMap.value[name] ?? 'STOPPED'
  }

  async function refreshProjects() {
    loading.value = true
    try {
      const { data } = await projectApi.list()
      projects.value = data.map((p) => ({
        ...p,
        name: p.name ?? p.id,
      }))
      await refreshStatus()
      await refreshConflicts()
    } catch {
      projects.value = []
    } finally {
      loading.value = false
    }
  }

  async function refreshStatus() {
    try {
      const { data } = await instanceApi.allStatus()
      statusMap.value = data
    } catch {
      statusMap.value = {}
    }
  }

  async function refreshConflicts() {
    try {
      const { data } = await projectApi.checkConflicts()
      conflicts.value = data
    } catch {
      conflicts.value = []
    }
  }

  return {
    projects,
    statusMap,
    conflicts,
    loading,
    pendingActions,
    hasConflicts,
    displayStatus,
    setPending,
    clearPending,
    refreshProjects,
    refreshStatus,
    refreshConflicts,
  }
})
