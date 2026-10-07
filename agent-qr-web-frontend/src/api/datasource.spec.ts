// @vitest-environment node
import { describe, it, expect, vi, beforeEach } from 'vitest'

const requestMock = vi.hoisted(() => ({
  get: vi.fn<(...args: unknown[]) => unknown>(),
  post: vi.fn<(...args: unknown[]) => unknown>(),
  put: vi.fn<(...args: unknown[]) => unknown>(),
  delete: vi.fn<(...args: unknown[]) => unknown>(),
}))

vi.mock('./index', () => ({ default: requestMock }))

import { datasourceApi } from './datasource'
import type { DataSourceConfig, DataSourceForm } from '@/types'
// 以原始文本导入页面源码（vite ?raw），用于静态回归护栏
import dialogSource from '../components/datasource/DataSourceFormDialog.vue?raw'
import typesSource from '../types/index.ts?raw'

/**
 * 批次 11 · R23：数据源表单 `cronExpression` 的回显断裂。
 *
 * <p>后端实体字段名是 `syncCron`，只用 `@JsonAlias("cronExpression")` 兼容**写入**；
 * 响应序列化仍是 `syncCron`。前端表单读的是 `editData.cronExpression`，
 * 于是"编辑数据源"弹窗里的定时表达式永远是空的（回显断裂）。</p>
 */
describe('datasourceApi · 数据源 CRUD 契约（batch-11 / R23）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('列表 → GET /api/datasource/list（domain 作为查询参数）', () => {
    datasourceApi.list({ page: 1, size: 10, domain: 'HR' })

    expect(requestMock.get).toHaveBeenCalledWith('/api/datasource/list', {
      params: { page: 1, size: 10, domain: 'HR' },
    })
  })

  it('★ 写入方向仍用 cronExpression（后端 @JsonAlias 接收，未随回显一起改名）', () => {
    const form: DataSourceForm = {
      sourceName: '考勤库',
      sourceType: 'JDBC',
      domain: 'HR',
      syncStrategy: 'FULL',
      cronExpression: '0 0 2 * * ?',
      connectionConfig: { url: 'jdbc:mysql://localhost:3306/hr' },
    }

    datasourceApi.update(9, form)

    expect(requestMock.put).toHaveBeenCalledWith('/api/datasource/9', form)
    expect((requestMock.put.mock.calls[0]![1] as DataSourceForm).cronExpression).toBe('0 0 2 * * ?')
  })
})

describe('DataSourceFormDialog.vue · 定时表达式回显护栏（batch-11 / R23）', () => {
  it('★ 回显读取 editData.syncCron（响应字段名）', () => {
    expect(dialogSource).toContain('props.editData.syncCron')
    expect(dialogSource).toMatch(/form\.cronExpression = props\.editData\.syncCron \|\| ''/)
  })

  it('★ 回显不再读 editData.cronExpression（原缺陷：读了一个响应里不存在的字段）', () => {
    expect(dialogSource).not.toContain('props.editData.cronExpression')
  })

  it('★ 提交仍用 cronExpression（写入契约不变）', () => {
    expect(dialogSource).toContain('cronExpression: form.cronExpression || undefined')
  })
})

describe('types/index.ts · 数据源响应类型护栏（batch-11 / R23）', () => {
  it('★ DataSourceConfig 声明 syncCron，且不再声明同名 cronExpression', () => {
    const configBlock = typesSource.slice(
      typesSource.indexOf('export interface DataSourceConfig'),
      typesSource.indexOf('export interface DataSourceForm'),
    )

    expect(configBlock).toContain('syncCron: string')
    // 只检查**字段声明**（注释里可以出现 cronExpression 这个词）
    expect(configBlock).not.toMatch(/cronExpression\s*[?:]/)
    // 后端实体会返回 cursorField，表单回显依赖它（此前缺失 → 类型报错）
    expect(configBlock).toContain('cursorField?: string')
  })

  it('★ DataSourceForm（写入报文）保留 cronExpression', () => {
    const formBlock = typesSource.slice(typesSource.indexOf('export interface DataSourceForm'))

    expect(formBlock).toContain('cronExpression?: string')
  })

  it('回显契约样例：响应中的定时表达式位于 syncCron 字段', () => {
    // 模拟后端 GET /api/datasource/{id} 的响应体（只列关键字段）
    const response: Pick<DataSourceConfig, 'syncCron' | 'cursorField'> = {
      syncCron: '0 0 2 * * ?',
      cursorField: 'update_time',
    }

    // 表单应回显出的值 = 组件的读取表达式 props.editData.syncCron
    const editData = response as DataSourceConfig
    expect(editData.syncCron || '').toBe('0 0 2 * * ?')
  })
})
