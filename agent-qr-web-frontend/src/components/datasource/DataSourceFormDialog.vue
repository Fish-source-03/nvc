<script setup lang="ts">
import { ref, reactive, watch, computed } from 'vue'
import { SOURCE_TYPES, DOMAINS } from '@/types'
import type { DataSourceConfig, DataSourceForm } from '@/types'
import { datasourceApi } from '@/api/datasource'

const props = defineProps<{
  modelValue: boolean
  editData?: DataSourceConfig
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  submit: [data: DataSourceForm]
  'test-connection': [data: DataSourceForm]
}>()

const sourceType = ref<'JDBC' | 'REST' | 'S3'>('JDBC')
const connectionConfig = reactive<Record<string, any>>({})
const tableNameInput = ref('')

// 每个表的字段检测加载状态
const detectingTables = reactive<Record<string, boolean>>({})
// 每个表的已检测字段列表（来自后端的所有字段）
const detectedColumns = reactive<Record<string, string[]>>({})
// 每个表的字段名手动输入
const fieldInput = reactive<Record<string, string>>({})

// 完整性检查字段选择状态（字段名 → 是否选中）
const contentFieldsSelected = reactive<Record<string, boolean>>({})

// 所有已检测字段的并集（跨表去重）
const allDetectedFields = computed(() => {
  const set = new Set<string>()
  for (const table of (connectionConfig.tableNames || [])) {
    const fields = detectedColumns[table] || connectionConfig.tableFields?.[table] || []
    for (const f of fields) set.add(f)
  }
  return [...set]
})

const form = reactive({
  sourceName: '',
  domain: '',
  syncStrategy: 'FULL' as 'FULL' | 'INCREMENTAL',
  cursorField: '',
  cronExpression: '',
})

function initForm() {
  if (props.editData) {
    form.sourceName = props.editData.sourceName
    sourceType.value = props.editData.sourceType
    form.domain = props.editData.domain
    form.syncStrategy = props.editData.syncStrategy
    form.cursorField = props.editData.cursorField || ''
    // ★ 批次 11 · R23：响应字段名是 syncCron（写入仍用 cronExpression，后端 @JsonAlias 接收）。
    //   此前读 editData.cronExpression → 编辑弹窗里定时表达式永远为空（回显断裂）。
    form.cronExpression = props.editData.syncCron || ''
    const rawConfig = props.editData.connectionConfig
    const parsedConfig = typeof rawConfig === 'string' ? JSON.parse(rawConfig) : (rawConfig || {})
    Object.assign(connectionConfig, parsedConfig)
    if (sourceType.value === 'JDBC') {
      if (!connectionConfig.tableNames) {
        connectionConfig.tableNames = []
      }
      if (!connectionConfig.tableFields) {
        connectionConfig.tableFields = {}
      }
      // 初始化 fieldInput 和 detectedColumns（编辑已有配置时回显）
      if (connectionConfig.tableNames) {
        for (const table of connectionConfig.tableNames) {
          if (!connectionConfig.tableFields[table]) {
            connectionConfig.tableFields[table] = []
          }
          // 编辑时，把已保存的字段也放入 detectedColumns 以便复选框显示
          if (connectionConfig.tableFields[table].length > 0) {
            detectedColumns[table] = [...connectionConfig.tableFields[table]]
          }
          fieldInput[table] = ''
        }
      }
    }
    // 回显 contentFields：解析逗号分隔字符串
    if (props.editData?.contentFields) {
      const fields = props.editData.contentFields.split(',').map(s => s.trim()).filter(Boolean)
      for (const f of fields) {
        contentFieldsSelected[f] = true
      }
    }
  } else {
    form.sourceName = ''
    sourceType.value = 'JDBC'
    form.domain = ''
    form.syncStrategy = 'FULL'
    form.cursorField = ''
    form.cronExpression = ''
    clearConnectionConfig()
  }
  tableNameInput.value = ''
}

