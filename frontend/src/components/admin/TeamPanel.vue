<!--
  「团队」分区（dev-board#496）。设置页「个人」组的一栏，三态：
    1. 未连接账户 —— 团队挂在官网账户上，先去「账户与用量」连接；
    2. 已连接但没有团队 —— 创建团队 / 邀请码加入 / 收到的邀请，三条路并排；
    3. 有团队 —— 数据共享开关 / KPI / 律所 / 成员 / 项目 / 管理区。

  层级是「个人 → 团队 → 律所」（设计 §10.1）。每一层都必须有看得见的入口，
  这是维护者定的验收线（尽调插件的教训：能力做好了没入口，等于没做）：
    - 无团队态三条路并排，一条都不藏在二级菜单里；
    - 「律所」区常显——没入所时给「创建 / 并入」，入所后给团队列表与范围切换；
    - 数据共享开关在看板顶部，不在最底下。

  五条界面口径（改这个文件前逐条对一遍）：
    - 「节约时间」必须带「估算」二字并把公式摆出来。公式取自服务端下发的
      savedMinutesFormula，不在前端写死系数——写死的话服务端调了系数，
      界面上的脚注就开始骗人。取不到公式时只说「口径取不到」，不编一个。
    - 项目默认以匿名短码出现。项目名只有在团队显式打开「共享项目名」时才会存在，
      这里绝不拿本机的项目名去「补全」看板上的短码——那等于绕过团队设置把名字露出来。
    - 数据共享开关的可用性读后端下发的 available，不靠「有没有桌面壳」猜。
    - 角色判定全在官网。这里的 canManage / canManageFirm 只决定「显不显示按钮」，
      不是闸门——桌面端改一个布尔值不该换来任何权限。
    - 服务端没给的值不编：邀请到期时间、活跃人数的分母、律所各团队合计，
      取不到就不显示或明说取不到。
