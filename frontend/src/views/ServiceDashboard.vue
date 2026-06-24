<template>
  <div class="service-dashboard">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-button link @click="$router.push('/')">← 返回實例列表</el-button>
        <h2>{{ currentInstance?.displayName ?? tomcatId }} — Service 管理</h2>
        <el-tag :type="tomcatTagType" size="large">{{ tomcatStatusLabel }}</el-tag>
        <el-tag v-if="externallyManaged" type="warning" size="small">非 TMAM 啟動</el-tag>
        <el-tag v-if="nginxStatus" :type="nginxStatus.available ? 'success' : 'info'" size="small">
          Nginx {{ nginxStatus.available ? '可用' : '未安裝' }}
        </el-tag>
      </div>
      <div class="toolbar-actions">
        <el-button type="success" @click="openCreateDialog">新增系統</el-button>
        <el-button :loading="actionLoading" @click="refresh">重新整理</el-button>
        <el-button type="primary" :loading="actionLoading" :disabled="isRunning" @click="handleStart">
          全部啟動
        </el-button>
        <el-button type="danger" :loading="actionLoading" :disabled="!isRunning" @click="handleStop">
          全部停止
        </el-button>
        <el-button @click="$router.push(`/tomcats/${tomcatId}/logs`)">查看 Log</el-button>
      </div>
    </div>

    <p v-if="currentInstance" class="instance-meta">{{ currentInstance.catalinaHome }}</p>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="hint-alert"
      title="此 Tomcat 實例內的 Service 切換會重啟該實例（約 50~90 秒）。路徑型透過 Nginx 分流；IP 型需網卡綁定對應 IP。"
    />

    <div class="service-filter">
      <el-input
        v-model="searchQuery"
        clearable
        placeholder="搜尋 Service 名稱、路徑、位址..."
        class="search-input"
      >
        <template #prefix>
          <span class="search-icon">🔍</span>
        </template>
      </el-input>
      <span v-if="searchQuery.trim()" class="filter-hint">
        顯示 {{ filteredServices.length }} / {{ services.length }} 個 Service
      </span>
    </div>

    <el-table v-loading="loading" :data="filteredServices" stripe border>
      <el-table-column width="90" align="center">
        <template #header>
          <div class="enable-header">
            <el-checkbox
              :model-value="allServicesEnabled"
              :disabled="actionLoading || services.length === 0"
              @click.stop.prevent="toggleSelectAll"
            />
            <span>啟動</span>
          </div>
        </template>
        <template #default="{ row }">
          <el-checkbox
            :model-value="row.enabled"
            :disabled="actionLoading || (row.enabled && !canDisable(row))"
            @change="(val) => onCheckboxChange(row, val)"
          />
        </template>
      </el-table-column>
      <el-table-column prop="displayName" label="Service" min-width="140" />
      <el-table-column label="路徑前綴" min-width="120">
        <template #default="{ row }">
          {{ row.type === 'PATH_PROXY' ? row.pathPrefix : '—' }}
        </template>
      </el-table-column>
      <el-table-column label="位址" min-width="220">
        <template #default="{ row }">
          <span v-if="row.type === 'PATH_PROXY'">{{ row.publicUrl || row.pathPrefix }}</span>
          <span v-else>{{ row.address }}:{{ row.port }}</span>
        </template>
      </el-table-column>
      <el-table-column label="狀態" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="serviceTagType(row)" size="small">{{ serviceStatusLabel(row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="類型" width="90" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="row.type === 'PATH_PROXY' ? 'warning' : 'info'">
            {{ row.type === 'PATH_PROXY' ? '路徑' : 'IP' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" fixed="right" align="center" width="150">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEditDialog(row)">
            編輯
          </el-button>
          <el-button
            link
            type="danger"
            :loading="actionLoading"
            :disabled="!canDelete(row)"
            @click="handleDelete(row)"
          >
            刪除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="footer-actions">
      <span class="footer-hint">
        已勾選 {{ enabledCount }} / {{ services.length }} 個 Service
        <template v-if="enabledDirty">（有未套用的變更）</template>
      </span>
      <el-button type="primary" :loading="actionLoading" @click="handleApply">
        套用勾選並啟動
      </el-button>
    </div>

    <AddServiceDialog
      v-model="dialogVisible"
      :mode="dialogMode"
      :loading="actionLoading"
      :initial="editingRow"
      @submit="submitDialog"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import { nginxApi, tomcatsApi } from '@/api/tmam'
import { useTomcatStore } from '@/stores/tomcat'
import AddServiceDialog from '@/components/AddServiceDialog.vue'

const route = useRoute()
const tomcatId = computed(() => route.params.tomcatId)

const store = useTomcatStore()
const {
  services,
  tomcatStatus,
  externallyManaged,
  currentInstance,
  loading,
  actionLoading,
  isRunning,
  enabledCount,
  enabledDirty,
} = storeToRefs(store)

const nginxStatus = ref(null)
const dialogVisible = ref(false)
const dialogMode = ref('create')
const editingRow = ref(null)
const searchQuery = ref('')

let pollingTimer = null

const filteredServices = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return services.value
  return services.value.filter((service) => {
    const haystack = [
      service.displayName,
      service.name,
      service.pathPrefix,
      service.address,
      service.publicUrl,
      service.type === 'PATH_PROXY' ? '路徑' : 'IP',
      String(service.port ?? ''),
    ]
      .filter(Boolean)
      .join(' ')
      .toLowerCase()
    return haystack.includes(q)
  })
})

const allServicesEnabled = computed(
  () => services.value.length > 0 && services.value.every((s) => s.enabled),
)

const tomcatStatusLabel = computed(() => ({
  RUNNING: '運行中',
  STOPPED: '已停止',
  STARTING: '啟動中',
  ERROR: '異常',
}[tomcatStatus.value] ?? '未知'))

const tomcatTagType = computed(() => ({
  RUNNING: 'success',
  STOPPED: 'info',
  STARTING: 'warning',
  ERROR: 'danger',
}[tomcatStatus.value] ?? 'info'))

watch(tomcatId, async (id) => {
  if (!id) return
  store.setCurrentTomcat(id)
  await refresh()
}, { immediate: true })

onMounted(() => {
  pollingTimer = setInterval(() => store.refreshStatus(), 5000)
})

onUnmounted(() => {
  clearInterval(pollingTimer)
  store.discardEnabledChanges()
})

onBeforeRouteLeave(() => {
  store.discardEnabledChanges()
})

function canDisable(row) {
  if (!row.enabled) return false
  return services.value.filter((s) => s.enabled).length > 1
}

function canDelete(row) {
  if (services.value.length <= 1) return false
  if (!row.enabled) return true
  return services.value.filter((s) => s.enabled).length > 1
}

function openCreateDialog() {
  dialogMode.value = 'create'
  editingRow.value = null
  dialogVisible.value = true
}

function openEditDialog(row) {
  dialogMode.value = 'edit'
  editingRow.value = { ...row }
  dialogVisible.value = true
}

async function refresh() {
  const hadDirty = await store.refresh()
  try {
    const { data } = await nginxApi.status()
    nginxStatus.value = data
  } catch {
    nginxStatus.value = null
  }
  if (hadDirty) {
    ElMessage.info('已還原為目前實際啟用中的 Service')
  }
}

async function withAction(fn) {
  actionLoading.value = true
  try {
    return await fn()
  } finally {
    actionLoading.value = false
    await refresh()
  }
}

async function submitDialog(form) {
  await withAction(async () => {
    const id = tomcatId.value
    if (dialogMode.value === 'create') {
      await tomcatsApi.createService(id, {
        type: form.type,
        name: form.name,
        displayName: form.displayName || form.name,
        pathPrefix: form.pathPrefix,
        docBase: form.docBase,
        address: form.address,
        port: form.port,
        enabled: form.enabled,
        proxyStripPrefix: form.proxyStripPrefix,
      })
      ElMessage.success('系統已新增，請點「套用勾選並啟動」生效')
    } else {
      await tomcatsApi.updateService(id, editingRow.value.name, {
        displayName: form.displayName,
        pathPrefix: form.pathPrefix,
        docBase: form.docBase,
        address: form.address,
        port: form.port,
        enabled: form.enabled,
        proxyStripPrefix: form.proxyStripPrefix,
      })
      ElMessage.success('已更新，請點「套用勾選並啟動」生效')
    }
    dialogVisible.value = false
  }).catch((e) => ElMessage.error(store.extractErrorMessage(e)))
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`確定刪除 ${row.displayName}？`, '確認刪除', { type: 'warning' })
  } catch {
    return
  }
  await withAction(async () => {
    await tomcatsApi.deleteService(tomcatId.value, row.name)
    ElMessage.success('已刪除，請點「套用勾選並啟動」生效')
  }).catch((e) => ElMessage.error(store.extractErrorMessage(e)))
}

async function handleStart() {
  await withAction(async () => {
    await store.saveEnabledSelection()
    const { data } = await tomcatsApi.start(tomcatId.value)
    if (data?.success === false) throw new Error(data.message)
    ElMessage.success('Tomcat 啟動成功')
  }).catch((e) => ElMessage.error(store.extractErrorMessage(e)))
}

async function handleStop() {
  await withAction(async () => {
    await tomcatsApi.stop(tomcatId.value)
    ElMessage.success('Tomcat 已停止')
  }).catch((e) => ElMessage.error(store.extractErrorMessage(e)))
}

async function handleApply() {
  const changes = store.getEnabledChanges()
  await withAction(async () => {
    ElMessage.info('正在套用設定並啟動，請稍候（約 50~90 秒）...')
    await store.saveEnabledSelection()
    const { data } = await tomcatsApi.apply(tomcatId.value)
    if (data?.success === false) throw new Error(data.message)

    const parts = []
    if (changes.enabled.length) parts.push(`啟用：${changes.enabled.join('、')}`)
    if (changes.disabled.length) parts.push(`停用：${changes.disabled.join('、')}`)
    const detail = parts.length ? `（${parts.join('；')}）` : ''
    ElMessage.success(`設定已套用並啟動${detail}。詳情可至「查看 Log」→ TMAM 操作日誌`)
  }).catch((e) => ElMessage.error(store.extractErrorMessage(e)))
}

function onCheckboxChange(row, enabled) {
  if (!enabled && !canDisable(row)) {
    ElMessage.warning('至少需要保留一個 Service')
    return
  }
  store.setLocalEnabled(row.name, enabled)
}

function toggleSelectAll() {
  if (actionLoading.value || services.value.length === 0) return

  if (allServicesEnabled.value) {
    store.enableOnlyFirstService()
    ElMessage.info(`已僅保留「${store.getFirstServiceLabel()}」啟用`)
    return
  }

  store.enableAllServices()
}

function serviceStatusLabel(row) {
  if (!row.enabled) return '未啟用'
  if (!isRunning.value) return '已停止'
  return row.status === 'RUNNING' ? '運行中' : '異常'
}

function serviceTagType(row) {
  if (!row.enabled) return 'info'
  if (!isRunning.value) return 'info'
  return row.status === 'RUNNING' ? 'success' : 'danger'
}
</script>

<style scoped>
.service-dashboard {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.toolbar-left h2 {
  margin: 0;
}

.instance-meta {
  margin: 0;
  font-size: 13px;
  color: #909399;
  word-break: break-all;
}

.toolbar-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.hint-alert {
  margin-bottom: 0;
}

.service-filter {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.search-input {
  max-width: 360px;
}

.search-icon {
  font-size: 14px;
  line-height: 1;
}

.filter-hint {
  font-size: 13px;
  color: #909399;
}

.enable-header {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  line-height: 1.2;
}

.footer-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.footer-hint {
  color: #606266;
  font-size: 14px;
}
</style>
