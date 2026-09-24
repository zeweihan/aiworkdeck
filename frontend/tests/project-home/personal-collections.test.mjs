// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 设置页「全部收藏」「我的待办」两栏的纯函数（dev-board#872）。
// 跑法：cd frontend && npm run test:project-home
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  favoriteKind,
  favoriteHost,
  filterFavorites,
  groupFavoritesByProject,
  localDateKey,
  groupTodos,
  writableProjects,
} from '../../src/utils/personalCollections.js'

test('收藏类型：有 URL 算网页（截图也算），有图算图片，其余文本', () => {
  assert.equal(favoriteKind({ sourceUrl: 'https://a.com', imagePath: 'x.png' }), 'web')
  assert.equal(favoriteKind({ imagePath: 'x.png' }), 'image')
  assert.equal(favoriteKind({ content: '摘录' }), 'text')
  assert.equal(favoriteKind(null), 'text')
})

test('来源域名：sourceHost 优先，否则解析 URL，坏 URL 给空串', () => {
  assert.equal(favoriteHost({ sourceHost: 'gsxt.gov.cn', sourceUrl: 'https://other.com/x' }), 'gsxt.gov.cn')
  assert.equal(favoriteHost({ sourceUrl: 'https://www.pkulaw.com/a?b=1' }), 'www.pkulaw.com')
  assert.equal(favoriteHost({ sourceUrl: 'not a url' }), '')
  assert.equal(favoriteHost({}), '')
})

const favs = [
  { id: 5, title: '某公司工商登记', sourceUrl: 'https://gsxt.gov.cn/1', projectId: 2, projectName: '甲项目' },
  { id: 4, title: '', content: '第十二条 违约责任', projectId: 1, projectName: '乙项目' },
  { id: 3, title: 'Case Law', content: '', projectId: 2, projectName: '甲项目' },
  { id: 2, title: '孤儿', projectId: 99, projectName: null },
  { id: 1, title: '无项目', projectId: null },
]

test('过滤：标题 / 内容 / URL / 项目名都能命中，忽略大小写，空查询不过滤', () => {
  assert.deepEqual(filterFavorites(favs, '').map((f) => f.id), [5, 4, 3, 2, 1])
  assert.deepEqual(filterFavorites(favs, '  ').map((f) => f.id), [5, 4, 3, 2, 1])
  assert.deepEqual(filterFavorites(favs, '工商').map((f) => f.id), [5])
  assert.deepEqual(filterFavorites(favs, '违约').map((f) => f.id), [4])
  assert.deepEqual(filterFavorites(favs, 'GSXT').map((f) => f.id), [5])
  assert.deepEqual(filterFavorites(favs, 'case law').map((f) => f.id), [3])
  assert.deepEqual(filterFavorites(favs, '甲项目').map((f) => f.id), [5, 3])
  assert.deepEqual(filterFavorites(null, 'x'), [])
})

test('按项目分组：保持首次出现顺序，查不到项目名的并进最后一组', () => {
  const groups = groupFavoritesByProject(favs)
  assert.deepEqual(groups.map((g) => g.key), ['2', '1', 'none'])
  assert.equal(groups[0].projectName, '甲项目')
  assert.deepEqual(groups[0].items.map((f) => f.id), [5, 3])
  assert.deepEqual(groups[2].items.map((f) => f.id), [2, 1])
  assert.equal(groups[2].projectName, null)
  assert.deepEqual(groupFavoritesByProject(undefined), [])
})

test('localDateKey 用本地时区，不走 toISOString', () => {
  assert.equal(localDateKey(new Date(2026, 0, 5, 0, 30)), '2026-01-05')
  assert.equal(localDateKey(new Date(2026, 11, 31, 23, 59)), '2026-12-31')
})