-->
<template>
  <view class="team-pane">
    <!-- 分区头 + 隐私一句话。三态都在，不随状态消失 -->
    <view class="section-card">
      <view class="section-header">
        <text class="section-title">{{ $t('team.navTeam') }}</text>
        <text class="section-subtitle">{{ $t('team.subtitle') }}</text>
      </view>
      <view class="section-body">
        <text class="privacy-line">{{ $t('team.privacyLine') }}</text>
      </view>
    </view>

    <!-- 态 1：未连接账户 -->
    <view v-if="!connected" class="section-card">
      <view class="section-header">
        <text class="section-title">{{ $t('team.needAccountTitle') }}</text>
        <text class="section-subtitle">{{ $t('team.needAccountDesc') }}</text>
      </view>
      <view class="section-body">
        <view class="team-btn primary" @tap="onGoAccount">{{ $t('team.goAccount') }}</view>
      </view>
    </view>

    <!-- 取数失败：给重试，不把整块吞掉 -->
    <view v-else-if="loadError" class="section-card">
      <view class="section-body">
        <text class="empty-line">{{ $t('team.loadFailed') }}</text>
        <view class="team-btn" @tap="reload">{{ $t('team.retry') }}</view>
      </view>
    </view>

    <!-- 态 2：已连接、没有团队。
         三条路并排，一条都不藏在二级菜单里（设计 §10.4 第 4 条）：
         创建团队 / 输入邀请码加入 / 别人按手机号邀请我。 -->
    <template v-else-if="!team">
      <view class="join-paths">
        <view class="section-card join-card">
          <view class="section-header">
            <text class="section-title">{{ $t('team.createTitle') }}</text>
            <text class="section-subtitle">{{ $t('team.createDesc') }}</text>
          </view>
          <view class="section-body">
            <!-- 输入框必须包在横向的 .team-row 里：.section-body 是竖向 flex，
                 .team-input 的 flex-basis 一旦是长度值就会落到高度上，把输入框
                 撑成一整块（走查 C 第 2 条）。 -->
            <view class="team-row">
              <input
                v-model="newTeamName"
                class="team-input"
                :placeholder="$t('team.teamNamePlaceholder')"
              />
            </view>
            <view class="team-btn primary" :class="{ 'is-busy': busy }" @tap="onCreateTeam">
              {{ $t('team.createButton') }}
            </view>
          </view>
        </view>

        <view class="section-card join-card">
          <view class="section-header">
            <text class="section-title">{{ $t('team.joinTitle') }}</text>
            <text class="section-subtitle">{{ $t('team.joinDesc') }}</text>
          </view>
          <view class="section-body">
            <view class="team-row">
              <input
                v-model="joinCodeInput"
                class="team-input"
                :placeholder="$t('team.joinCodePlaceholder')"
              />
            </view>
            <view class="team-btn primary" :class="{ 'is-busy': busy }" @tap="onJoinTeam">
              {{ $t('team.joinButton') }}
            </view>
          </view>
        </view>

        <view class="section-card join-card">
          <view class="section-header">
            <text class="section-title">{{ $t('team.invitesTitle') }}</text>
          </view>
          <view class="section-body">
            <text v-if="!receivedInvites.length" class="empty-line">{{ $t('team.invitesEmpty') }}</text>
            <view v-for="inv in receivedInvites" :key="inv.id" class="team-list-row">
              <view class="team-list-main">
                <text class="team-list-name">{{ $t('team.inviteFrom', { team: inv.teamName || inv.teamId }) }}</text>
                <!-- 被邀角色与到期时间：接受之前就该看得见自己会以什么身份进去、
                     这条邀请还剩多久。两者都只显示服务端给的值，取不到就明说取不到。 -->
                <text class="team-list-meta">
                  {{ $t('team.inviteRoleAs', { role: roleLabel(inv.role) }) }} · {{ expiryText(inv.expiresAt) }}
                </text>
              </view>
              <view class="team-btn small primary" @tap="onAcceptInvite(inv)">{{ $t('team.acceptInvite') }}</view>
            </view>
          </view>
        </view>
      </view>
    </template>

    <!-- 态 3：有团队 -->
    <template v-else>
      <!-- 数据共享开关 + 立即上报：看板顶部（设计 §10.4 第 6 条）。
           它决定「这个团队有没有数据可看」，压在最底下等于让用户滚过两张空表
           才发现自己一直没开。 -->
      <view class="section-card">
        <view class="section-body">
          <view class="switch-row">
            <view class="switch-info">
              <text class="switch-name">{{ $t('team.sharingTitle') }}</text>
              <!-- 说明文案，不是把标题再抄一遍：同一张卡上出现两遍同一句话，
                   用户会以为这是两个不同的开关 -->
              <text class="switch-desc">{{ sharingHint }}</text>
            </view>
            <AwdSwitch
              :checked="!!sharing.enabled"
              :disabled="busy || sharing.available === false"
              @change="onToggleSharing"
            />
          </view>
          <view class="team-row">
            <view class="team-btn" :class="{ 'is-busy': busy }" @tap="onUploadNow">
              {{ $t('team.uploadNow') }}
            </view>
            <text class="team-footnote">{{ lastUploadText }}</text>
          </view>
        </view>
      </view>

      <view class="section-card">
        <view class="section-header">
          <text class="section-title">{{ headerTitle }}</text>
          <text class="section-subtitle">{{ headerSubtitle }}</text>
          <!-- 范围切换只在入所后出现：没有律所时「全所」不是一个真实存在的视角。
               能不能看全所由官网按角色判，这里只负责把用户选的视角带上去。 -->
          <view v-if="firm" class="team-days-row">
            <text
              class="team-days-btn"
              :class="{ active: scope === 'team' }"
              @tap="setScope('team')"
            >{{ $t('team.scopeTeam') }}</text>
            <text
              class="team-days-btn"
              :class="{ active: scope === 'firm' }"
              @tap="setScope('firm')"
            >{{ $t('team.scopeFirm') }}</text>
          </view>
          <view class="team-days-row">
            <text
              v-for="d in [7, 30, 90]"
              :key="d"
              class="team-days-btn"
              :class="{ active: range === d }"
              @tap="setRange(d)"
            >{{ $t('team.rangeDays', { n: d }) }}</text>
          </view>
        </view>
        <view class="section-body">
          <template v-if="hasSummary">
            <view class="stats-tiles">
              <view class="stat-tile">
                <text class="stat-value">{{ kpi.activeMembers || 0 }}</text>
                <text class="stat-caption">{{ $t('team.kpiActiveMembers') }}</text>
                <!-- 「几人里有几人在用」才是管理者真正想看的比例。档位可切
                     7/30/90，所以标题不许写死「本周」。 -->
                <text v-if="activeMembersCaption" class="stat-sub">{{ activeMembersCaption }}</text>
              </view>
              <view class="stat-tile">
                <text class="stat-value">{{ kpi.projectsCreated || 0 }}</text>
                <text class="stat-caption">{{ $t('team.kpiProjectsCreated') }}</text>
              </view>
              <view class="stat-tile">
                <text class="stat-value">{{ kpi.appStarts || 0 }}</text>
                <text class="stat-caption">{{ $t('team.kpiAppStarts') }}</text>
              </view>
              <view class="stat-tile">
                <text class="stat-value">{{ hoursLabel(kpi.activeMinutes) }}</text>
                <text class="stat-caption">{{ $t('team.kpiActiveMinutes') }}</text>
              </view>
              <view class="stat-tile">
                <text class="stat-value">{{ hoursLabel(kpi.savedMinutes) }}</text>
                <text class="stat-caption">{{ $t('team.kpiSavedMinutes') }}</text>
              </view>
            </view>
            <text class="team-footnote">{{ savedFormulaText }}</text>
          </template>
          <text v-else class="empty-line">{{ $t('team.emptyData') }}</text>
        </view>
      </view>

      <!-- 全所视角下服务端若给了 teams[]，按团队摊开一张表 -->
      <view v-if="scope === 'firm' && firmTeamRows.length" class="section-card">
        <view class="section-header">
          <text class="section-title">{{ $t('team.firmTeamsTableTitle') }}</text>
        </view>
        <view class="section-body">
          <view class="team-table">
            <view class="team-table-head">
              <text class="col col-name">{{ $t('team.colTeam') }}</text>
              <text class="col">{{ $t('team.colTeamMembers') }}</text>
              <text class="col">{{ $t('team.colTeamMinutes') }}</text>
              <text class="col">{{ $t('team.colAiTurns') }}</text>
            </view>
            <view v-for="row in firmTeamRows" :key="row.id || row.name" class="team-table-row">
              <text class="col col-name">
                {{ row.name }}<text v-if="row.isHead" class="head-badge">{{ $t('team.firmHeadBadge') }}</text>
              </text>
              <text class="col">{{ row.memberCount || 0 }}</text>
              <text class="col">{{ hoursLabel(row.activeMinutes) }}</text>
              <text class="col">{{ row.aiTurns || 0 }}</text>
            </view>
          </view>
        </view>
      </view>

      <!-- 律所区：常显（设计 §10.4 第 5 条）。未入所时也要看得见这一层存在，
           否则「多个团队并成一家所」这条能力就没有任何入口。 -->
      <view class="section-card">
        <view class="section-header">
          <text class="section-title">{{ $t('team.firmTitle') }}</text>
          <text class="section-subtitle">{{ firm ? (firm.name || '') : $t('team.firmNoneDesc') }}</text>
        </view>
        <view class="section-body">
          <template v-if="!firm">
            <template v-if="isTeamOwner">
              <text class="sub-title">{{ $t('team.createFirmTitle') }}</text>
              <view class="team-row">
                <input
                  v-model="newFirmName"
                  class="team-input"
                  :placeholder="$t('team.firmNamePlaceholder')"
                />
                <view class="team-btn primary" :class="{ 'is-busy': busy }" @tap="onCreateFirm">
                  {{ $t('team.createFirmButton') }}
                </view>
              </view>
              <text class="sub-title">{{ $t('team.joinFirmTitle') }}</text>
              <view class="team-row">
                <input
                  v-model="firmCodeInput"
                  class="team-input"
                  :placeholder="$t('team.firmCodePlaceholder')"
                />
                <view class="team-btn" :class="{ 'is-busy': busy }" @tap="onJoinFirm">
                  {{ $t('team.joinFirmButton') }}
                </view>
              </view>
            </template>
            <!-- 不是负责人的人也要知道这一层存在、以及为什么自己点不了 -->
            <text v-else class="empty-line">{{ $t('team.firmOwnerOnly') }}</text>
          </template>

          <template v-else>
            <view v-if="canManageFirm" class="team-row">
              <input
                v-model="firmRenameDraft"
                class="team-input"
                :placeholder="$t('team.firmNameLabel')"
              />
              <view class="team-btn" :class="{ 'is-busy': busy }" @tap="onRenameFirm">
                {{ $t('team.saveFirmName') }}
              </view>
            </view>

            <view v-if="canManageFirm" class="code-row">
              <view class="code-main">
                <text class="code-label">{{ $t('team.firmJoinCodeTitle') }}</text>
                <text class="code-value">{{ firm.joinCode || '—' }}</text>
                <text class="code-desc">{{ $t('team.firmJoinCodeDesc') }}</text>
              </view>
              <!-- 契约里两个 joinCode 都是「按角色缺字段」而不是给 null：没这个字段
                   就别渲染一对点下去必然失败的按钮（走查 C 第 1 条） -->
              <view v-if="firm.joinCode" class="code-actions">
                <view class="team-btn small" @tap="onCopyCode(firm.joinCode)">{{ $t('team.copyCode') }}</view>
                <view class="team-btn small" :class="{ 'is-busy': busy }" @tap="onResetFirmCode">
                  {{ $t('team.resetCode') }}
                </view>
              </view>
            </view>

            <text class="sub-title">{{ $t('team.firmTeamsTitle') }}</text>
            <view v-for="t in firmTeams" :key="t.id" class="team-list-row">
              <view class="team-list-main">
                <text class="team-list-name">
                  {{ t.name }}<text v-if="t.isHead" class="head-badge">{{ $t('team.firmHeadBadge') }}</text>
                </text>
              </view>
              <text class="team-list-count">{{ t.memberCount || 0 }}</text>
              <!-- 动作位恒占宽：总部那行没有「移出律所」，不占位的话它的人数会甩到
                   行尾，整列数字对不齐（走查 C 第 7 条）。
                   总部团队自己不可移出（官网也会拒）。 -->
              <view class="team-list-tail">
                <text
                  v-if="canManageFirm && !t.isHead"
                  class="link-action danger"
                  @tap="onRemoveFirmTeam(t)"
                >{{ $t('team.removeFirmTeam') }}</text>
              </view>
            </view>

            <!-- 子团队负责人的退出口。必须与上面的团队列表断开：贴着列表尾巴时
                 它读起来像「第五个团队」那一行（走查 C 第 8 条）。 -->
            <view v-if="canLeaveFirm" class="firm-leave-row">
              <view class="team-btn small danger" @tap="onLeaveFirm">{{ $t('team.leaveFirm') }}</view>
            </view>
          </template>
        </view>
      </view>

      <!-- 成员 -->
      <view class="section-card">
        <view class="section-header">
          <text class="section-title">{{ $t('team.membersTitle') }}</text>
        </view>
        <view class="section-body">
          <!-- 团队邀请码摆在成员区顶部（设计 §10.2）：邀请人的第一动作就是把码发出去。
               只对 OWNER/ADMIN 显示——码等于一张入场券。 -->
          <view v-if="canManage" class="code-row">
            <view class="code-main">
              <text class="code-label">{{ $t('team.joinCodeTitle') }}</text>
              <text class="code-value">{{ joinCode || '—' }}</text>
              <text class="code-desc">{{ $t('team.joinCodeDesc') }}</text>
            </view>
            <!-- 官网把团队邀请码放在 GET /api/account/team 的**顶层**，不在 team 对象里，
                 且 MEMBER 时是「缺这个字段」而不是给 null。所以这里读 this.joinCode，
                 并且只有拿到非空字符串才渲染复制/重置（走查 C 第 1 条）。 -->
            <view v-if="joinCode" class="code-actions">
              <view class="team-btn small" @tap="onCopyCode(joinCode)">{{ $t('team.copyCode') }}</view>
              <view class="team-btn small" :class="{ 'is-busy': busy }" @tap="onResetJoinCode">
                {{ $t('team.resetCode') }}
              </view>
            </view>
          </view>
          <text v-else class="empty-line">{{ $t('team.codeHidden') }}</text>

          <text v-if="!members.length" class="empty-line">{{ $t('team.membersEmpty') }}</text>
          <view v-else class="team-table">
            <view class="team-table-head">
              <text class="col col-name">{{ $t('team.colMember') }}</text>
              <text class="col">{{ $t('team.colRole') }}</text>
              <text class="col">{{ $t('team.colActiveDays') }}</text>
              <text class="col">{{ $t('team.colMinutes') }}</text>
              <text class="col">{{ $t('team.colAiTurns') }}</text>
              <text class="col">{{ $t('team.colLastActive') }}</text>
              <text v-if="canManage" class="col col-actions"></text>
            </view>
            <view v-for="m in members" :key="m.accountId" class="team-table-row">
              <text class="col col-name">{{ m.displayName || m.accountId }}</text>
              <text class="col">{{ roleLabel(m.role) }}</text>
              <text class="col">{{ m.activeDays || 0 }}</text>
              <text class="col">{{ hoursLabel(m.activeMinutes) }}</text>
              <text class="col">{{ m.aiTurns || 0 }}</text>
              <text class="col">{{ m.lastActiveDate || '-' }}</text>
              <!-- OWNER 改不了也踢不掉，自己退出走底部单独的「退出团队」。这两行不给动作：
                   给了就是摆两个点下去必然被官网拒掉的按钮（走查 C 第 3 条）。 -->
              <view v-if="canManage" class="col col-actions">
                <template v-if="canActOn(m)">
                  <text class="link-action" @tap="onChangeRole(m)">{{ $t('team.changeRole') }}</text>
                  <text class="link-action danger" @tap="onRemoveMember(m)">{{ $t('team.removeMember') }}</text>
                </template>
              </view>
            </view>
          </view>
        </view>
      </view>

      <!-- 项目 -->
      <view class="section-card">
        <view class="section-header">
          <text class="section-title">{{ $t('team.projectsTitle') }}</text>
          <text class="section-subtitle">{{ $t('team.projectsNote') }}</text>
        </view>
        <view class="section-body">
          <text v-if="!projects.length" class="empty-line">{{ $t('team.projectsEmpty') }}</text>
          <view v-else class="team-table">
            <view class="team-table-head">
              <text class="col col-name">{{ $t('team.colProject') }}</text>
              <text class="col">{{ $t('team.colMinutes') }}</text>
              <text class="col">{{ $t('team.colProjectMembers') }}</text>
              <text class="col">{{ $t('team.colAiTurns') }}</text>
              <text v-if="canManage" class="col col-actions"></text>
            </view>
            <view v-for="p in projects" :key="p.projectKey" class="team-table-row">
              <text class="col col-name">{{ p.label || p.projectKey }}</text>
              <text class="col">{{ hoursLabel(p.minutes) }}</text>
              <text class="col">{{ p.members || 0 }}</text>
              <text class="col">{{ p.aiTurns || 0 }}</text>
              <view v-if="canManage" class="col col-actions">
                <!-- 已经有别名时说「改别名」：这时点下去是改，不是起 -->
                <text class="link-action" @tap="onSetAlias(p)">
                  {{ p.label ? $t('team.renameAlias') : $t('team.setAlias') }}
                </text>
              </view>
            </view>
          </view>
        </view>
      </view>

      <!-- 管理区：仅 OWNER/ADMIN -->
      <view v-if="canManage" class="section-card">
        <view class="section-header">
          <text class="section-title">{{ $t('team.manageTitle') }}</text>
        </view>
        <view class="section-body">
          <text class="sub-title">{{ $t('team.inviteTitle') }}</text>
          <view class="team-row">
            <input
              v-model="invitePhone"
              class="team-input"
              :placeholder="$t('team.invitePhonePlaceholder')"
            />
            <AwdSelect
              class="team-select"
              :range="inviteRoleLabels"
              :value="inviteRoleIndex"
              @change="onInviteRolePick"
            />
            <view class="team-btn primary" :class="{ 'is-busy': busy }" @tap="onInvite">
              {{ $t('team.inviteButton') }}
            </view>
          </view>

          <text class="sub-title">{{ $t('team.pendingInvitesTitle') }}</text>
          <text v-if="!pendingInvites.length" class="empty-line">{{ $t('team.pendingInvitesEmpty') }}</text>
          <view v-for="inv in pendingInvites" :key="inv.id" class="team-list-row">
            <view class="team-list-main">
              <text class="team-list-name">{{ inv.phone }}</text>
              <text class="team-list-meta">{{ expiryText(inv.expiresAt) }}</text>
            </view>
            <text class="team-list-count">{{ roleLabel(inv.role) }}</text>
            <!-- 撤销与角色标签之间留够距离：挨在一起时误点的是不可撤销的动作 -->
            <text class="link-action danger team-list-action" @tap="onRevokeInvite(inv)">
              {{ $t('team.revokeInvite') }}
            </text>
          </view>

          <text class="sub-title">{{ $t('team.settingsTitle') }}</text>
          <view class="team-row">
            <input v-model="renameDraft" class="team-input" :placeholder="$t('team.renameLabel')" />
            <view class="team-btn" :class="{ 'is-busy': busy }" @tap="onRename">{{ $t('team.saveName') }}</view>
          </view>
          <view class="switch-row">
            <view class="switch-info">
              <text class="switch-name">{{ $t('team.shareProjectNamesLabel') }}</text>
              <text class="switch-desc">{{ $t('team.shareProjectNamesDesc') }}</text>
            </view>
            <AwdSwitch
              :checked="!!team.shareProjectNames"
              :disabled="busy"
              @change="onToggleShareNames"
            />
          </view>
        </view>
      </view>

      <view v-if="canLeave" class="section-card">
        <view class="section-body">
          <text class="link-action danger" @tap="onLeaveTeam">{{ $t('team.leaveTeam') }}</text>
        </view>
      </view>
    </template>
  </view>
