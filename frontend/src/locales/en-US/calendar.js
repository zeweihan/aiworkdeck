// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Calendar/task system (global calendar page, project calendar pane, file deadline dialog, TaskSchedule)
// spec: docs/superpowers/specs/2026-08-20-calendar-view-design.md
export default {
  pageTitle: 'Calendar',
  backToProjects: 'Back to Projects',
  today: 'Today',
  viewMonth: 'Month',
  viewWeek: 'Week',
  viewList: 'List',
  loading: 'Loading...',
  loadFailed: 'Failed to load. Please try again later.',

  // Upcoming deadlines list (global page sidebar)
  upcomingTitle: 'Upcoming Deadlines',
  upcomingEmpty: 'No open deadlines',
  dueToday: 'Due today',
  daysLeft: 'In {count} days',
  overdueDays: 'Overdue by {count} days',

  // Task create/edit dialog
  createTask: 'New Event',
  editTask: 'Edit Event',
  taskTitleLabel: 'Title',
  taskTitlePlaceholder: 'e.g. File the defense, court hearing',
  projectLabel: 'Project',
  selectProject: 'Select a project',
  dateLabel: 'Date',
  timeLabel: 'Time (optional)',
  fileLabel: 'Linked File',
  noLinkedFile: 'None',
  statusOpen: 'Open',
  statusDone: 'Done',
  markDone: 'Mark Done',
  markOpen: 'Reopen',
  save: 'Save',
  cancel: 'Cancel',
  delete: 'Delete',
  deleteConfirmTitle: 'Delete Event',
  deleteConfirmContent: 'Delete "{title}"?',
  requiredTitle: 'Please enter a title',
  requiredDate: 'Please pick a date',
  requiredProject: 'Please select a project',
  saved: 'Saved',
  deleted: 'Deleted',
  saveFailed: 'Failed to save',
  deleteFailed: 'Failed to delete',
  openProject: 'Open Project',

  // Holiday badges (chinese-days: statutory holidays and makeup workdays)
  holidayRest: 'Off',
  holidayWork: 'Work',
  aiSourceTag: 'AI',

  // Project calendar pane (rail)
  paneEmpty: 'No events this month',
  addQuick: 'Add',
  openGlobalCalendar: 'Open Full Calendar',

  // File context menu "Set Deadline"
  setDeadline: 'Set Deadline',
  deadlineDialogTitle: 'Set Deadline',
  deadlineForFile: 'Set a deadline for "{name}"',
  deadlineSet: 'Deadline set',

  // TaskSchedule (overview schedule block) extension
  showDone: 'Show completed',

  // ==================== Task system redesign (dev-board#896, spec 2026-09-25-task-calendar-redesign) ====================
  // Wording: "Task" is an item, "Schedule" is the calendar view.
  // All keys needed by the follow-up cards (schedule page, rail, project list overview, file tree, commands) live here.

  // Types
  typeLabel: 'Type',
  typeDeadline: 'Deadline',
  typeHearing: 'Hearing',
  typeMeeting: 'Meeting',
  typeTodo: 'To-do',
  typeOther: 'Other',
  priorityHigh: 'Important',

  // Reminders
  remindLabel: 'Reminder',
  remindNone: 'No reminder',
  remindAtTime: 'At time of task',
  remind30m: '30 minutes before',
  remind1h: '1 hour before',
  remind1d: '1 day before',
  remind3d: '3 days before',
  remind1w: '1 week before',
  remindAllDayHint: 'All-day tasks remind relative to 9:00 AM',
  remindSet: 'Reminder set',

  // Agenda groups
  groupOverdue: 'Overdue',
  groupToday: 'Today',
  groupWeek: 'This Week',
  groupLater: 'Later',
  groupDone: 'Completed',
  groupDoneCount: 'Completed ({count})',

  // Due badges (taskUtils.dueBadge)
  dueOverdueDays: '{count}d overdue',
  dueTodayShort: 'Today',
  dueTomorrow: 'Tomorrow',
  dueInDays: 'In {count} days',
  dueMonthDay: '{month}/{day}',
  dueYearMonthDay: '{month}/{day}/{year}',

  // Task dialog
  dialogCreateTitle: 'New Task',
  dialogEditTitle: 'Edit Task',
  titlePlaceholder: "Task name, type {'@'} to link files or members",
  timeField: 'Time',
  timePlaceholder: 'All day',
  timeClear: 'Clear time',
  assigneeLabel: 'Assignee',
  unassigned: 'Unassigned',
  filesLabel: 'Linked Files',
  addFile: 'Add File',
  filePickerTitle: 'Select a File to Link',
  fileMissing: 'File deleted',
  removeFile: 'Remove',
  moreFiles: '{count} more files',
  notesLabel: 'Notes',
  notesPlaceholder: "Add details, type {'@'} to link files or members",
  openFile: 'Open File',
  saving: 'Saving...',
  saveShortcutHint: 'Ctrl/⌘ + Enter to save',
  noWritableProject: 'No projects you can add tasks to',
  pickProjectFirst: 'Select a project first',
  edit: 'Edit',
  doneLabel: 'Completed',

  // @ picker in task inputs
  mentionTitle: 'Link a File or Member',
  mentionHint: '↑↓ to select, Enter to confirm, Esc to close',
  mentionNoMatch: 'No matching files or members',

  // Schedule page header / views / filters
  schedulePageTitle: 'Schedule',
  back: 'Back',
  prevPeriod: 'Previous',
  nextPeriod: 'Next',
  monthTitle: '{month} {year}',
  viewAgenda: 'Agenda',
  newTask: 'New Task',
  filter: 'Filter',
  filterProjects: 'Projects',
  filterTypes: 'Types',
  filterAllProjects: 'All Projects',
  filterAllTypes: 'All Types',
  filterIncludeDone: 'Include completed',
  filterReset: 'Reset',
  agendaStats: 'Overdue {overdue} · Today {today} · This week {week}',

  // Empty-state guide (three ways to create)
  emptyGuideTitle: 'No tasks yet',
  emptyGuideCreate: 'Click "New Task" at the top right',
  emptyGuideFile: 'Right-click a file and choose "Add Task…" to link it',
  emptyGuideAi: 'Tell the AI "Add a hearing next Wednesday"',

  // Project list overview strip
  overviewProjects: 'Projects',
  overviewOverdue: 'Overdue',
  overviewToday: 'Today',
  overviewWeek: 'This Week',
  viewSchedule: 'View Schedule',

  // Entry points: rail / avatar menu / command palette / workbench pane
  railSchedule: 'Schedule',
  mySchedule: 'My Schedule',
  cmdSchedule: 'Schedule',
  cmdNewTask: 'New Task…',
  viewFullSchedule: 'Open Full Schedule',
  paneEmptyTasks: 'No tasks in this project yet',

  // File context menu
  fileAddTask: 'Add Task…',
  fileViewTasks: 'View Tasks ({count})',

  // Local reminders (taskReminders)
  notifyTitle: '{type}: {title}',
  notifyBody: '{project} · {when}',
  notifyWhenAllDay: '{date}, all day',
  notifyWhenTime: '{date} {time}',
  notifyToast: '{type} reminder: {title} ({when})',

  // Daily digest (once a day on the project list)
  digestTodayAndOverdue: 'Due today: {count}, overdue: {overdue}',
  digestTodayOnly: 'Due today: {count}',
  digestOverdueOnly: 'Overdue: {overdue}',

  // Project list overview: next due line (dev-board#898)
  overviewNextDue: 'Next: {when} {title}',

  // 日程页议程：有事项但被筛光（dev-board#897）
  agendaNoMatch: 'No tasks match the filters',

  // Workbench schedule pane / rail badge (dev-board#899)
  paneAll: 'All',
  paneAllTasks: 'All tasks',
  paneOnlyFile: 'Only: {name}',
  paneClearFilter: 'Clear filter',
  paneEmptyMonth: 'No tasks this month',
  paneEmptyFile: 'No tasks linked to this file yet',
  railScheduleTitle: 'Schedule ({overdue} overdue · {today} today)',
}
