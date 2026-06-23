import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { extractErrorMessage, tomcatApi } from '@/api/tmam'

export const useTomcatStore = defineStore('tomcat', () => {
  const services = ref([])
  const tomcatStatus = ref('STOPPED')
  const externallyManaged = ref(false)
  const loading = ref(false)
  const actionLoading = ref(false)
  const enabledDirty = ref(false)

  const isRunning = computed(() => tomcatStatus.value === 'RUNNING')
  const enabledCount = computed(() => services.value.filter((s) => s.enabled).length)

  async function refresh() {
    const preservedEnabled = enabledDirty.value
      ? Object.fromEntries(services.value.map((s) => [s.name, s.enabled]))
      : null

    loading.value = true
    try {
      const [servicesRes, statusRes] = await Promise.all([
        tomcatApi.services(),
        tomcatApi.status(),
      ])
      services.value = (servicesRes.data ?? []).map((service) => ({
        ...service,
        enabled: preservedEnabled?.[service.name] ?? service.enabled,
      }))
      tomcatStatus.value = statusRes.data?.status ?? 'STOPPED'
      externallyManaged.value = !!statusRes.data?.externallyManaged
    } catch {
      services.value = []
      tomcatStatus.value = 'STOPPED'
      externallyManaged.value = false
    } finally {
      loading.value = false
    }
  }

  function setLocalEnabled(name, enabled) {
    enabledDirty.value = true
    services.value = services.value.map((service) =>
      service.name === name ? { ...service, enabled } : service,
    )
  }

  function clearEnabledDirty() {
    enabledDirty.value = false
  }

  async function saveEnabledSelection() {
    const payload = Object.fromEntries(services.value.map((s) => [s.name, s.enabled]))
    await tomcatApi.updateEnabled(payload)
    enabledDirty.value = false
  }

  return {
    services,
    tomcatStatus,
    externallyManaged,
    loading,
    actionLoading,
    isRunning,
    enabledCount,
    refresh,
    setLocalEnabled,
    clearEnabledDirty,
    saveEnabledSelection,
    extractErrorMessage,
  }
})