</template>

<script>
import AwdSwitch from '@/components/AwdSwitch.vue'
import AwdSelect from '@/components/AwdSelect.vue'
import {
  getAccountStatus,
  getTeam, createTeam, updateTeam,
  createTeamInvite, revokeTeamInvite, acceptTeamInvite,
  updateTeamMemberRole, removeTeamMember,
  getTeamSummary, setTeamProjectAlias,
  getTeamUsageSharing, setTeamUsageSharing, uploadTeamUsageNow,
  joinTeam, regenerateTeamJoinCode,
  createFirm, joinFirm, updateFirm, regenerateFirmJoinCode, removeFirmTeam,
} from '@/services/api.js'

const ROLE_KEYS = ['ADMIN', 'MEMBER']

export default {
  name: 'TeamPanel',
  components: { AwdSwitch, AwdSelect },
  emits: ['go-account'],
  data() {
    return {
      connected: false,
      loadError: false,
      busy: false,
      team: null,
      myRole: '',
      // 官网 /api/account/team 若下发 myAccountId 才给「退出团队」入口。
      // 契约 §6 只写了 {team, myRole, members[], pendingInvites[]}，没有这个字段——
      // 拿不到就不显示这个动作，**绝不自己编一个 'me' 之类的 id 去打 DELETE**：
      // 猜错的后果是把别人踢出团队。
      myAccountId: '',
      // 团队邀请码。官网把它放在 GET /api/account/team 的顶层（不在 team 里），
      // 且只发给本队 OWNER/ADMIN——MEMBER 那边是**缺字段**，不是 null。
      // 空串 = 没拿到，界面据此不渲染复制/重置。
      joinCode: '',
      members: [],
      pendingInvites: [],
      receivedInvites: [],
      // 律所（设计 §10.1）。null = 本团队没有并入任何律所——这一层仍要在界面上
      // 看得见，只是给的是「创建 / 并入」两个入口而不是看板
      firm: null,
      summary: null,
      range: 7,
      // 看板视角。入所之后才有「全所」这回事；能不能看由官网按角色判
      scope: 'team',
      // available 初值刻意是 undefined 而不是 true/false：还没问过后端时既不该
      // 把开关点亮，也不该显示「不可用」的说明
      sharing: { enabled: false, lastUploadAt: '', available: undefined },
      newTeamName: '',
      joinCodeInput: '',
      newFirmName: '',
      firmCodeInput: '',
      firmRenameDraft: '',
      invitePhone: '',
      inviteRoleIndex: 1,
      renameDraft: '',
    }
  },
  computed: {
    canManage() {
      return this.myRole === 'OWNER' || this.myRole === 'ADMIN'
    },
    // 创建律所 / 并入律所都要求团队 OWNER（设计 §10.2）
    isTeamOwner() {
      return this.myRole === 'OWNER'
    },
    // 律所管理者 = 总部团队的 OWNER/ADMIN。isHead 由服务端下发，
    // 不拿 firm.headTeamId === team.id 自己推——两个字段哪个缺了都会推错
    canManageFirm() {
      return !!(this.firm && this.firm.isHead) && this.canManage
    },
    // 子团队负责人可以带着团队退出律所；总部团队不可退出（官网也会拒）
    canLeaveFirm() {
      return !!this.firm && !this.firm.isHead && this.isTeamOwner && !!this.team
    },
    firmTeams() {
      return (this.firm && Array.isArray(this.firm.teams) ? this.firm.teams : [])
    },
    // 全所视角下服务端按团队摊开的合计。没有就不渲染那张表，不拿 firm.teams 顶
    // ——那份只有名册没有统计数字。
    firmTeamRows() {
      return (this.summary && Array.isArray(this.summary.teams) ? this.summary.teams : [])
    },
    // 全所视角下这张卡统计的是整个律所，标题就该是律所名——挂着本团队的名字
    // 会让人把全所的数字当成本队的（走查 C 第 6 条）
    headerTitle() {
      if (this.scope === 'firm' && this.firm) return this.firm.name || this.$t('team.firmTitle')
      return (this.team && this.team.name) || this.$t('team.kpiTitle')
    },
    headerSubtitle() {
      if (this.scope === 'firm' && this.firm) {
        // 团队数只取服务端给的名册长度；取不到就只说「全所」，不编一个数字
        const n = this.firmTeams.length
        return n ? this.$t('team.firmScopeSubtitle', { n }) : this.$t('team.scopeFirm')
      }
      if (this.firm && this.firm.name) return `${this.$t('team.kpiTitle')} · ${this.firm.name}`
      return this.$t('team.kpiTitle')
    },
    // 「6 / 9 人」。总人数取不到时不显示这一行，不拿活跃数顶成分母
    activeMembersCaption() {
      const k = this.kpi
      if (typeof k.memberCount !== 'number') return ''
      return this.$t('team.kpiActiveMembersCaption', {
        active: k.activeMembers || 0,
        total: k.memberCount,
      })
    },
    // OWNER 不能退出团队（退了就没人管了），界面上直接不给这个动作
    canLeave() {
      // OWNER 不能退出（退了就没人管了）；没有 myAccountId 时也不给，见 data 里的注释
      return !!this.team && this.myRole !== 'OWNER' && !!this.myAccountId
    },
    hasSummary() {
      return !!(this.summary && this.summary.kpi)
    },
    kpi() {
      return (this.summary && this.summary.kpi) || {}
    },
    projects() {
      return (this.summary && this.summary.projects) || []
    },
    inviteRoleLabels() {
      return ROLE_KEYS.map((r) => this.roleLabel(r))
    },
    savedFormulaText() {
      const f = this.summary && this.summary.savedMinutesFormula
      if (!f || typeof f.perAgentEdit !== 'number' || typeof f.perAiTurn !== 'number') {
        return this.$t('team.savedFormulaUnknown')
      }
      return this.$t('team.savedFormula', { perAgentEdit: f.perAgentEdit, perAiTurn: f.perAiTurn })
    },
    lastUploadText() {
      const at = this.sharing.lastUploadAt
      if (!at) return this.$t('team.lastUploadNever')
      return this.$t('team.lastUploadAt', { time: String(at).replace('T', ' ').slice(0, 16) })
    },
    // 开关行的说明。不可用时说明原因，其余时候说这个开关到底会做什么——
    // 绝不把上面那行标题原样再念一遍
    sharingHint() {
      if (this.sharing.available === false) return this.$t('team.sharingDesktopOnly')
      return this.$t('team.sharingSwitchDesc')
    },
  },
  mounted() {
    this.reload()
  },
  methods: {
    hoursLabel(minutes) {
      const n = Number(minutes || 0)
      return this.$t('team.hours', { hours: (n / 60).toFixed(1) })
    },
    // 能不能对这一行动手。OWNER 不可改不可踢（契约 403），自己退出走「退出团队」。
    // 这只决定显不显示按钮，真正的闸门在官网。
    canActOn(member) {
      if (!this.canManage) return false
      if (member.role === 'OWNER') return false
      return !(this.myAccountId && member.accountId === this.myAccountId)
    },
    roleLabel(role) {
      if (role === 'OWNER') return this.$t('team.roleOwner')
      if (role === 'ADMIN') return this.$t('team.roleAdmin')
      return this.$t('team.roleMember')
    },
    // 邀请到期时间。服务端没给就明说「以官网为准」，绝不自己按「7 天」算一个
    // 日期出来——那个数字看起来精确，但只要官网改了有效期就在骗人
    expiryText(raw) {
      if (!raw) return this.$t('team.inviteExpiresUnknown')
      return this.$t('team.inviteExpires', { date: String(raw).replace('T', ' ').slice(0, 16) })
    },
    toast(title) {
      uni.showToast({ title, icon: 'none' })
    },
    async reload() {
      this.loadError = false
      try {
        const status = await getAccountStatus()
        this.connected = !!(status && status.connected)
      } catch (e) {
        this.connected = false
        return
      }
      if (!this.connected) return
      try {
        const data = await getTeam()
        this.team = (data && data.team) || null
        this.myRole = (data && data.myRole) || ''
        this.myAccountId = (data && data.myAccountId) || ''
        // 缺字段/null 一律归成空串，界面只认「非空字符串才渲染复制与重置」
        this.joinCode = typeof (data && data.joinCode) === 'string' ? data.joinCode : ''
        this.members = (data && data.members) || []
        this.pendingInvites = (data && data.pendingInvites) || []
        this.receivedInvites = (data && data.invites) || []
        this.firm = (data && data.firm) || null
        this.renameDraft = this.team ? this.team.name || '' : ''
        this.firmRenameDraft = this.firm ? this.firm.name || '' : ''
        // 退出律所之后「全所」这个视角就不存在了，停在它上面会一直打一个必然被拒的请求
        if (!this.firm) this.scope = 'team'
      } catch (e) {
        this.loadError = true
        return
      }
      await this.loadSharing()
      if (this.team) await this.loadSummary()
    },
    async loadSharing() {
      try {
        const s = await getTeamUsageSharing()
        this.sharing = {
          enabled: !!(s && s.enabled),
          lastUploadAt: (s && s.lastUploadAt) || '',
          available: s ? s.available : undefined,
        }
      } catch (e) {
        // 开关读不到不该让整个面板报错：其余部分照常可用
      }
    },
    async loadSummary() {
      try {
        const data = await getTeamSummary(this.range, this.scope)
        this.summary = data || null
        // summary 的成员行带统计数字，比 /team 那份更完整；有就用它
        if (data && Array.isArray(data.members) && data.members.length) {
          this.members = data.members
        }
      } catch (e) {
        this.summary = null
      }
    },
    setRange(days) {
      if (this.range === days) return
      this.range = days
      this.loadSummary()
    },
    setScope(next) {
      if (this.scope === next) return
      this.scope = next
      this.loadSummary()
    },
    async run(fn) {
      if (this.busy) return
      this.busy = true
      try {
        await fn()
      } catch (e) {
        this.toast((e && e.message) || this.$t('team.loadFailed'))
      } finally {
        this.busy = false
      }
    },
    onGoAccount() {
      this.$emit('go-account')
    },
    onCreateTeam() {
      const name = (this.newTeamName || '').trim()
      if (!name) {
        this.toast(this.$t('team.createFailedEmpty'))
        return
      }
      this.run(async () => {
        await createTeam(name)
        this.newTeamName = ''
        await this.reload()
      })
    },
    onJoinTeam() {
      const code = (this.joinCodeInput || '').trim()
      if (!code) {
        this.toast(this.$t('team.joinCodeEmpty'))
        return
      }
      this.run(async () => {
        await joinTeam(code)
        this.joinCodeInput = ''
        await this.reload()
      })
    },
    onAcceptInvite(invite) {
      this.run(async () => {
        await acceptTeamInvite(invite.id)
        await this.reload()
      })
    },
    // ---- 邀请码：复制 / 重置 ----
    onCopyCode(code) {
      const value = (code || '').trim()
      if (!value) {
        this.toast(this.$t('team.copyFailed'))
        return
      }
      uni.setClipboardData({
        data: value,
        success: () => this.toast(this.$t('team.codeCopied')),
        fail: () => this.toast(this.$t('team.copyFailed')),
      })
    },
    onResetJoinCode() {
      uni.showModal({
        title: this.$t('team.resetCode'),
        content: this.$t('team.confirmResetCode'),
        success: (res) => {
          if (!res.confirm) return
          this.run(async () => {
            await regenerateTeamJoinCode()
            await this.reload()
          })
        },
      })
    },
    // ---- 律所 ----
    onCreateFirm() {
      const name = (this.newFirmName || '').trim()
      if (!name) {
        this.toast(this.$t('team.firmNameEmpty'))
        return
      }
      this.run(async () => {
        await createFirm(name)
        this.newFirmName = ''
        await this.reload()
      })
    },
    onJoinFirm() {
      const code = (this.firmCodeInput || '').trim()
      if (!code) {
        this.toast(this.$t('team.joinCodeEmpty'))
        return
      }
      this.run(async () => {
        await joinFirm(code)
        this.firmCodeInput = ''
        await this.reload()
      })
    },
    onRenameFirm() {
      const name = (this.firmRenameDraft || '').trim()
      if (!name) {
        this.toast(this.$t('team.firmNameEmpty'))
        return
      }
      this.run(async () => {
        await updateFirm(name)
        await this.reload()
      })
    },
    onResetFirmCode() {
      uni.showModal({
        title: this.$t('team.resetCode'),
        content: this.$t('team.confirmResetCode'),
        success: (res) => {
          if (!res.confirm) return
          this.run(async () => {
            await regenerateFirmJoinCode()
            await this.reload()
          })
        },
      })
    },
    onRemoveFirmTeam(t) {
      uni.showModal({
        title: this.$t('team.removeFirmTeam'),
        content: this.$t('team.confirmRemoveFirmTeam'),
        success: (res) => {
          if (!res.confirm) return
          this.run(async () => {
            await removeFirmTeam(t.id)
            await this.reload()
          })
        },
      })
    },
    // 本团队退出律所走的是同一个端点，只是拿自己的 teamId。
    // team.id 取不到就不发——猜一个 id 出去会把别人的团队踢出律所。
    onLeaveFirm() {
      const teamId = this.team && this.team.id
      if (!teamId) {
        this.toast(this.$t('team.loadFailed'))
        return
      }
      uni.showModal({
        title: this.$t('team.leaveFirm'),
        content: this.$t('team.confirmLeaveFirm'),
        success: (res) => {
          if (!res.confirm) return
          this.run(async () => {
            await removeFirmTeam(teamId)
            await this.reload()
          })
        },
      })
    },
    onInviteRolePick(index) {
      this.inviteRoleIndex = index
    },
    onInvite() {
      const phone = (this.invitePhone || '').trim()
      if (!phone) {
        this.toast(this.$t('team.invitePhoneEmpty'))
        return
      }
      this.run(async () => {
        await createTeamInvite(phone, ROLE_KEYS[this.inviteRoleIndex] || 'MEMBER')
        this.invitePhone = ''
        await this.reload()
      })
    },
    // 撤销是删行、不可回退，与「移除成员」同款确认
    onRevokeInvite(invite) {
      uni.showModal({
        title: this.$t('team.revokeInvite'),
        content: this.$t('team.confirmRevokeInvite', { phone: invite.phone }),
        success: (res) => {
          if (!res.confirm) return
          this.run(async () => {
            await revokeTeamInvite(invite.id)
            await this.reload()
          })
        },
      })
    },
    // 改角色是权限变更，点一下就生效太轻了：先把「谁、从什么改成什么」摆出来确认
    onChangeRole(member) {
      const next = member.role === 'ADMIN' ? 'MEMBER' : 'ADMIN'
      uni.showModal({
        title: this.$t('team.changeRole'),
        content: this.$t('team.confirmChangeRole', {
          name: member.displayName || member.accountId,
          from: this.roleLabel(member.role),
          to: this.roleLabel(next),
        }),
        success: (res) => {
          if (!res.confirm) return
          this.run(async () => {
            await updateTeamMemberRole(member.accountId, next)
            await this.reload()
          })
        },
      })
    },
    onRemoveMember(member) {
      uni.showModal({
        title: this.$t('team.removeMember'),
        content: this.$t('team.confirmRemove'),
        success: (res) => {
          if (!res.confirm) return
          this.run(async () => {
            await removeTeamMember(member.accountId)
            await this.reload()
          })
        },
      })
    },
    onLeaveTeam() {
      uni.showModal({
        title: this.$t('team.leaveTeam'),
        content: this.$t('team.confirmLeave'),
        success: (res) => {
          if (!res.confirm) return
          this.run(async () => {
            await removeTeamMember(this.myAccountId)
            await this.reload()
          })
        },
      })
    },
    onRename() {
      const name = (this.renameDraft || '').trim()
      if (!name) {
        this.toast(this.$t('team.createFailedEmpty'))
        return
      }
      this.run(async () => {
        await updateTeam({ name })
        await this.reload()
      })
    },
    onToggleShareNames(next) {
      this.run(async () => {
        await updateTeam({ shareProjectNames: !!next })
        await this.reload()
      })
    },
    onToggleSharing(next) {
      this.run(async () => {
        // 切换失败不改本地状态：重新读一次让界面回到真相，而不是显示一个没生效的档位
        await setTeamUsageSharing(!!next)
        await this.loadSharing()
      })
    },
    onSetAlias(project) {
      uni.showModal({
        title: this.$t('team.setAlias'),
        editable: true,
        placeholderText: this.$t('team.aliasPlaceholder'),
        content: project.label || '',
        success: (res) => {
          // content 为 undefined 说明这个平台的 showModal 没有实现 editable——
          // 那时把它当成 '' 会静默清掉已有别名，只能按取消处理
          if (!res.confirm || typeof res.content !== 'string') return
          this.run(async () => {
            await setTeamProjectAlias(project.projectKey, res.content)
            await this.loadSummary()
          })
        },
      })
    },
    onUploadNow() {
      this.run(async () => {
        const result = await uploadTeamUsageNow()
        await this.loadSharing()
        if (result && result.skipped) {
          const reasons = {
            disabled: 'team.skipDisabled',
            not_local_mode: 'team.skipNotLocalMode',
            not_connected: 'team.skipNotConnected',
            no_team: 'team.skipNoTeam',
          }
          this.toast(this.$t(reasons[result.reason] || 'team.loadFailed'))
          return
        }
        const days = (result && result.uploaded) || 0
        this.toast(days ? this.$t('team.uploadDone', { days }) : this.$t('team.uploadNothing'))
      })
    },
  },
}
</script>

