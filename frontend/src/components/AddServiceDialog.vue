<template>
  <el-dialog
    v-model="visible"
    :title="mode === 'create' ? '新增系統' : '編輯位址與路徑'"
    width="560px"
    destroy-on-close
    @closed="reset"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
      <el-form-item v-if="mode === 'create'" label="類型">
        <el-radio-group v-model="form.type">
          <el-radio value="PATH_PROXY">路徑型（Nginx）</el-radio>
          <el-radio value="LEGACY_IP">IP 型</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="mode === 'create'" label="系統名稱" prop="name">
        <el-input v-model="form.name" placeholder="例如 New_System" />
      </el-form-item>
      <el-form-item label="顯示名稱">
        <el-input v-model="form.displayName" placeholder="儀表板顯示名稱" />
      </el-form-item>

      <template v-if="form.type === 'PATH_PROXY'">
        <el-form-item label="路徑前綴" prop="pathPrefix">
          <el-input v-model="form.pathPrefix" placeholder="/new-system" />
        </el-form-item>
        <el-form-item label="剝除前綴轉發">
          <el-switch v-model="form.proxyStripPrefix" />
        </el-form-item>
      </template>

      <template v-else>
        <el-form-item label="IP 位址" prop="address">
          <el-input v-model="form.address" placeholder="192.168.10.20" />
        </el-form-item>
        <el-form-item label="Port" prop="port">
          <el-input-number v-model="form.port" :min="1" :max="65535" />
        </el-form-item>
      </template>

      <template v-if="mode === 'create'">
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
      </template>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="loading" @click="submit">儲存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

const visible = defineModel({ type: Boolean, default: false })

const props = defineProps({
  mode: { type: String, default: 'create' },
  loading: { type: Boolean, default: false },
  initial: { type: Object, default: null },
})

const emit = defineEmits(['submit'])

const formRef = ref(null)
const form = reactive({
  type: 'PATH_PROXY',
  name: '',
  displayName: '',
  pathPrefix: '',
  docBase: '',
  address: '127.0.0.1',
  port: 8080,
  enabled: true,
  proxyStripPrefix: false,
})

const rules = computed(() => ({
  name: [{ required: props.mode === 'create', message: '請輸入系統名稱', trigger: 'blur' }],
  pathPrefix: [{
    required: form.type === 'PATH_PROXY',
    message: '請輸入路徑前綴',
    trigger: 'blur',
  }],
  address: [{
    required: form.type === 'LEGACY_IP',
    message: '請輸入 IP',
    trigger: 'blur',
  }],
  port: [{
    required: form.type === 'LEGACY_IP',
    message: '請輸入 Port',
    trigger: 'change',
  }],
  docBase: [{
    required: props.mode === 'create',
    message: '請輸入 webapp 目錄',
    trigger: 'blur',
  }],
}))

watch(
  () => props.initial,
  (row) => {
    if (!row) return
    form.type = row.type ?? 'PATH_PROXY'
    form.name = row.name ?? ''
    form.displayName = row.displayName ?? ''
    form.pathPrefix = row.pathPrefix ?? ''
    form.docBase = row.docBase ?? ''
    form.address = row.address ?? '127.0.0.1'
    form.port = row.port ?? 8080
    form.enabled = row.enabled ?? true
    form.proxyStripPrefix = !!row.proxyStripPrefix
  },
  { immediate: true },
)

function reset() {
  form.type = 'PATH_PROXY'
  form.name = ''
  form.displayName = ''
  form.pathPrefix = ''
  form.docBase = ''
  form.address = '127.0.0.1'
  form.port = 8080
  form.enabled = true
  form.proxyStripPrefix = false
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

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  emit('submit', { ...form })
}
</script>