function clearConnectionConfig() {
  Object.keys(connectionConfig).forEach((key) => delete connectionConfig[key])
  Object.keys(detectedColumns).forEach((key) => delete detectedColumns[key])
  Object.keys(detectingTables).forEach((key) => delete detectingTables[key])
  Object.keys(fieldInput).forEach((key) => delete fieldInput[key])
  Object.keys(contentFieldsSelected).forEach((key) => delete contentFieldsSelected[key])
}

watch(sourceType, () => {
  clearConnectionConfig()
  if (sourceType.value === 'JDBC') {
    connectionConfig.tableNames = []
    connectionConfig.tableFields = {}
  }
})

watch(() => [props.modelValue, props.editData], () => {
  if (props.modelValue) {
    initForm()
  }
})

function handleClose() {
  emit('update:modelValue', false)
}

function addTable() {
  if (!tableNameInput.value.trim()) return
  if (!connectionConfig.tableNames) connectionConfig.tableNames = []
  const tableName = tableNameInput.value.trim()
  connectionConfig.tableNames.push(tableName)
  // 初始化字段追踪
  if (!connectionConfig.tableFields) connectionConfig.tableFields = {}
  if (!connectionConfig.tableFields[tableName]) {
    connectionConfig.tableFields[tableName] = []
  }
  fieldInput[tableName] = ''
  tableNameInput.value = ''
}

function removeTable(index: number) {
  if (connectionConfig.tableNames) {
    const tableName = connectionConfig.tableNames[index]
    // 记录该表独有的字段，后续清理 contentFields
    const tableOnlyFields = tableName
      ? (connectionConfig.tableFields?.[tableName] || []).filter(
          f => !isFieldUsedByOtherTable(f, tableName)
        )
      : []
    connectionConfig.tableNames.splice(index, 1)
    // 清理该表的所有字段相关数据
    if (tableName) {
      delete connectionConfig.tableFields?.[tableName]
      delete detectedColumns[tableName]
      delete detectingTables[tableName]
      delete fieldInput[tableName]
    }
    // 如果没有表了，清理 tableFields
    if (connectionConfig.tableNames.length === 0) {
      delete connectionConfig.tableFields
    }
    // 清理仅属于该表的完整性检查字段
    for (const f of tableOnlyFields) {
      delete contentFieldsSelected[f]
    }
  }
}

/** 检查字段是否被指定表以外的其他表引用 */
function isFieldUsedByOtherTable(fieldName: string, excludeTable: string): boolean {
  for (const table of (connectionConfig.tableNames || [])) {
    if (table === excludeTable) continue
    if (connectionConfig.tableFields?.[table]?.includes(fieldName)) {
      return true
    }
  }
  return false
}

// ==================== 字段管理 ====================

/** 自动检测表字段（通过后端连接数据库获取） */
async function detectFields(tableName: string) {
  const configJson = JSON.stringify({
    url: connectionConfig.url,
    username: connectionConfig.username,
    password: connectionConfig.password,
  })
  detectingTables[tableName] = true
  try {
    const res = await datasourceApi.detectColumns(configJson, tableName)
    detectedColumns[tableName] = res.data
    // 默认全选所有检测到的字段
    if (!connectionConfig.tableFields) connectionConfig.tableFields = {}
    connectionConfig.tableFields[tableName] = [...res.data]
    // 自动将新检测到的字段加入完整性检查
    for (const field of res.data) {
      if (contentFieldsSelected[field] === undefined) {
        contentFieldsSelected[field] = true
      }
    }
  } catch {
    // 检测失败，用户可手动输入
    if (!connectionConfig.tableFields) connectionConfig.tableFields = {}
    if (!connectionConfig.tableFields[tableName]) connectionConfig.tableFields[tableName] = []
  } finally {
    detectingTables[tableName] = false
  }
}