<style scoped>
.team-pane {
  display: flex;
  flex-direction: column;
  gap: 14px;
  /* 右下角的反馈浮窗是全局元素，会压住页面最后一行。这里给它让出高度，
     保证面板最后一个可点控件不被盖住（改这个值前先看反馈浮窗的实际高度）。 */
  padding-bottom: 72px;
}

/* 无团队态的三条路并排。窄了自动换行，但每条仍占满一列不塌成一行文字 */
.join-paths {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  align-items: flex-start;
}

.join-card {
  flex: 1 1 240px;
  min-width: 220px;
}

.section-card {
  background: var(--awd-surface);
  border: 1px solid var(--awd-border);
  border-radius: 8px;
  overflow: hidden;
}

.section-header {
  padding: 14px 18px 10px;
  border-bottom: 1px solid var(--awd-border-subtle);
}

.section-title {
  display: block;
  font-size: 14px;
  font-weight: 600;
  color: var(--awd-text);
}

.section-subtitle {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--awd-text-2);
  line-height: 18px;
}

.section-body {
  padding: 14px 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.privacy-line {
  font-size: 12px;
  color: var(--awd-text-2);
  line-height: 18px;
}

/* KPI 磁贴：与 OverviewStatsBar 的 .stat-tile 同一形制。
   刻意用固定五列的 grid，两种更「聪明」的写法都实测不行：
     - flex `1 1 140px`（改前的写法）在 1440 窗口下把五块排成 4+1，最后一块独占整行；
     - `auto-fit + minmax(120px, 1fr)` 只是把这个断点挪到容器 600px 附近，
       而那对应 1280 宽的窗口——最常见的笔记本尺寸，照样 4+1。
   `repeat(5, minmax(0, 1fr))` 实测在容器 600px 以上五块同排且文字零裁切
   （量法：把这段样式搬进静态页，按容器宽逐档量 stat-tile 的 top 分组与 scrollWidth）。
   minmax 的下界必须是 0：默认的 auto 下界等于内容宽，长文案会把列撑开又变回换行。 */
.stats-tiles {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
}

.stat-tile {
  padding: 10px 12px;
  background: var(--awd-bg);
  border-left: 3px solid var(--awd-mint);
  border-radius: 4px;
}

.stat-value {
  display: block;
  font-size: 15px;
  font-weight: 600;
  color: var(--awd-accent-text);
  line-height: 22px;
}

/* 1280 宽下这一列只有 86.4px 可用，而「节约时间（估算）」是 8 个全角字符：
   11px 时正好 88px，溢出 1.6px，最后那个「）」被切掉（走查 C 第 9 条）。
   只放开 white-space 不够——实测 Chrome 在这个宽度上仍不折行（唯一的断点在「估」
   前面，「）」又不许起行），字号必须降到 10.5px（8 × 10.5 = 84px，留 2.4px 余量）。
   量法：把这段样式搬进静态页，按 86.4px 的盒宽取 Range.getClientRects()。
   .stat-sub 跟着一起降，否则副行比正行还大。 */
.stat-caption {
  display: block;
  margin-top: 2px;
  font-size: 10.5px;
  color: var(--awd-text-2);
  white-space: normal;
  overflow-wrap: anywhere;
  line-height: 1.3;
}

.stat-sub {
  display: block;
  margin-top: 1px;
  font-size: 10.5px;
  color: var(--awd-text-3);
  line-height: 1.3;
}

.team-footnote {
  font-size: 11px;
  color: var(--awd-text-3);
  line-height: 16px;
}

.team-days-row {
  display: flex;
  gap: 6px;
  margin-top: 8px;
}

.team-days-btn {
  padding: 3px 10px;
  font-size: 12px;
  color: var(--awd-text-2);
  border: 1px solid var(--awd-border);
  border-radius: 4px;
  cursor: pointer;
}

.team-days-btn.active {
  color: var(--awd-accent-text);
  border-color: var(--awd-accent);
  background: var(--awd-accent-wash);
}

/* 表格：与 telemetry 分区的 .telemetry-list 同一密度 */
.team-table {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--awd-border-subtle);
  border-radius: 4px;
  overflow-x: auto;
}

