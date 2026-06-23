<template>
  <el-dialog
    v-model="visible"
    :title="isEdit ? '編輯專案' : '新增專案'"
    width="560px"
    @close="reset"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
      <el-form-item label="識別名稱" prop="name">
        <el-input
          v-model="form.name"
          :disabled="isEdit"
          placeholder="crm-system（英數與連字號）"
        />
      </el-form-item>
      <el-form-item label="顯示名稱" prop="displayName">
        <el-input v-model="form.displayName" placeholder="CRM 客戶關係管理系統" />
      </el-form-item>
      <el-form-item label="HTTP Port" prop="http">
        <el-input-number v-model="form.http" :min="1024" :max="65535" />
      </el-form-item>
      <el-form-item label="Shutdown Port" prop="shutdown">
        <el-input-number v-model="form.shutdown" :min="1024" :max="65535" />
      </el-form-item>
      <el-form-item label="AJP Port" prop="ajp">
        <el-input-number v-model="form.ajp" :min="1024" :max="65535" />
      </el-form-item>
      <el-form-item label="WAR 路徑">
        <el-input v-model="form.warPath" placeholder="選填，例如 D:\wars\app.war" />
      </el-form-item>
      <el-form-item label="Context Path">
        <el-input v-model="form.contextPath" placeholder="ROOT 或應用路徑" />
      </el-form-item>
      <el-form-item label="JVM 參數">
        <el-input v-model="form.jvmOpts" placeholder="-Xms256m -Xmx512m" />
      </el-form-item>
      <el-form-item label="啟用">
        <el-switch v-model="form.enabled" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="handleSave">儲存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { extractErrorMessage, projectApi } from '@/api/tmam'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  project: { type: Object, default: null },
})
const emit = defineEmits(['update:modelValue', 'saved'])

const formRef = ref()
const saving = ref(false)

const isEdit = computed(() => !!props.project)

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const defaultForm = () => ({
  name: '',
  displayName: '',
  http: 8081,
  shutdown: 8005,
  ajp: 8009,
  warPath: '',
  contextPath: 'ROOT',
  jvmOpts: '-Xms256m -Xmx512m',
  enabled: true,
})

const form = reactive(defaultForm())

const rules = {
  name: [
    { required: true, message: '請輸入識別名稱', trigger: 'blur' },
    { pattern: /^[a-z0-9][a-z0-9-]*$/, message: '僅限小寫英數與連字號', trigger: 'blur' },
  ],
  displayName: [{ required: true, message: '請輸入顯示名稱', trigger: 'blur' }],
  http: [{ required: true, message: '請設定 HTTP Port', trigger: 'change' }],
  shutdown: [{ required: true, message: '請設定 Shutdown Port', trigger: 'change' }],
  ajp: [{ required: true, message: '請設定 AJP Port', trigger: 'change' }],
}

watch(
  () => props.project,
  (project) => {
    if (!project) {
      Object.assign(form, defaultForm())
      return
    }
    Object.assign(form, {
      name: project.name,
      displayName: project.displayName ?? '',
      http: project.ports?.http ?? 8081,
      shutdown: project.ports?.shutdown ?? 8005,
      ajp: project.ports?.ajp ?? 8009,
      warPath: project.warPath ?? '',
      contextPath: project.contextPath ?? 'ROOT',
      jvmOpts: project.jvmOpts ?? '',
      enabled: project.enabled !== false,
    })
  },
  { immediate: true },
)

function reset() {
  Object.assign(form, defaultForm())
  formRef.value?.clearValidate()
}

function buildPayload() {
  return {
    name: form.name,
    displayName: form.displayName,
    ports: { http: form.http, shutdown: form.shutdown, ajp: form.ajp },
    warPath: form.warPath || null,
    contextPath: form.contextPath || 'ROOT',
    jvmOpts: form.jvmOpts || null,
    enabled: form.enabled,
  }
}

function buildUpdatePayload() {
  return {
    displayName: form.displayName,
    ports: { http: form.http, shutdown: form.shutdown, ajp: form.ajp },
    warPath: form.warPath || null,
    contextPath: form.contextPath || 'ROOT',
    jvmOpts: form.jvmOpts || null,
    enabled: form.enabled,
  }
}

async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    if (isEdit.value) {
      await projectApi.update(form.name, buildUpdatePayload())
      ElMessage.success('專案已更新')
    } else {
      await projectApi.add(buildPayload())
      ElMessage.success('專案已新增')
    }
    visible.value = false
    emit('saved')
  } catch (e) {
    ElMessage.error(extractErrorMessage(e))
  } finally {
    saving.value = false
  }
}
</script>
