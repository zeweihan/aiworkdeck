// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

/** One option per cloud identity, retaining the existing route for the selected location. */
export function catalogOptions(catalog, cloudProjects) {
  const cloudIds = new Set(cloudProjects.map(p => String(p.id)))
  const projects = []
  const devices = new Map()
  for (const item of catalog) {
    const locations = item.locations || []
    const cloud = locations.find(l => l.kind === 'cloud' && cloudIds.has(String(l.cloudProjectId)))
    if (cloud) {
      projects.push({ ...cloudProjects.find(p => String(p.id) === String(cloud.cloudProjectId)), projectUid: item.projectUid, name: item.name })
      continue
    }
    const device = locations.filter(l => l.kind === 'desktop').sort((a, b) => Number(b.online) - Number(a.online))[0]
    if (!device) continue
    if (!devices.has(device.deviceId)) devices.set(device.deviceId, { ...device, projects: [] })
    devices.get(device.deviceId).projects.push({ key: device.key, name: item.name, projectUid: item.projectUid })
  }
  return { projects, devices: [...devices.values()] }
}
