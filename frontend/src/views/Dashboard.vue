<template>
  <div class="dashboard">
    <PortConflictAlert />

    <el-skeleton :loading="loading" animated>
      <el-row :gutter="16">
        <el-col v-if="projects.length === 0" :span="24">
          <el-empty description="尚無專案，請至專案管理新增">
            <el-button type="primary" @click="$router.push('/projects')">前往專案管理</el-button>
          </el-empty>
        </el-col>
        <el-col
          v-for="project in projects"
          :key="project.name"
          :xs="24"
          :sm="12"
          :md="8"
          :lg="6"
        >
          <InstanceCard
            :project="project"
            :status="displayStatus(project.name)"
            :loading="!!pendingActions[project.name]"
            @start="handleStart"
            @stop="handleStop"
            @restart="handleRestart"
          />
        </el-col>
      </el-row>
    </el-skeleton>
  </div>
</template>

<script setup>
import { storeToRefs } from 'pinia'
import { onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import InstanceCard from '@/components/InstanceCard.vue'
import PortConflictAlert from '@/components/PortConflictAlert.vue'
import { extractErrorMessage, instanceApi } from '@/api/tmam'
import { useInstanceStore } from '@/stores/instance'

const store = useInstanceStore()
const { projects, loading, pendingActions } = storeToRefs(store)
const { displayStatus } = store

let pollingTimer = null

onMounted(async () => {
  await store.refreshProjects()
  pollingTimer = setInterval(() => store.refreshStatus(), 3000)
})

onUnmounted(() => clearInterval(pollingTimer))

async function handleStart(name) {
  store.setPending(name, 'start')
  ElMessage.info(`正在啟動 ${name}...`)
  try {
    await instanceApi.start(name)
    ElMessage.success(`${name} 啟動成功`)
    await store.refreshStatus()
  } catch (e) {
    ElMessage.error(`啟動失敗：${extractErrorMessage(e)}`)
  } finally {
    store.clearPending(name)
  }
}

async function handleStop(name) {
  store.setPending(name, 'stop')
  try {
    await instanceApi.stop(name)
    ElMessage.success(`${name} 已停止`)
    await store.refreshStatus()
  } catch (e) {
    ElMessage.error(`停止失敗：${extractErrorMessage(e)}`)
  } finally {
    store.clearPending(name)
  }
}

async function handleRestart(name) {
  store.setPending(name, 'restart')
  ElMessage.info(`正在重啟 ${name}...`)
  try {
    await instanceApi.restart(name)
    ElMessage.success(`${name} 重啟成功`)
    await store.refreshStatus()
  } catch (e) {
    ElMessage.error(`重啟失敗：${extractErrorMessage(e)}`)
  } finally {
    store.clearPending(name)
  }
}
</script>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
</style>
