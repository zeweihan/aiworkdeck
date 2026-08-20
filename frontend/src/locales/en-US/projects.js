// Project list page + Project overview page (project-list.vue / project-home.vue and its five project-home/* subcomponents)
export default {
  // project-list.vue: header and empty states
  myProjects: 'My Projects',
  pullFromTeamLibrary: 'Pull a Case File from the Team Case Library',
  // Lands on the Personal group of the unified Settings page (merged 2026-08-20; key kept)
  personalCenter: 'Settings',
  // Header "Calendar" entry, opens pages/calendar/calendar (cross-project task calendar)
  calendarEntry: 'Calendar',
  allProjects: 'All Projects',
  loading: 'Loading…',
  clientEmptyHint: 'After your lawyer shares a case file with you, it will appear here.',
  newProject: 'New Project',
  createSectionTitle: 'New',
  emptyHint: 'No matters yet. Start below: open an existing folder, or create one.',
  // project-list.vue: view toggle and list-view column headers
  gridView: 'Grid view',
  listView: 'List view',
  nameColumn: 'Name',
  clientColumn: 'Client',
  clientInferred: 'inferred',
  detailToggle: 'Details',
  detailToggleHint: 'Show matter type, counterparty, opened date and next step from the project profile (filled in on the project overview)',
  matterTypeField: 'Matter type',
  counterpartyField: 'Counterparty',
  openedAtField: 'Opened',
  nextStepField: 'Next step',
  createdColumn: 'Created',
  updatedColumn: 'Last modified',
  membersColumn: 'Members',
  createdAtShort: 'Created {time}',
  rename: 'Rename',
  // project-list.vue: cards
  delete: 'Delete',
  managerLabel: 'Lead: {name}',
  unknown: 'Unknown',
  clientLabel: 'Client',
  clientMemberTitle: '{name} (Client)',
  clientInitial: 'C',
  listedCompany: 'Listed Company',
  targetCompany: 'Target Company',
  // project-list.vue: remove member
  removeConfirmTitle: 'Confirm Removal',
  removeConfirmContent: 'Are you sure you want to remove this member?',
  cancel: 'Cancel',
  confirm: 'Confirm',
  removeSuccess: 'Removed',
  removeFailed: 'Failed to remove',
  // project-list.vue: load / rename / delete
  loadFailedRetry: 'Failed to load. Please try again later',
  projectNameEmpty: 'Project name cannot be empty',
  renameSuccess: 'Renamed',
  renameFailed: 'Failed to rename',
  deleteConfirmTitle: 'Confirm Deletion',
  deleteConfirmContent: 'Are you sure you want to delete this project? This cannot be undone.',
  deleteSuccess: 'Deleted',
  deleteFailedRetry: 'Failed to delete. Please try again later',

  // project-home.vue
  backToList: 'Back to Projects',
  overviewPageTitle: 'Project Overview',
  enterWorkbench: 'Enter Workbench',
  activitySectionTitle: 'Activity',
  taskSectionTitle: 'Schedule & Tasks',
  conversationSectionTitle: 'AI Conversations',
  missingProjectParam: 'Missing project parameter',
  saveFailed: 'Save failed',

  // ProfileHeader.vue
  profileEmptyGuideDesc: "This case file's profile is still empty. Fill in the client and matter type first, so colleagues and clients can tell what this case is about at a glance.",
  startFilling: 'Start Filling In',
  selectMatterType: 'Select Matter Type',
  notFilled: 'Not filled in',
  placeholderClient: 'e.g., Acme Technology Co., Ltd.',
  placeholderOpenedAt: 'e.g., 2026-08-01',
  placeholderNextStep: 'e.g., First draft of due diligence report by August 15',
  placeholderCounterparty: 'e.g., Beta Trading Co., Ltd.',

  // OverviewStatsBar.vue
  statsLoadingHint: 'Loading project status…',
  folderCountLabel: '{count} folders',
  folderCaption: 'Excludes system directories',
  memberCountLabel: '{count} members',
  memberCaption: 'Includes the lead',
  runCountLabel: '{count} background tasks',
  noRunningTasks: 'No tasks running right now',
  recentRunPrefix: 'Latest: {status}',
  runFinished: 'Finished',
  localRootCaption: 'Local folder, as of the last reconciliation',
  defaultFileCaption: 'Excludes the staging area and AI-generated directories',

  // ActivityFeed.vue
  activityLoadingHint: 'Loading activity…',
  noVersionHistoryTitle: 'No version history for this case file yet',
  noVersionHistoryDesc: 'Once enabled, every change is saved automatically, and this list will show what happened over time. You can turn it on from the Version History panel on the right side of the workbench.',
  noActivityTitle: 'No activity yet',
  noActivityDesc: 'Once you edit files or run an AI task in the workbench, records will show up here.',
  aiTaskLabel: 'AI Task {status}',

  // TaskSchedule.vue
  tasksLoadingHint: 'Loading tasks…',
  noTasksTitle: 'No tasks scheduled yet',
  noTasksDesc: 'Due dates, to-dos, and reminders will show up here.',
  statusDoing: 'In Progress',
  statusDone: 'Done',
  statusOpen: 'To-do',

  // ConversationList.vue
  conversationsLoadingHint: 'Loading conversation history…',
  noConversationsTitle: 'No AI conversations for this case file yet',
  noConversationsDesc: 'Open the AI Panel in the workbench to ask your first question — every conversation after that will show up here.',
  loadMoreConversations: 'Load Earlier Conversations',
}