.team-table-head,
.team-table-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  min-width: 560px;
}

.team-table-head {
  background: var(--awd-surface-2);
}

.team-table-row + .team-table-row,
.team-table-head + .team-table-row {
  border-top: 1px solid var(--awd-border-subtle);
}

.col {
  flex: 1 1 0;
  font-size: 12px;
  color: var(--awd-text-2);
}

.team-table-row .col {
  color: var(--awd-text);
}

.col-name {
  flex: 2 1 0;
  font-weight: 500;
}

.col-actions {
  flex: 1.4 1 0;
  display: flex;
  gap: 10px;
  justify-content: flex-end;
}

.link-action {
  font-size: 12px;
  color: var(--awd-accent-text);
  cursor: pointer;
}

.link-action.danger {
  color: var(--awd-danger-text);
}

.team-list-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px solid var(--awd-border-subtle);
}

.team-list-main {
  flex: 1;
  min-width: 0;
}

.team-list-name {
  display: block;
  flex: 1;
  font-size: 13px;
  color: var(--awd-text);
}

.team-list-meta {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: var(--awd-text-3);
  line-height: 16px;
}

/* 角色标签与「撤销」贴在一起时误点的是不可撤销的那个 */
.team-list-action {
  margin-left: 16px;
}

.head-badge {
  margin-left: 6px;
  padding: 1px 6px;
  font-size: 11px;
  color: var(--awd-accent-text);
  background: var(--awd-accent-wash);
  border-radius: 3px;
}

