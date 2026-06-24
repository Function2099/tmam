<template>
  <el-card class="instance-card" shadow="hover">
    <div class="card-header">
      <span class="instance-name">{{ instance.displayName }}</span>
      <el-tag :type="statusType" size="small">{{ statusLabel }}</el-tag>
    </div>

    <div class="card-info">
      <div>ID：<code>{{ instance.id }}</code></div>
      <div class="path" :title="instance.catalinaHome">{{ instance.catalinaHome }}</div>
      <div>Service：{{ instance.enabledServiceCount }} / {{ instance.totalServiceCount }} 啟用</div>
      <div>Gateway Port：{{ instance.gatewayPort }}</div>
    </div>

    <div class="card-actions">
      <el-button type="primary" size="small" @click="$emit('manage', instance.id)">
        管理 Service
      </el-button>
      <el-button
        type="success"
        size="small"
        :disabled="status === 'RUNNING'"
        :loading="loading"
        @click="$emit('start', instance.id)"
      >
        啟動
      </el-button>
      <el-button
        type="danger"
        size="small"
        :disabled="status === 'STOPPED'"
        :loading="loading"
        @click="$emit('stop', instance.id)"
      >
        停止
      </el-button>
      <el-button size="small" @click="$emit('logs', instance.id)">Log</el-button>
    </div>
  </el-card>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  instance: { type: Object, required: true },
  status: { type: String, default: 'STOPPED' },
  loading: { type: Boolean, default: false },
})
defineEmits(['manage', 'start', 'stop', 'logs'])

const statusType = computed(() => ({
  RUNNING: 'success',
  STOPPED: 'info',
  STARTING: 'warning',
  ERROR: 'danger',
}[props.status] ?? 'info'))

const statusLabel = computed(() => ({
  RUNNING: '運行中',
  STOPPED: '已停止',
  STARTING: '啟動中',
  ERROR: '異常',
}[props.status] ?? '未知'))
</script>

<style scoped>
.instance-card {
  height: 100%;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  gap: 8px;
}

.instance-name {
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

.path {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
