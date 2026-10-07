import { test, expect, type Page } from '@playwright/test'

/**
 * 端到端用例：登录 → 提问 → 收到流式回答（批次 11 · 任务 11.2.3）。
 *
 * ## 为什么对后端打桩
 *
 * 完整的"真实后端"链路在本机不可复现：问答需要可用的 LLM
 * （`.env` 的 `DEEPSEEK_API_KEY` 为占位值，本地 Ollama 只有 embedding 模型，
 * 没有 chat 模型），后端在 LLM 失败时只发 `error` 事件、不发 `token`。
 * 若强行连真实后端，用例断言的不是"流式回答"而是"降级错误"，
 * 反而会把 SSE 契约的回归掩盖过去。
 *
 * 因此本用例在 **HTTP 边界**打桩（`page.route`），跑真实的浏览器 + 真实前端产物：
 * 覆盖登录契约、路由跳转、域联动、SSE 事件解析（token/done）与渲染，
 * 同时把"生产路径拼接"（问题 36：`/api/api/...` 已消失）钉死在请求 URL 上。
 *
 * 真实链路的可用性由 `e2e/live-backend-probe.spec.ts` 在环境具备时另行验证。
 */

const API_RESULT_OK = { code: 200, message: 'ok' }

/** 登录响应（字段与 `LoginVO` 对齐） */
function loginPayload(allowedDomains: string) {
  return {
    ...API_RESULT_OK,
    data: {
      accessToken: 'e2e-stub-access-token',
      refreshToken: 'e2e-stub-refresh-token',
      expiresIn: 3600,
      userId: 1,
      username: 'alice',
      role: 'user',
      department: 'HR',
      clearanceLevel: 1,
      allowedDomains,
      title: 'employee',
    },
  }
}

/**
 * 注册全部接口桩。
 *
 * ⚠️ Playwright 的路由按"后注册先匹配"生效，因此通配兜底必须最先注册。
 */
async function stubBackend(page: Page, allowedDomains: string) {
  await page.route('**/api/**', (route) =>
    route.fulfill({ status: 200, json: { ...API_RESULT_OK, data: null } }),
  )
  await page.route('**/api/auth/login', (route) =>
    route.fulfill({ status: 200, json: loginPayload(allowedDomains) }),
  )
  await page.route('**/api/chat/conversations', (route) =>
    route.fulfill({ status: 200, json: { ...API_RESULT_OK, data: [] } }),
  )
}

/** 登录（走真实登录页与真实表单校验） */
async function loginAs(page: Page) {
  await page.goto('/login')
  await page.getByPlaceholder('请输入用户名').fill('alice')
  await page.getByPlaceholder('请输入密码').fill('alice-pass')
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/chat$/)
}

test.describe('登录 → 提问 → 收到流式回答', () => {
  test('登录成功后进入问答页，提问收到 SSE 流式回答并渲染', async ({ page }) => {
    await stubBackend(page, 'HR')

    /** 记录 SSE 请求，用于校验路径拼接与请求体契约 */
    let sseUrl = ''
    let sseBody: { query?: string; domain?: string; conversationId?: number | null } = {}
    let sseAuthHeader = ''
    await page.route('**/api/chat/ask/stream', async (route) => {
      sseUrl = route.request().url()
      sseBody = route.request().postDataJSON()
      sseAuthHeader = route.request().headers()['authorization'] ?? ''
      await route.fulfill({
        status: 200,
        headers: {
          'content-type': 'text/event-stream; charset=utf-8',
          'cache-control': 'no-cache',
        },
        // SSE 契约：token 事件携带原始文本片段，done 事件携带完整答案与来源
        body:
          'event: token\ndata: 您好\n\n' +
          'event: token\ndata: ，这是流式回答\n\n' +
          'event: done\ndata: {"answer":"您好，这是流式回答","conversationId":42,"messageId":1001,"sources":[]}\n\n',
      })
    })

    await loginAs(page)

    // 提问：域由 ChatInput 默认选中（用户 allowedDomains = HR）
    await page.getByPlaceholder(/请输入您的问题/).fill('今年的考勤规则是什么？')
    await page.getByRole('button', { name: '发送' }).click()

    // 断言 SSE 请求契约：路径不带重复 /api 前缀（问题 36）、携带 Bearer、
    // body 含 query 与非空 domain（批次 03 强制项）、新会话 conversationId 为 null
    await expect.poll(() => sseUrl).toContain('/api/chat/ask/stream')
    expect(sseUrl).not.toContain('/api/api/')
    expect(sseAuthHeader).toBe('Bearer e2e-stub-access-token')
    expect(sseBody.query).toBe('今年的考勤规则是什么？')
    expect(sseBody.domain).toBe('HR')
    expect(sseBody.conversationId).toBeNull()

    // 断言流式回答渲染（token 拼接 + done 覆盖完整答案）
    await expect(page.getByText('您好，这是流式回答')).toBeVisible()
  })

  test('未授权任何业务域时禁止提问并给出明确提示（不发出必然失败的请求）', async ({ page }) => {
    await stubBackend(page, '')
    let sseCalled = false
    await page.route('**/api/chat/ask/stream', async (route) => {
      sseCalled = true
      await route.fulfill({ status: 200, headers: { 'content-type': 'text/event-stream' }, body: '' })
    })

    await loginAs(page)

    // 无可用域 → 输入区禁用 + role=alert 提示
    // 注意：用 hasText 过滤——Element Plus 的消息提示同样是 role=alert（登录成功 toast）
    await expect(page.getByRole('alert').filter({ hasText: '未授权任何业务域' })).toBeVisible()
    await expect(page.getByPlaceholder(/请输入您的问题/)).toBeDisabled()

    // 直接点发送（按钮已禁用）不应发出请求
    await page.keyboard.press('Enter')
    await page.waitForTimeout(300)
    expect(sseCalled).toBe(false)
  })
})