/** 切换字段勾选（复选框） */
function toggleField(tableName: string, field: string) {
  if (!connectionConfig.tableFields) connectionConfig.tableFields = {}
  if (!connectionConfig.tableFields[tableName]) connectionConfig.tableFields[tableName] = []
  const fields = connectionConfig.tableFields[tableName]
  const idx = fields.indexOf(field)
  if (idx >= 0) {
    fields.splice(idx, 1)
  } else {
    fields.push(field)
  }
}

/** 手动添加字段 */
function addField(tableName: string) {
  if (!fieldInput[tableName]?.trim()) return
  if (!connectionConfig.tableFields) connectionConfig.tableFields = {}
  if (!connectionConfig.tableFields[tableName]) connectionConfig.tableFields[tableName] = []
  const field = fieldInput[tableName].trim()
  if (!connectionConfig.tableFields[tableName].includes(field)) {
    connectionConfig.tableFields[tableName].push(field)
  }
  // 自动加入完整性检查
  if (contentFieldsSelected[field] === undefined) {
    contentFieldsSelected[field] = true
  }
  fieldInput[tableName] = ''
}

/** 删除字段（从标签关闭按钮） */
function removeField(tableName: string, fieldName: string) {
  if (connectionConfig.tableFields?.[tableName]) {
    const idx = connectionConfig.tableFields[tableName].indexOf(fieldName)
    if (idx >= 0) connectionConfig.tableFields[tableName].splice(idx, 1)
  }
  // 如果该字段不再被任何表使用，从完整性检查中移除
  if (!isFieldUsedByAnyTable(fieldName)) {
    delete contentFieldsSelected[fieldName]
  }
}

/** 检查字段是否被任意表引用 */
function isFieldUsedByAnyTable(fieldName: string): boolean {
  for (const table of (connectionConfig.tableNames || [])) {
    if (connectionConfig.tableFields?.[table]?.includes(fieldName)) {
      return true
    }
  }
  return false
}

/** 切换完整性检查字段的选中状态 */
function toggleContentField(field: string) {
  contentFieldsSelected[field] = !contentFieldsSelected[field]
}

function buildFormData(): DataSourceForm {
  const config: Record<string, any> = { ...connectionConfig }
  if (sourceType.value === 'JDBC' && config.tableNames) {
    config.tableNames = [...config.tableNames]
  }
  // 构建 contentFields：逗号分隔的选中字段
  const selectedFields = Object.entries(contentFieldsSelected)
    .filter(([, v]) => v)
    .map(([k]) => k)
  return {
    sourceName: form.sourceName,
    sourceType: sourceType.value,
    domain: form.domain,
    syncStrategy: form.syncStrategy,
    cursorField: form.cursorField || undefined,
    cronExpression: form.cronExpression || undefined,
    connectionConfig: Object.keys(config).length > 0 ? JSON.stringify(config) : undefined,
    contentFields: selectedFields.length > 0 ? selectedFields.join(',') : undefined,
  }
}

function handleSubmit() {
  if (!form.sourceName) return
  if (!form.domain) return
  const formData = buildFormData()
  emit('submit', formData)
}

