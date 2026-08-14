/**
 * 案件参与人角色的展示文案——**唯一来源**。
 *
 * 存在理由：这套标签原先散在三处各写各的（CloudSyncBar.roleLabel、InviteMemberDialog
 * 的角色单选、project-overview.groupedMembers 的分组标签），同一个 PARTICIPANT
 * 在三个界面分别叫「参与者」「参与者」「项目成员」，律师在同一个项目里看到三种说法。
 *
 * 硬约束：这里只管**展示**。键名（OWNER/ADMIN/PARTICIPANT/READ_ONLY/CLIENT）是后端
 * ProjectMember.Role 的枚举值，也是 addCloudMember/addProjectMember 的请求字段值，
 * 属接口契约——改文案随便改，改键名会打断契约。
 *
 * 「案件管理员」而不是「管理人」：后者在《企业破产法》上是法定专有名词（破产管理人），
 * 给法律行业做的软件里出现它，律师第一反应是破产程序里的那个角色。
 * 「协作人」而不是「协作律师」：实际被加进案卷的常是律助、实习生、公司法务、外部顾问，
 * 标成「律师」是事实错误，而执业身份对律所是敏感信息。
 */
import { t } from '@/i18n'

export const ROLE_LABELS = {
  OWNER: t('config.roles.owner'),
  MANAGER: t('config.roles.owner'),
  ADMIN: t('config.roles.admin'),
  PARTICIPANT: t('config.roles.participant'),
  READ_ONLY: t('config.roles.readOnly'),
  CLIENT: t('config.roles.client'),
  CLIENT_NAMED: t('config.roles.client'),
  CLIENT_GENERIC: t('config.roles.client'),
}

export function roleLabel(role) {
  return ROLE_LABELS[role] || role || ''
}

/** 邀请内部同事时可选的三个角色（负责人是项目创建者，客户走访问码，都不在这里选）。 */
export const ASSIGNABLE_ROLES = [
  { value: 'ADMIN', label: ROLE_LABELS.ADMIN, hint: t('config.roleHints.admin') },
  { value: 'PARTICIPANT', label: ROLE_LABELS.PARTICIPANT, hint: t('config.roleHints.participant') },
  { value: 'READ_ONLY', label: ROLE_LABELS.READ_ONLY, hint: t('config.roleHints.readOnly') },
]

/** 参与人分组（成员堆叠展开面板用）。 */
export const MEMBER_GROUP_LABELS = {
  admin: t('config.memberGroups.admin'),
  member: t('config.memberGroups.member'),
  client: t('config.memberGroups.client'),
}
