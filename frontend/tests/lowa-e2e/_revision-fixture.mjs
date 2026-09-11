// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import JSZip from 'jszip'

export const deleted = '应恢复的删除文字'
const run = text => `<w:r><w:t>${text}</w:t></w:r>`
const change = (type, id, author, text) => `<w:${type} w:id="${id}" w:author="${author}" w:date="2026-09-10T08:00:00Z">${type === 'del' ? `<w:r><w:delText>${text}</w:delText></w:r>` : run(text)}</w:${type}>`
export async function fixture(layered) {
  const zip = new JSZip()
  zip.file('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
  zip.file('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
  const deletion = change('del', 1, '删除者', deleted)
  const target = layered ? `<w:ins w:id="2" w:author="原插入者" w:date="2026-09-09T08:00:00Z">${deletion}</w:ins>` : deletion
  zip.file('word/document.xml', `<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p>${run('前文')}${target}${run('后文')}</w:p><w:p>${change('ins', 3, '其他审阅人', '独立插入')}</w:p><w:p>${run('尾')}${change('del', 4, '其他审阅人', '独立删除')}${run('文')}</w:p><w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>`)
  return Array.from(await zip.generateAsync({ type: 'uint8array' }))
}

