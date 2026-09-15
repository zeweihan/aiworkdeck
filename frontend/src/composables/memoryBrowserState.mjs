// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

export function resolveMemoryLink(currentPath, rawHref, knownPaths = []) {
  if (!rawHref || typeof rawHref !== 'string') return null
  let href
  try {
    href = decodeURIComponent(rawHref.trim())
  } catch (e) {
    return null
  }
  href = href.split('#', 1)[0].split('?', 1)[0]
  if (!href || href.startsWith('/') || href.startsWith('\\') || href.includes('\\')) return null
  if (/^[a-z][a-z0-9+.-]*:/i.test(href)) return null

  const base = String(currentPath || '').split('/').slice(0, -1).filter(Boolean)
  for (const segment of href.split('/')) {
    if (!segment || segment === '.') continue
    if (segment === '..') {
      if (!base.length) return null
      base.pop()
      continue
    }
    if (segment.includes('\0')) return null
    base.push(segment)
  }
  const resolved = base.join('/')
  if (!resolved.toLowerCase().endsWith('.md')) return null
  return knownPaths.includes(resolved) ? resolved : null
}

export function markdownFileLinks(content, currentPath, knownPaths = []) {
  const links = []
  const seen = new Set()
  const linkPattern = /(!?)\[([^\]]+)\]\(([^)\s]+)(?:\s+["'][^"']*["'])?\)/g
  let match
  while ((match = linkPattern.exec(String(content || ''))) !== null) {
    if (match[1] === '!') continue
    const path = resolveMemoryLink(currentPath, match[3], knownPaths)
    if (!path || seen.has(path)) continue
    seen.add(path)
    links.push({ label: match[2].trim() || path, path })
  }
  return links
}