function handleTestConnection() {
  if (!form.sourceName) return
  if (!form.domain) return
  const formData = buildFormData()
  emit('test-connection', formData)
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="editData ? '编辑数据源' : '新增数据源'"
    width="580px"
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form :model="form" label-width="110px" @submit.prevent>
      <el-form-item label="数据源名称" required>
        <el-input v-model="form.sourceName" placeholder="请输入数据源名称" maxlength="100" />
      </el-form-item>

      <el-form-item label="数据源类型" required>
        <el-select v-model="sourceType" style="width: 100%">
          <el-option
            v-for="item in SOURCE_TYPES"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="业务域" required>
        <el-select v-model="form.domain" style="width: 100%">
          <el-option
            v-for="domain in DOMAINS"
            :key="domain"
            :label="domain"
            :value="domain"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="同步策略">
        <el-radio-group v-model="form.syncStrategy">
          <el-radio value="FULL">全量 (FULL)</el-radio>
          <el-radio value="INCREMENTAL">增量 (INCREMENTAL)</el-radio>
        </el-radio-group>
      </el-form-item>

      <!-- 增量同步时显示游标字段选择（仅 JDBC） -->
      <el-form-item
        v-if="form.syncStrategy === 'INCREMENTAL' && sourceType === 'JDBC'"
        label="游标字段"
      >
        <el-select
          v-model="form.cursorField"
          placeholder="请选择增量游标字段（如 id、update_time）"
          clearable
          style="width: 100%"
        >
          <el-option
            v-for="field in allDetectedFields"
            :key="'cursor-' + field"
            :label="field"
            :value="field"
          />
        </el-select>
        <div class="cursor-field-hint">
          游标字段用于判断增量数据，通常选择自增主键或更新时间字段
        </div>
      </el-form-item>

      <el-form-item label="定时表达式">
        <el-input
          v-model="form.cronExpression"
          placeholder="0 0 2 * * ?"
        />
      </el-form-item>

      <el-divider content-position="left">连接配置</el-divider>

      <!-- JDBC 配置 -->
      <template v-if="sourceType === 'JDBC'">
        <el-form-item label="JDBC URL" required>
          <el-input
            v-model="connectionConfig.url"
            placeholder="jdbc:mysql://host:port/db"
          />
        </el-form-item>
        <el-form-item label="用户名" required>
          <el-input v-model="connectionConfig.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码" required>
          <el-input
            v-model="connectionConfig.password"
            type="password"
            placeholder="请输入密码"
            show-password
          />
        </el-form-item>
        <el-form-item label="表名与字段">
          <div class="table-input-area">
            <!-- 每个表的卡片 -->
            <div
              v-for="(table, index) in connectionConfig.tableNames"
              :key="table"
              class="table-card"
            >
              <div class="table-card-header">
                <el-tag closable type="warning" @close="removeTable(index)">{{ table }}</el-tag>
                <el-button
                  size="small"
                  type="success"
                  :loading="detectingTables[table]"
                  @click="detectFields(table)"
                >
                  检测字段
                </el-button>
              </div>
              <!-- 检测到的字段复选框 -->
              <div v-if="detectedColumns[table]?.length" class="field-checkboxes">
                <el-checkbox
                  v-for="col in detectedColumns[table]"
                  :key="col"
                  :model-value="connectionConfig.tableFields?.[table]?.includes(col)"
                  @change="toggleField(table, col)"
                >
                  {{ col }}
                </el-checkbox>
              </div>
              <!-- 已选字段标签 -->
              <div class="field-tags">
                <el-tag
                  v-for="(field, fi) in connectionConfig.tableFields?.[table]"
                  :key="fi"
                  closable
                  size="small"
                  @close="removeField(table, field)"
                >
                  {{ field }}
                </el-tag>
                <span
                  v-if="!connectionConfig.tableFields?.[table]?.length"
                  class="no-fields"
                >SELECT *（未指定字段）</span>
              </div>
              <!-- 手动输入 -->
              <div class="field-input-row">
                <el-input
                  v-model="fieldInput[table]"
                  size="small"
                  placeholder="手动输入字段名"
                  @keyup.enter="addField(table)"
                />
                <el-button size="small" @click="addField(table)">添加</el-button>
              </div>
            </div>
            <!-- 添加表名 -->
            <div class="table-input-row">
              <el-input
                v-model="tableNameInput"
                placeholder="输入表名后点击添加"
                @keyup.enter="addTable"
              />
              <el-button type="primary" @click="addTable">添加表名</el-button>
            </div>
          </div>
        </el-form-item>

        <!-- 完整性检查字段（仅 JDBC） -->
        <el-form-item v-if="allDetectedFields.length > 0" label="完整性检查字段">
          <div class="content-fields-area">
            <p class="content-fields-hint">
              选择用于数据质量完整性检查的字段。未配置时使用全局默认值（content, text, _content）。
            </p>
            <div class="content-fields-checkboxes">
              <el-checkbox
                v-for="field in allDetectedFields"
                :key="'cf-' + field"
                :model-value="!!contentFieldsSelected[field]"
                @change="toggleContentField(field)"
              >
                {{ field }}
              </el-checkbox>
            </div>
            <p v-if="!allDetectedFields.some(f => contentFieldsSelected[f])" class="content-fields-warn">
              未选择任何字段，将使用全局默认值。对于 sys_user 等不含 content/text/_content 列的表，请至少选择一个字段。
            </p>
          </div>
        </el-form-item>
      </template>

      <!-- REST 配置 -->
      <template v-if="sourceType === 'REST'">
        <el-form-item label="Base URL" required>
          <el-input
            v-model="connectionConfig.baseUrl"
            placeholder="https://api.example.com"
          />
        </el-form-item>
        <el-form-item label="认证头">
          <el-input
            v-model="connectionConfig.authHeader"
            placeholder="请输入认证头"
          />
        </el-form-item>
        <el-form-item label="分页参数">
          <el-input
            v-model="connectionConfig.pagination"
            placeholder="page={page}&size={size}"
          />
        </el-form-item>
      </template>

      <!-- S3 配置 -->
      <template v-if="sourceType === 'S3'">
        <el-form-item label="Bucket" required>
          <el-input v-model="connectionConfig.bucketName" placeholder="请输入 Bucket 名称" />
        </el-form-item>
        <el-form-item label="Prefix">
          <el-input v-model="connectionConfig.prefix" placeholder="请输入 Prefix（选填）" />
        </el-form-item>
        <el-form-item label="Access Key" required>
          <el-input v-model="connectionConfig.accessKey" placeholder="请输入 Access Key" />
        </el-form-item>
        <el-form-item label="Secret Key" required>
          <el-input
            v-model="connectionConfig.secretKey"
            type="password"
            placeholder="请输入 Secret Key"
            show-password
          />
        </el-form-item>
      </template>
    </el-form>

    <template #footer>
      <el-button @click="handleClose">取消</el-button>
      <el-button type="warning" @click="handleTestConnection">测试连接</el-button>
      <el-button type="primary" @click="handleSubmit">确认</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.table-input-area {
  width: 100%;

  .table-card {
    border: 1px solid #e4e7ed;
    border-radius: 6px;
    padding: 10px 12px;
    margin-bottom: 10px;

    .table-card-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 8px;
    }

    .field-checkboxes {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin-bottom: 8px;
      padding: 6px 8px;
      background-color: #f5f7fa;
      border-radius: 4px;
    }

    .field-tags {
      display: flex;
      flex-wrap: wrap;
      gap: 4px;
      margin-bottom: 8px;
      min-height: 22px;

      .no-fields {
        color: #c0c4cc;
        font-size: 12px;
        line-height: 22px;
      }
    }

    .field-input-row {
      display: flex;
      gap: 6px;

      .el-input {
        flex: 1;
      }
    }
  }

  .table-input-row {
    display: flex;
    gap: 8px;

    .el-input {
      flex: 1;
    }
  }
}

.cursor-field-hint {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}

.content-fields-area {
  width: 100%;

  .content-fields-hint {
    margin: 0 0 8px 0;
    font-size: 12px;
    color: #909399;
  }

  .content-fields-checkboxes {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    padding: 8px 10px;
    background-color: #f0f9eb;
    border-radius: 4px;
    border: 1px solid #e1f3d8;
  }

  .content-fields-warn {
    margin: 6px 0 0 0;
    font-size: 12px;
    color: #e6a23c;
  }
}
</style>
