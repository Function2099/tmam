<template>
  <el-card class="instance-card" shadow="hover">
    <div class="card-header">
      <span class="project-name">{{ project.displayName ?? project.name }}</span>
      <el-tag :type="statusType" size="small">{{ statusLabel }}</el-tag>
    </div>

    <div class="card-info">
      <div>識別：<code>{{ project.name }}</code></div>
      <div>HTTP Port：<strong>{{ project.ports?.http }}</strong></div>
      <div v-if="project.ports?.shutdown">Shutdown：{{ project.ports.shutdown }}</div>
      <el-link
        v-if="project.ports?.http && status === 'RUNNING'"
        :href="`http://localhost:${project.ports.http}`"
        target="_blank"
        type="primary"
      >
        開啟瀏覽器
      </el-link>
    </div>

    <div class="card-actions">
      <el-button
        type="primary"
        size="small"
        :disabled="status === 'RUNNING' || status === 'STARTING'"
        :loading="loading && status !== 'RUNNING'"
        @click="$emit('start', project.name)"
      >
        啟動
      </el-button>

      <el-button
        type="danger"
        size="small"
        :disabled="status === 'STOPPED' || status === 'STARTING'"
        :loading="loading && status === 'RUNNING'"
        @click="$emit('stop', project.name)"
      >
        停止
      </el-button>

      <el-button
        size="small"
        :disabled="status === 'STARTING'"
        @click="$emit('restart', project.name)"
      >
        重啟
      </el-button>

      <el-button size="small" @click="viewLogs">Log</el-button>
    </div>
  </el-card>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'

const props = defineProps({
  project: { type: Object, required: true },
  status: { type: String, default: 'STOPPED' },
  loading: { type: Boolean, default: false },
})
defineEmits(['start', 'stop', 'restart'])

const router = useRouter()

const statusType = computed(() => ({
  RUNNING: 'success',
  STOPPED: 'info',
  STARTING: 'warning',
  ERROR: 'danger',
}[props.status] ?? 'info'))

const statusLabel = computed(() => ({
  RUNNING: '運行中',
  STOPPED: '已停止',
  STARTING: '啟動中...',
  ERROR: '異常',
}[props.status] ?? '未知'))

function viewLogs() {
  router.push(`/logs/${props.project.name}`)
}
</script>

<style scoped>
.instance-card {
  margin-bottom: 16px;
  height: 100%;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  gap: 8px;
}

.project-name {
  font-weight: 600;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-info {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 16px;
  font-size: 13px;
  color: #606266;
}

.card-info code {
  font-size: 12px;
}

.card-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
</style>
