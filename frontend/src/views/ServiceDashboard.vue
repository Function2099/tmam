<template>
  <div class="service-dashboard">
    <div class="toolbar">
      <div class="toolbar-left">
        <h2>Tomcat Service 管理</h2>
        <el-tag :type="tomcatTagType" size="large">{{ tomcatStatusLabel }}</el-tag>
        <el-tag v-if="externallyManaged" type="warning" size="small">
          Tomcat 非 TMAM 啟動
        </el-tag>
        <el-tag v-if="nginxStatus" :type="nginxStatus.available ? 'success' : 'info'" size="small">
          Nginx {{ nginxStatus.available ? '可用' : '未安裝' }}
        </el-tag>
      </div>
      <div class="toolbar-actions">
        <el-button type="success" @click="openCreateDialog">新增路徑型系統</el-button>
        <el-button :loading="actionLoading" @click="refresh">重新整理</el-button>
        <el-button type="primary" :loading="actionLoading" :disabled="isRunning" @click="handleStart">
          全部啟動
        </el-button>
        <el-button type="danger" :loading="actionLoading" :disabled="!isRunning" @click="handleStop">
          全部停止
        </el-button>
        <el-button @click="$router.push('/logs')">查看 Log</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="hint-alert"
      title="IP 型 Service 切換會整台 Tomcat 重啟（約 50~90 秒）。路徑型系統透過 Nginx 分流，不需新增網卡 IP。"
    />

    <el-table v-loading="loading" :data="services" stripe border>
      <el-table-column label="啟動" width="70" align="center">
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
      <el-table-column label="操作" width="180" fixed="right" align="center">
        <template #default="{ row }">
          <template v-if="row.type === 'PATH_PROXY'">
            <el-button link type="primary" @click="openEditDialog(row)">編輯</el-button>
            <el-button link type="danger" :loading="actionLoading" @click="handleDelete(row)">刪除</el-button>
          </template>
          <template v-else>
            <el-button
              link
              :type="row.enabled ? 'danger' : 'primary'"
              :loading="actionLoading"
              :disabled="row.enabled && legacyEnabledCount <= 1"
              @click="handleToggle(row)"
            >
              {{ row.enabled ? '停用' : '啟用' }}
            </el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <div class="footer-actions">
      <span class="footer-hint">已勾選 {{ enabledCount }} / {{ services.length }} 個 Service</span>
      <el-button type="primary" :loading="actionLoading" @click="handleApply">
        套用勾選並啟動
      </el-button>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新增路徑型系統' : '編輯路徑型系統'"
      width="520px"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="110px">
        <el-form-item v-if="dialogMode === 'create'" label="系統名稱" prop="name">
          <el-input v-model="form.name" placeholder="例如 New_System" />
        </el-form-item>
        <el-form-item label="顯示名稱" prop="displayName">
          <el-input v-model="form.displayName" placeholder="儀表板顯示名稱" />
        </el-form-item>
        <el-form-item label="路徑前綴" prop="pathPrefix">
          <el-input v-model="form.pathPrefix" placeholder="/new-system" />
        </el-form-item>
        <el-form-item label="webapp 目錄" prop="docBase">
          <el-input v-model="form.docBase" placeholder="D:\Work_Java\NewSystem\web">
            <template #append>
              <el-button @click="browseDocBase">瀏覽</el-button>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item label="啟用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="剝除前綴轉發">
          <el-switch v-model="form.proxyStripPrefix" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="actionLoading" @click="submitDialog">儲存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import { nginxApi, tomcatApi } from '@/api/tmam'
import { useTomcatStore } from '@/stores/tomcat'

const store = useTomcatStore()
const {
  services,
  tomcatStatus,
  externallyManaged,
  loading,
  actionLoading,
  isRunning,
  enabledCount,
} = storeToRefs(store)

const nginxStatus = ref(null)
const dialogVisible = ref(false)
const dialogMode = ref('create')
const editingName = ref('')
const formRef = ref(null)
const form = reactive({
  name: '',
  displayName: '',
  pathPrefix: '',
  docBase: '',
  enabled: true,
  proxyStripPrefix: false,
})

const formRules = {
  name: [{ required: true, message: '請輸入系統名稱', trigger: 'blur' }],
  pathPrefix: [{ required: true, message: '請輸入路徑前綴', trigger: 'blur' }],
  docBase: [{ required: true, message: '請輸入 webapp 目錄', trigger: 'blur' }],
}

let pollingTimer = null

