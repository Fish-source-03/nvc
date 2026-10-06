// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { DOMAINS } from '@/types'
import { normalizeDomains, resolveAvailableDomains, pickDefaultDomain } from './auth'

/**
 * 批次 03 前端联动修复 —— 域选择默认值。
 *
 * <p>背景：后端 `/api/chat/ask` 与 `/api/chat/ask/stream` 已强制校验 domain
 * （缺失 → 业务码 400、越域 → 403）。原前端域选择器默认「全部域」（空值），
 * 导致问答必然失败，且 SSE 只看 HTTP 状态，界面表现为"无响应"。
 * 修复后默认选中用户首个可用域，且不允许空域发出请求。</p>
 *
 * <p>环境说明：本仓库 jsdom 环境不可用（`html-encoding-sniffer` 触发
 * `ERR_REQUIRE_ESM`），故组件层无法自动化渲染测试；选域逻辑已提取为
 * 纯函数，本文件以 node 环境覆盖之。</p>
 */
describe('chat domain selection (batch-03)', () => {
  describe('normalizeDomains', () => {
    it('空值 / 未定义 / 空数组均返回空数组', () => {
      expect(normalizeDomains(undefined)).toEqual([])
      expect(normalizeDomains(null)).toEqual([])
      expect(normalizeDomains([])).toEqual([])
    })

    it('剔除空白项与重复项，并保持原有顺序', () => {
      expect(normalizeDomains(['HR', '  ', 'FINANCE', 'HR', '', 'RD'])).toEqual([
        'HR',
        'FINANCE',
        'RD',
      ])
    })

    it('裁剪每项两侧空白', () => {
      expect(normalizeDomains([' HR ', 'FINANCE '])).toEqual(['HR', 'FINANCE'])
    })
  })

  describe('pickDefaultDomain', () => {
    it('普通用户取首个可用域', () => {
      expect(pickDefaultDomain(['FINANCE', 'COMMON'], false)).toBe('FINANCE')
    })

    it('跳过列表中的空串 / 空白项，取首个真实域', () => {
      expect(pickDefaultDomain(['', '   ', 'RD', 'RD'], false)).toBe('RD')
    })

    it('普通用户无任何可用域时返回 null（调用方须禁用发送并提示）', () => {
      expect(pickDefaultDomain([], false)).toBeNull()
      expect(pickDefaultDomain(undefined, false)).toBeNull()
      expect(pickDefaultDomain(null, false)).toBeNull()
      expect(pickDefaultDomain(['', '  '], false)).toBeNull()
    })

    it('admin 的 allowedDomains 为空时回退到内置全量域首项（后端 ABAC 对 admin 直通）', () => {
      expect(pickDefaultDomain([], true)).toBe(DOMAINS[0])
      expect(pickDefaultDomain(undefined, true)).toBe(DOMAINS[0])
    })

    it('admin 有显式授权域时仍以其列表为准', () => {
      expect(pickDefaultDomain(['SALES', 'HR'], true)).toBe('SALES')
    })

    it('返回值要么为 null，要么非空字符串 —— 绝不会是空串', () => {
      const picked = [
        pickDefaultDomain([], false),
        pickDefaultDomain(undefined, false),
        pickDefaultDomain(['  '], false),
        pickDefaultDomain(['HR'], false),
        pickDefaultDomain(null, true),
        pickDefaultDomain(['', 'COMMON'], true),
      ]
      for (const domain of picked) {
        expect(domain === null || domain.trim().length > 0).toBe(true)
      }
    })
  })

  describe('resolveAvailableDomains', () => {
    it('有授权域时原样（去重后）返回，普通用户与 admin 一致', () => {
      expect(resolveAvailableDomains(['HR', 'COMMON'], false)).toEqual(['HR', 'COMMON'])
      expect(resolveAvailableDomains(['HR', 'COMMON'], true)).toEqual(['HR', 'COMMON'])
    })

    it('admin 无授权域时得到内置全量域', () => {
      expect(resolveAvailableDomains([], true)).toEqual([...DOMAINS])
    })

    it('普通用户无授权域时得到空列表（→ 前端禁用发送）', () => {
      expect(resolveAvailableDomains([], false)).toEqual([])
      expect(resolveAvailableDomains(null, false)).toEqual([])
    })

    it('返回新数组，调用方修改不会污染入参', () => {
      const input = ['HR']
      const output = resolveAvailableDomains(input, false)
      output.push('FINANCE')
      expect(input).toEqual(['HR'])
    })

    it('admin 回退分支返回的也是副本，不会污染内置常量', () => {
      const output = resolveAvailableDomains([], true)
      output.pop()
      expect(resolveAvailableDomains([], true)).toEqual([...DOMAINS])
    })
  })
})