.team-list-count {
  /* 固定宽 + 右对齐：一列人数要对得齐，不能跟着后面有没有按钮左右横跳 */
  min-width: 48px;
  text-align: right;
  font-size: 12px;
  color: var(--awd-text-2);
}

/* 动作位恒占宽，总部那行没有「移出律所」时也占着，前面的人数才不会甩到行尾 */
.team-list-tail {
  flex: 0 0 auto;
  min-width: 72px;
  margin-left: 16px;
  display: flex;
  justify-content: flex-end;
}

.team-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

/* flex-basis 必须是 auto：写成长度值（改前是 200px）时，只要这个输入框落在
   竖向 flex 的 .section-body 里，basis 就落到**高度**上，输入框会被撑成 202px
   高的一大块（走查 C 第 2 条）。auto 让 basis 回到 height: 32px。
   刻意不写 width: 100%：那会让 basis 变成整行宽，.team-row 又是 wrap 的，
   「律所名称 + 保存」这类一行两件的行会被折成两行（静态页实测）。 */
.team-input {
  flex: 1 1 auto;
  min-width: 0;
  height: 32px;
  box-sizing: border-box;
  padding: 0 10px;
  font-size: 13px;
  color: var(--awd-text);
  background: var(--awd-bg);
  border: 1px solid var(--awd-border);
  border-radius: 4px;
}

