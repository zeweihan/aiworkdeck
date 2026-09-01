// 工作台操作簇（project-overview 的 mixin 模块）：文件打开/标签页、AI 指令路由、
// OCR 截图与识别、暂存区、剪贴板捕获的 toast/modal/错误文案。
export default {
  // fileOpenTabs.js
  fileNotFoundNamed: '未找到文件: {name}',
  fetchFileListFailed: '获取文件列表失败',
  cannotOpenFileTitle: '无法打开文件',
  unsupportedFileContent: '暂不支持打开此类型文件：{name}\n\n文件类型：{fileType}\n\n支持的文件类型：\n• 文档：doc, docx, xls, xlsx, ppt, pptx, pdf\n• 图片：jpg, jpeg, png, gif, bmp, webp, svg\n• 视频：mp4, webm, ogg, mov, mkv, avi\n• 音频：mp3, wav, m4a, flac, aac\n• 文本：txt, md, json, xml, html等',
  noExtension: '无后缀名',
  gotIt: '我知道了',
  versionCompareTabName: '{name} 版本对比',
  previousVersion: '上一版',
  currentVersion: '这一版',
  selectTwoDocsToCompare: '请选择两个文档进行对比',
  updatedOpenFiles: '已更新 {count} 份打开中的文件',

  // agentClientActions.js
  fileUpdated: '文件已更新',
  openedNamed: '已打开: {name}',
  fileNotFound: '文件不存在',
  openFileFailed: '打开文件失败',
  fileUpdatedNamed: '文件已更新: {name}',
  reloadFailedNamed: '{name} 重新加载失败，请关闭标签后重新打开',
  refreshFileFailed: '刷新文件失败',

  // ocrActions.js / ocrCapture.js
  frameNotReady: '截图画面未就绪',
  videoNotReady: '截图视频未就绪',
  videoSizeInvalid: '截图视频尺寸异常',
  captureFailed: '截图失败',
  imageLoadFailed: '截图图片加载失败',
  imageSizeInvalid: '截图图片尺寸异常',
  recognizing: '识别中…',
  recognizeCopySuccess: '识别并复制成功',
  noTextRecognized: '未识别到文字',
  recognizeFailed: '识别失败',
  imageCopied: '已复制图片',
  copyFailed: '复制失败',
  enterFileName: '请输入文件名',
  saveSuccess: '保存成功',
  saveFailedNamed: '保存失败: {msg}',
  unknownError: '未知错误',
  addingFavorite: '正在加入收藏…',
  webExcerpt: '网页摘录',
  favoriteSuccess: '收藏成功',
  favoriteFailed: '收藏失败',
  selectAreaFirst: '请先框选区域',
  processing: '处理中…',
  webMark: '网核',
  webMarkLinkFailed: '网核关联失败',
  desktopCaptureUnavailable: '桌面端截图能力不可用',
  captureNoResult: '截图完成，但未收到结果',
  screenShareUnsupported: '当前浏览器不支持屏幕共享',
  captureFailedAllowShare: '截图失败（请允许共享标签页/窗口）',
  captureH5Only: '仅 H5 支持截图摘录',

  // stagingArea.js
  loadStagingFailed: '加载暂存区文件失败',
  addedToStaging: '已加入暂存区',
  stagingFullTitle: '文件缓存区已满',
  learnMore: '了解详情',
  okKnown: '知道了',
  addToStagingFailed: '加入暂存区失败',
  movedToRootFallback: '原目录已不存在，已移至根目录',
  removeFromStagingFailed: '移出暂存区失败',

  // clipboardBridge.js
  imageCaptured: '已捕获图片',
  fileCaptured: '已捕获文件',
  imageCaptureFailed: '图片记录失败',
  clipboardRecordFailed: '剪贴板记录失败',
  fileCaptureFailed: '文件记录失败',

  // FileTree.vue 拖拽移动：后端回滚失败的移动会照常返回成功响应（数据库与磁盘保持一致，
  // 见 ProjectFileService.moveSingleFileWithPhysical），前端靠比对返回的 parentId 识别
  // 是否真的移动成功（常见于 Windows 上文件被占用）。
  fileMoveOccupied: '该文件可能正被占用，未能移动',
  filesMoveOccupiedCount: '有 {count} 个文件可能正被占用，未能移动',
}
