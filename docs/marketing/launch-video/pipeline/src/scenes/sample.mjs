// 样片场景：项目列表 → 打开演示项目 → 文件树展开 → 打开一份材料 → 右键「管理标签」打一个标签。
// 这是链路验证卡（dev-board #59）要交付的那一段样片，不是全部八幕正片。
//
// 一幕 = 一个 async 函数(stage, ctx)：ctx 带着 seedDemoProject() 返回的项目/文件夹/文件 id，
// 选择器优先钉死在这些真实 id 上（.tree-item[data-file-id="..."]），不靠文本位置猜。

const TAG_NAME = '关键证据'

export async function sampleScene(stage, ctx) {
  const { folderId, files } = ctx
  const firstFile = files[0]
  if (!firstFile) throw new Error('演示项目里没有材料文件，seedDemoProject 应该已经导入了 9 份')

  await stage.pause(600)

  // ---- 一、项目列表 → 打开演示项目 ----
  await stage.titleCard('打开案卷')
  await stage.waitFor('.page-project-list')
  await stage.click('.project-item-card')
  await stage.pause(400)

  // 工作台就绪的判据用左栏「资源管理器」rail 按钮（与外壳面板无关的稳定锚点）
  await stage.waitFor('.rail-btn[title="资源管理器"]', { timeout: 30000 })
  await stage.pause(500)

  // ---- 二、文件树展开 ----
  await stage.titleCard('翻开卷宗')
  await stage.click('.rail-btn[title="资源管理器"]')
  await stage.pause(500)

  const folderSelector = `.tree-item[data-file-id="${folderId}"]`
  await stage.waitFor(folderSelector, { timeout: 15000 })
  await stage.click(`${folderSelector} .tree-expand-icon-wrapper`)
  await stage.pause(500)

  // ---- 三、打开一份材料 ----
  const fileSelector = `.tree-item[data-file-id="${firstFile.id}"]`
  await stage.waitFor(fileSelector, { timeout: 15000 })
  await stage.click(fileSelector)
  // 文档编辑器（LOWA）加载需要时间，给足够的静场——旁白在这里描述材料内容
  await stage.pause(3500)

  // ---- 四、右键「管理标签」 ----
  await stage.titleCard('打上标签')
  await stage.rightClick(fileSelector)
  await stage.pause(350)
  await stage.clickText('.context-menu-item', '管理标签')
  await stage.pause(500)

  await stage.click('.tag-input')
  await stage.pause(200)
  await stage.type(TAG_NAME)
  await stage.pause(400)
  await stage.clickText('.create-option-card', '创建')
  await stage.pause(400)
  // 色板任选一个不是纯白/纯黑的颜色，索引 2 落在预设色板中段
  await stage.clickNth('.color-option-compact', 2)
  await stage.pause(300)
  await stage.clickText('.confirm-btn-compact', '创建')
  await stage.pause(700) // 停留展示已打上的标签 chip

  await stage.clickText('.awd-dialog-footer .awd-btn-primary', '完成')
  await stage.pause(1200) // 收尾静场
}