test('待办分组：逾期 / 今天 / 之后 / 无日期 / 已完成，各组排序', () => {
  const tasks = [
    { id: 1, title: '之后-晚', dueDate: '2026-10-10', status: 'OPEN' },
    { id: 2, title: '逾期', dueDate: '2026-09-01', status: 'OPEN' },
    { id: 3, title: '今天-下午', dueDate: '2026-09-23', dueTime: '15:00', status: 'OPEN' },
    { id: 4, title: '今天-全天', dueDate: '2026-09-23', status: 'open' },
    { id: 5, title: '之后-早', dueDate: '2026-09-24', status: 'OPEN' },
    { id: 6, title: '逾期但已完成', dueDate: '2026-09-01', status: 'DONE' },
    { id: 7, title: '已完成-近', dueDate: '2026-09-20', status: 'done' },
    { id: 8, title: '无日期', dueDate: null, status: 'OPEN' },
    { id: 9, title: '更早逾期', dueDate: '2026-08-15', status: 'OPEN' },
  ]
  const g = groupTodos(tasks, '2026-09-23')
  assert.deepEqual(g.overdue.map((t) => t.id), [9, 2])
  assert.deepEqual(g.today.map((t) => t.id), [4, 3])
  assert.deepEqual(g.upcoming.map((t) => t.id), [5, 1])
  assert.deepEqual(g.noDate.map((t) => t.id), [8])
  assert.deepEqual(g.done.map((t) => t.id), [7, 6])
  const empty = groupTodos(null, '2026-09-23')
  assert.deepEqual(Object.values(empty).map((a) => a.length), [0, 0, 0, 0, 0])
})

test('新增待办的项目下拉只留可写项目（只读成员与客户不给选）', () => {
  const projects = [
    { id: 1, name: 'A', myRole: 'OWNER' },
    { id: 2, name: 'B', myRole: 'ADMIN' },
    { id: 3, name: 'C', myRole: 'PARTICIPANT' },
    { id: 4, name: 'D', myRole: 'READ_ONLY' },
    { id: 5, name: 'E', myRole: 'CLIENT' },
    { id: 6, name: 'F' },
  ]
  assert.deepEqual(writableProjects(projects).map((p) => p.id), [1, 2, 3])
  assert.deepEqual(writableProjects(null), [])
})

// 设置页「我的待办」换统一事项行（dev-board#898）：源码断言，组件带 @/ 别名进不了 node。
test('我的待办：读写走 taskStore，行用 TaskRow（显示项目），分组用 groupByDue，锚点不变', async () => {
  const { readFileSync } = await import('node:fs')
  const src = readFileSync(
    new URL('../../src/components/userprofile/PersonalTodosPanel.vue', import.meta.url), 'utf8')
  const code = src.replace(/<!--[\s\S]*?-->/g, '').replace(/^\s*\/\/.*$/gm, '')
  assert.ok(code.includes('<view class="panel-todos">'), '.panel-todos 是 app-e2e 锚点（#872/#981）')
  assert.ok(code.includes("$t('account.todosSubtitle')"), '副标题保留')
  assert.ok(code.includes("from '@/utils/taskStore.js'"))
  assert.ok(code.includes('taskStore.global.list'))
  assert.ok(code.includes('loadGlobal({ force: true })'), '全量加载（不带 from/to）')
  assert.ok(!/getCalendarTasks|from '@\/services\/api\.js'[^\n]*(updateTask|deleteTask)/.test(code), '写操作不再直接调接口')
  assert.ok(code.includes('groupByDue(this.tasks)'))
  assert.ok(!code.includes('groupTodos'), '已从旧 groupTodos 迁走（无日期桶已不存在）')
  assert.match(code, /<TaskRow[\s\S]*?:show-project="true"/)
  assert.ok(code.includes('showDone && doneTasks.length'), '已完成默认折叠')
  assert.match(code, /<TaskDialog[\s\S]*?:projects="writableMyProjects"/, '新增开统一弹窗，只给可写项目')
  assert.ok(code.includes("$t('calendar.viewFullSchedule')"), '「查看全盘日程」链接保留')
})
