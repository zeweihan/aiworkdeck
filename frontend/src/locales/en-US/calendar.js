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
}