.team-select {
  flex: 0 0 120px;
}

/* .section-body 是竖向 flex，块级按钮会被拉满整张卡的宽度（走查 B1/B2）。
   inline-flex + align-self 让它只占自己的内容宽度。 */
.team-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  align-self: flex-start;
  width: auto;
  padding: 6px 14px;
  font-size: 13px;
  color: var(--awd-text);
  background: var(--awd-surface-2);
  border: 1px solid var(--awd-border);
  border-radius: 4px;
  cursor: pointer;
}

.team-btn.primary {
  color: var(--awd-text-on-accent);
  background: var(--awd-accent);
  border-color: var(--awd-accent);
}

.team-btn.small {
  padding: 4px 10px;
  font-size: 12px;
}

.team-btn.is-busy {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 次级危险：描边而不是实心，和上面的团队列表拉开，也不抢主按钮的位置 */
.team-btn.danger {
  color: var(--awd-danger-text);
  border-color: var(--awd-danger-text);
  background: transparent;
}

/* 「退出律所」与团队列表之间必须断开，否则它读起来像列表的最后一行 */
.firm-leave-row {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--awd-border-subtle);
  display: flex;
}

/* 邀请码行：码本身要大到能一眼读出来、也能整串选中复制 */
.code-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 10px 12px;
  background: var(--awd-bg);
  border: 1px solid var(--awd-border-subtle);
  border-radius: 4px;
}

.code-main {
  flex: 1 1 220px;
  min-width: 0;
}

.code-label {
  display: block;
  font-size: 12px;
  color: var(--awd-text-2);
}

.code-value {
  display: block;
  margin-top: 2px;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 2px;
  color: var(--awd-text);
  user-select: text;
}

.code-desc {
  display: block;
  margin-top: 3px;
  font-size: 11px;
  color: var(--awd-text-3);
  line-height: 16px;
}

.code-actions {
  display: flex;
  gap: 8px;
}

.switch-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  background: var(--awd-bg);
  border: 1px solid var(--awd-border-subtle);
  border-radius: 4px;
}

.switch-info {
  flex: 1;
}

.switch-name {
  display: block;
  font-size: 13px;
  color: var(--awd-text);
}

.switch-desc {
  display: block;
  margin-top: 3px;
  font-size: 11px;
  color: var(--awd-text-2);
  line-height: 16px;
}

.sub-title {
  display: block;
  margin-top: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--awd-text-2);
}

.empty-line {
  font-size: 12px;
  color: var(--awd-text-3);
  line-height: 18px;
}
</style>
