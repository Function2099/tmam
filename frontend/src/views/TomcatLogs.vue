<template>
  <div class="logs-view">
    <div class="logs-header">
      <div>
        <el-button link @click="$router.push(`/tomcats/${tomcatId}`)">← 返回 Service 管理</el-button>
        <h2>Log 檢視 — {{ tomcatId }}</h2>
      </div>
      <div class="logs-actions">
        <el-switch v-model="autoRefresh" active-text="自動更新" />
        <el-select v-model="lineCount" style="width: 120px" @change="reloadLogs">
          <el-option :value="50" label="50 行" />
          <el-option :value="100" label="100 行" />
          <el-option :value="200" label="200 行" />
          <el-option :value="500" label="500 行" />
        </el-select>
        <el-button @click="openLogsFolder">開啟資料夾</el-button>
        <el-button :loading="loading" @click="reloadLogs">重新整理</el-button>
      </div>
    </div>

    <LogSourceTabs v-model="activeTab" @change="reloadLogs" />

    <el-alert
      v-if="loadError"
      type="error"
      :closable="false"
      show-icon
      class="log-error"
      :title="loadError"
    />

    <pre ref="logBox" class="log-content">{{ logText }}</pre>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import LogSourceTabs from '@/components/LogSourceTabs.vue'
import { extractErrorMessage, tomcatApi, tomcatsApi } from '@/api/tmam'

const route = useRoute()
const tomcatId = computed(() => route.params.tomcatId)

const lines = ref([])
const loading = ref(false)
const autoRefresh = ref(true)
const lineCount = ref(200)
const logBox = ref(null)
const activeTab = ref('tomcat')
const loadError = ref('')

let pollingTimer = null
let requestSeq = 0

const logText = computed(() => {
  if (lines.value.length === 0) {
    return loadError.value || '（尚無日誌）'
  }
  if (loadError.value) {
    return `[重新整理失敗，顯示上次內容] ${loadError.value}\n\n${lines.value.join('\n')}`
  }
  return lines.value.join('\n')
})

function isNearBottom(el, threshold = 80) {
  return el.scrollHeight - el.scrollTop - el.clientHeight <= threshold
}

async function openLogsFolder() {
  try {
    await tomcatsApi.openLogsDir(tomcatId.value)
  } catch (error) {
    ElMessage.error(extractErrorMessage(error) || '無法開啟日誌資料夾')
  }
}

async function loadLogs({ showLoading = false } = {}) {
  const box = logBox.value
  const stickToBottom = !box || isNearBottom(box)
  const seq = ++requestSeq

  if (showLoading) loading.value = true
  try {
    const apiCall = activeTab.value === 'app'
      ? () => tomcatApi.appLogs(lineCount.value)
      : () => tomcatsApi.logs(tomcatId.value, lineCount.value)
    const { data } = await apiCall()
    if (seq !== requestSeq) return
    const next = Array.isArray(data) ? data : []
    const nextText = next.join('\n')
    const prevText = lines.value.join('\n')
    loadError.value = ''
    if (nextText === prevText) return
    lines.value = next
    await nextTick()
    if (logBox.value && stickToBottom) {
      logBox.value.scrollTop = logBox.value.scrollHeight
    }
  } catch {
    if (seq !== requestSeq) return
    loadError.value = activeTab.value === 'app'
      ? '無法載入 TMAM 後端日誌'
      : '無法載入 Tomcat 日誌'
    if (lines.value.length === 0) {
      lines.value = [loadError.value]
    }
  } finally {
    if (showLoading && seq === requestSeq) loading.value = false
  }
}

function reloadLogs() {
  return loadLogs({ showLoading: true })
}

watch(tomcatId, () => reloadLogs())

watch(autoRefresh, (enabled) => {
  if (enabled) {
    pollingTimer = setInterval(() => loadLogs({ showLoading: false }), 3000)
  } else {
    clearInterval(pollingTimer)
    pollingTimer = null
  }
})

onMounted(() => {
  reloadLogs()
  if (autoRefresh.value) {
    pollingTimer = setInterval(() => loadLogs({ showLoading: false }), 3000)
  }
})

onUnmounted(() => clearInterval(pollingTimer))
</script>

<style scoped>
.logs-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.logs-header h2 {
  margin: 8px 0 0;
}

.logs-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.log-error {
  margin-bottom: 12px;
}

.log-content {
  margin: 0;
  padding: 12px;
  background: #1e1e1e;
  color: #d4d4d4;
  border-radius: 4px;
  min-height: 480px;
  max-height: calc(100vh - 240px);
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: Consolas, 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.5;
}
</style>
