<template>
  <el-dialog
    v-model="visible"
    :title="setup ? '選擇 Tomcat 安裝目錄' : '新增 Tomcat 實例'"
    width="640px"
    destroy-on-close
    :close-on-click-modal="!setup"
    :show-close="!setup"
    @open="onOpen"
  >
    <el-alert
      v-if="setup"
      type="info"
      :closable="false"
      show-icon
      title="首次使用請選擇本機 Tomcat 安裝目錄，設定會自動儲存，下次啟動無需重新選擇。"
      class="setup-hint"
    />
    <el-form label-width="110px" class="add-tomcat-form">
      <el-form-item label="來源">
        <el-radio-group v-model="activeTab" class="mode-switch">
          <el-radio-button value="scan">掃描本機</el-radio-button>
          <el-radio-button value="manual">手動輸入</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <div v-show="activeTab === 'scan'" v-loading="scanLoading" class="scan-pane">
        <el-button :loading="scanLoading" @click="loadDiscover">重新掃描</el-button>
        <el-table
          v-if="discovered.length"
          :data="discovered"
          stripe
          highlight-current-row
          class="scan-table"
          @current-change="(row) => (selected = row)"
        >
          <el-table-column prop="name" label="名稱" min-width="140" />
          <el-table-column prop="catalinaHome" label="路徑" min-width="260" show-overflow-tooltip />
          <el-table-column prop="version" label="版本" min-width="120" show-overflow-tooltip />
        </el-table>
        <el-empty v-else description="未掃描到 Tomcat，請改用手動輸入" />
      </div>

      <el-form-item v-show="activeTab === 'manual'" label="安裝目錄">
        <el-input v-model="manualHome" placeholder="C:\Program Files\apache-tomcat-9.0.115">
          <template #append>
            <el-button @click="browseHome">瀏覽</el-button>
          </template>
        </el-input>
      </el-form-item>

      <el-form-item label="顯示名稱">
        <el-input v-model="displayName" placeholder="選填" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button v-if="!setup" @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">
        {{ setup ? '確認並開始使用' : '新增' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { tomcatsApi, extractErrorMessage } from '@/api/tmam'

const visible = defineModel({ type: Boolean, default: false })
const props = defineProps({
  setup: { type: Boolean, default: false },
})
const emit = defineEmits(['created'])

const activeTab = ref('scan')
const scanLoading = ref(false)
const submitting = ref(false)
const discovered = ref([])
const selected = ref(null)
const manualHome = ref('')
const displayName = ref('')

watch(visible, (open) => {
  if (!open) {
    selected.value = null
    manualHome.value = ''
    displayName.value = ''
  }
})

async function onOpen() {
  await loadDiscover()
}

async function loadDiscover() {
  scanLoading.value = true
  try {
    const { data } = await tomcatsApi.discover()
    discovered.value = data ?? []
  } catch (e) {
    ElMessage.error(extractErrorMessage(e))
    discovered.value = []
  } finally {
    scanLoading.value = false
  }
}

async function browseHome() {
  if (!window.tmam?.selectDirectory) {
    ElMessage.warning('目錄瀏覽僅支援 TMAM 桌面版')
    return
  }
  try {
    const path = await window.tmam.selectDirectory(manualHome.value || undefined)
    if (path) manualHome.value = path
  } catch {
    ElMessage.error('無法開啟目錄選擇器')
  }
}

async function submit() {
  const catalinaHome = activeTab.value === 'scan'
    ? selected.value?.catalinaHome
    : manualHome.value?.trim()

  if (!catalinaHome) {
    ElMessage.warning('請選擇或輸入 Tomcat 路徑')
    return
  }

  submitting.value = true
  try {
    const { data } = await tomcatsApi.create({
      catalinaHome,
      displayName: displayName.value || undefined,
    })
    ElMessage.success(props.setup ? 'Tomcat 已設定完成' : 'Tomcat 實例已新增')
    visible.value = false
    emit('created', data)
  } catch (e) {
    ElMessage.error(extractErrorMessage(e))
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.setup-hint {
  margin-bottom: 16px;
}

.add-tomcat-form :deep(.el-form-item__content) {
  min-width: 0;
}

.mode-switch {
  width: 100%;
}

.scan-pane {
  min-height: 200px;
  margin-bottom: 18px;
}

.scan-table {
  margin-top: 12px;
}
</style>