const legacyEnabledCount = computed(() =>
  services.value.filter((s) => s.type !== 'PATH_PROXY' && s.enabled).length,
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

onMounted(async () => {
  await refresh()
  pollingTimer = setInterval(() => store.refresh(), 5000)
})

onUnmounted(() => clearInterval(pollingTimer))

function canDisable(row) {
  if (!row.enabled) return false
  if (row.type === 'PATH_PROXY') return true
  return legacyEnabledCount.value > 1
}

function resetForm() {
  form.name = ''
  form.displayName = ''
  form.pathPrefix = ''
  form.docBase = ''
  form.enabled = true
  form.proxyStripPrefix = false
}

function openCreateDialog() {
  dialogMode.value = 'create'
  editingName.value = ''
  resetForm()
  dialogVisible.value = true
}

async function browseDocBase() {
  if (!window.tmam?.selectDirectory) {
    ElMessage.warning('目錄瀏覽僅支援 TMAM 桌面版')
    return
  }
  try {
    const selected = await window.tmam.selectDirectory(form.docBase || undefined)
    if (selected) {
      form.docBase = selected
      formRef.value?.validateField('docBase')
    }
  } catch {
    ElMessage.error('無法開啟目錄選擇器')
  }
}

function openEditDialog(row) {
  dialogMode.value = 'edit'
  editingName.value = row.name
  form.name = row.name
  form.displayName = row.displayName
  form.pathPrefix = row.pathPrefix
  form.docBase = row.docBase ?? ''
  form.enabled = row.enabled
  form.proxyStripPrefix = !!row.proxyStripPrefix
  dialogVisible.value = true
}

async function refresh() {
  await store.refresh()
  try {
    const { data } = await nginxApi.status()
    nginxStatus.value = data
  } catch {
    nginxStatus.value = null
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

async function submitDialog() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  await withAction(async () => {
    if (dialogMode.value === 'create') {
      await tomcatApi.createService({
        name: form.name,
        displayName: form.displayName || form.name,
        pathPrefix: form.pathPrefix,
        docBase: form.docBase,
        enabled: form.enabled,
        proxyStripPrefix: form.proxyStripPrefix,
      })
      ElMessage.success('路徑型系統已新增，請點「套用勾選並啟動」生效')
    } else {
      await tomcatApi.updateService(editingName.value, {
        displayName: form.displayName,
        pathPrefix: form.pathPrefix,
        docBase: form.docBase,
        enabled: form.enabled,
        proxyStripPrefix: form.proxyStripPrefix,
      })
      ElMessage.success('路徑型系統已更新，請點「套用勾選並啟動」生效')
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
    await tomcatApi.deleteService(row.name)
    ElMessage.success('已刪除，請點「套用勾選並啟動」更新執行環境')
  }).catch((e) => ElMessage.error(store.extractErrorMessage(e)))
}

async function handleStart() {
  await withAction(async () => {
    ElMessage.info('正在啟動 Tomcat，請稍候...')
    await store.saveEnabledSelection()
    const { data } = await tomcatApi.start()
    if (data?.success === false) {
      throw new Error(data.message)
    }
    ElMessage.success('Tomcat 啟動成功')
  }).catch((e) => ElMessage.error(store.extractErrorMessage(e)))
}

async function handleStop() {
  await withAction(async () => {
    await tomcatApi.stop()
    ElMessage.success('Tomcat 已停止')
  }).catch((e) => ElMessage.error(store.extractErrorMessage(e)))
}

async function handleApply() {
  await withAction(async () => {
    ElMessage.info('正在套用設定並啟動，請稍候（約 50~90 秒）...')
    await store.saveEnabledSelection()
    const { data } = await tomcatApi.apply()
    if (data?.success === false) {
      throw new Error(data.message)
    }
    ElMessage.success('設定已套用')
  }).catch((e) => ElMessage.error(store.extractErrorMessage(e)))
}

async function handleToggle(row) {
  const action = row.enabled ? '停用' : '啟用'
  try {
    await ElMessageBox.confirm(
      `${action} ${row.displayName} 會整台 Tomcat 重啟，期間所有 Service 短暫中斷。是否繼續？`,
      '確認操作',
      { type: 'warning' },
    )
  } catch {
    return
  }

  store.clearEnabledDirty()

  await withAction(async () => {
    ElMessage.info(`正在${action} ${row.displayName}...`)
    const { data } = await tomcatApi.toggle(row.name)
    if (data?.success === false) {
      throw new Error(data.message)
    }
    ElMessage.success(`${row.displayName} 已${action}`)
  }).catch((e) => ElMessage.error(store.extractErrorMessage(e)))
}

function onCheckboxChange(row, enabled) {
  if (!enabled && !canDisable(row)) {
    ElMessage.warning('至少需要保留一個啟用的 IP 型 Service')
    return
  }
  store.setLocalEnabled(row.name, enabled)
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

.toolbar-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.hint-alert {
  margin-bottom: 0;
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
