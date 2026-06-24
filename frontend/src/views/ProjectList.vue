<template>
  <div class="project-list">
    <div class="toolbar">
      <h2>專案管理</h2>
      <div class="toolbar-actions">
        <el-button @click="refresh">重新整理</el-button>
        <el-button type="primary" @click="openCreate">新增專案</el-button>
      </div>
    </div>

    <PortConflictAlert />

    <el-table v-loading="loading" :data="projects" stripe border>
      <el-table-column prop="displayName" label="顯示名稱" min-width="160" />
      <el-table-column prop="name" label="識別名稱" min-width="140" />
      <el-table-column label="HTTP" width="90" align="center">
        <template #default="{ row }">{{ row.ports?.http }}</template>
      </el-table-column>
      <el-table-column label="Shutdown" width="100" align="center">
        <template #default="{ row }">{{ row.ports?.shutdown }}</template>
      </el-table-column>
      <el-table-column label="AJP" width="90" align="center">
        <template #default="{ row }">{{ row.ports?.ajp }}</template>
      </el-table-column>
      <el-table-column label="狀態" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="statusTagType(displayStatus(row.name))" size="small">
            {{ statusLabel(displayStatus(row.name)) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="啟用" width="80" align="center">
        <template #default="{ row }">
          <el-tag :type="row.enabled !== false ? 'success' : 'info'" size="small">
            {{ row.enabled !== false ? '是' : '否' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">編輯</el-button>
          <el-button link type="danger" @click="handleDelete(row)">刪除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <AddProjectDialog
      v-model="dialogVisible"
      :project="editingProject"
      @saved="onSaved"
    />
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import AddProjectDialog from '@/components/AddProjectDialog.vue'
import PortConflictAlert from '@/components/PortConflictAlert.vue'
import { extractErrorMessage, projectApi } from '@/api/tmam'
import { useInstanceStore } from '@/stores/instance'

const store = useInstanceStore()
const { projects, loading } = storeToRefs(store)
const { displayStatus } = store

const dialogVisible = ref(false)
const editingProject = ref(null)
let pollingTimer = null

onMounted(async () => {
  await refresh()
  pollingTimer = setInterval(() => store.refreshStatus(), 3000)
})

onUnmounted(() => clearInterval(pollingTimer))

async function refresh() {
  await store.refreshProjects()
}

function openCreate() {
  editingProject.value = null
  dialogVisible.value = true
}

function openEdit(project) {
  editingProject.value = { ...project }
  dialogVisible.value = true
}

async function onSaved() {
  await store.refreshProjects()
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `確定刪除專案「${row.displayName ?? row.name}」？此操作不會刪除磁碟上的實例目錄。`,
      '確認刪除',
      { type: 'warning', confirmButtonText: '刪除', cancelButtonText: '取消' },
    )
    await projectApi.remove(row.name)
    ElMessage.success('專案已刪除')
    await store.refreshProjects()
  } catch (e) {
    if (e !== 'cancel' && e?.message !== 'cancel') {
      ElMessage.error(extractErrorMessage(e))
    }
  }
}

function statusTagType(status) {
  return { RUNNING: 'success', STOPPED: 'info', STARTING: 'warning', ERROR: 'danger' }[status] ?? 'info'
}

function statusLabel(status) {
  return { RUNNING: '運行中', STOPPED: '已停止', STARTING: '啟動中', ERROR: '異常' }[status] ?? '未知'
}
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.toolbar h2 {
  margin: 0;
}

.toolbar-actions {
  display: flex;
  gap: 8px;
}
</style>
