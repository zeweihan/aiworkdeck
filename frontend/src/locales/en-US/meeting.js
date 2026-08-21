// All meeting-recording copy: the panel (components/MeetingRecordingPanel.vue) and the
// recording engine (utils/meetingRecorder.js), whose errors render inside that panel.
//
// Copy red lines: no emoji anywhere, and the Chinese strings must never contain the three
// sign-out substrings (see the zh-CN file for the full reasoning).
//
// The tier labels deliberately duplicate platform.js rather than share keys: there they name
// options in a settings dropdown, here they are a status badge on the recording panel.
// The panel title is not here: since #389 the shell's sidebar-header renders it, and its copy
// comes from config.sidebar.meetingRecorder (the rail icon and the title must be one fact).
export default {
  // ---- Transcription tier and the "keep recordings on this device" toggle ----
  tierLabel: 'Transcription',
  tierPlatform: 'Platform-sourced',
  tierByok: 'Your own key',
  tierLocal: 'On this device',
  tierNeedsAccount: 'Account needed',
  tierDescLocal: 'Recording and transcription both happen on this device, and the audio never leaves it. Expect roughly 1.5x real time (a two-hour meeting takes about an hour) and no speaker separation.',
  tierDescByok: 'Transcribed with your own Alibaba Cloud Tingwu account; the audio passes through your own OSS bucket.',
  tierDescNoPlatform: 'This deployment uses your own key. Enter the Tingwu credentials under System settings - Platform Services.',
  tierDescNotConnected: 'Connect your AI WorkDeck account and transcription works right away, with no Tingwu account of your own.',
  tierDescPlatform: 'AI WorkDeck transcribes for you and bills Credits from your account balance by duration. The audio passes through our object storage and is deleted as soon as transcription finishes, with a 24-hour sweep as a backstop.',
  localSwitchLabel: 'Keep recordings on this device',
  localSwitchNoteOn: 'Audio is not uploaded and transcription runs on this device; slower than the cloud tier, with no speaker separation.',
  localSwitchNoteReady: 'Once on, audio is not uploaded and transcription runs on this device (slower than the cloud tier, with no speaker separation).',
  localSwitchNoteNeedsModel: 'This needs the on-device transcription model, which you can download below.',
  switchFailed: 'Could not switch. Try again later.',

  // ---- On-device transcription model (downloadable in place) ----
  modelDownloading: 'Downloading model {percent}%',
  cancelDownload: 'Cancel Download',
  // The "~" belongs here, not in {size}: {size} is model-manager's sizeHint (like "1.5 GB",
  // language-neutral) and the same value also feeds the admin confirm copy.
  downloadModel: 'Download Model (~{size})',
  recheck: 'Check Again',
  downloadStartFailed: 'Could not start the download. Try again later.',

  // ---- No transcription credentials yet (recording still works). Next step depends on tier ----
  notConfiguredPlatform: 'Transcription is unavailable for now: recordings are still saved to project files, but cannot be turned into text. Connect your AI WorkDeck account under System settings - Account & Usage to enable it.',
  notConfiguredByok: 'No transcription service configured: recordings are still saved to project files, but cannot be turned into text. An administrator can enter Alibaba Cloud Tingwu credentials under System settings - Platform Services - Meeting Transcription, or switch to platform-sourced.',

  // ---- Recording ----
  micDeviceLabel: 'Microphone',
  micDeviceFallbackName: 'Microphone {n}',
  micDeviceFallback: 'The selected microphone is unavailable; switched to the default device',
  startRecording: 'Start Recording',
  startHint: 'Tap to start. Speakers are separated automatically, and transcription runs once you stop.',
  recording: 'Recording',
  connectingMic: 'Connecting to microphone…',
  paused: 'Paused',
  interrupted: 'Interrupted',
  pause: 'Pause',
  resume: 'Resume',
  stopRecording: 'Stop Recording',
  saving: 'Saving...',
  backgroundHint: 'Recording keeps running when you switch pages, and can be stopped any time from the capsule at the top.',
  otherProjectRecording: 'Another project is recording. Stop it from the capsule at the top and you can start one here.',
  cannotStartRecording: 'Could not start recording',

  // ---- Recording engine, utils/meetingRecorder.js ----
  alreadyRecording: 'A recording is already in progress',
  recordingUnsupported: 'Recording is not supported in this environment',
  micPermissionDenied: 'Could not get microphone permission. Allow this app to use the microphone in your system settings.',
  finishWriteBackFailed: 'The recording was saved, but its status could not be written back: {message}',
  uploadStalled: 'Upload stalled, retrying (attempt {attempt})',
  deviceInterrupted: 'The recording device was disconnected or its permission was revoked. Recording has stopped — click Stop to save what was captured.',

  // ---- List and status badges ----
  sectionTitle: 'Recordings',
  empty: 'No recordings yet. Use "Start Recording" above.',
  statusRecording: 'Recording',
  statusRecorded: 'Not transcribed',
  statusTranscribing: 'Transcribing',
  statusTranscribed: 'Transcribed',
  statusEmpty: 'No speech detected',
  statusFailed: 'Transcription failed',

  // ---- Detail actions ----
  renameTitle: 'Rename',
  titlePlaceholder: 'Meeting title',
  playRecording: 'Play Recording',
  stopPlayback: 'Stop Playback',
  delete: 'Delete',
  cancel: 'Cancel',
  save: 'Save',
  saveFailed: 'Could not save: {message}',
  noAudio: 'There is no recording to play',
  playFailed: 'Could not play: {message}',

  // ---- Transcription ----
  needCredentialsHint: 'Configure transcription credentials to turn recordings into text and generate minutes.',
  transcribe: 'Start Transcription',
  retryTranscribe: 'Retry Transcription',
  transcribingHint: 'Transcription and speaker separation are running. This usually takes a few minutes, and you can leave this page.',
  transcribeFailed: 'Transcription failed',
  submitTranscribeFailed: 'Could not submit for transcription: {message}',
  emptyTranscriptHint: "This recording finished processing, but no speech was detected - it may be too short or largely silent. If you're sure it contains conversation, you can retry transcription.",

  // ---- Speakers ----
  speakersTitle: 'Speakers (tap to rename)',
  speakerDefaultName: 'Speaker {n}',
  speakerNamePlaceholder: 'Name for Speaker {n}',

  // ---- Minutes and export ----
  generateMinutes: 'Generate Minutes',
  sendingToAi: 'Handing off to AI...',
  generateMinutesFailed: 'Could not generate minutes: {message}',
  exportTranscript: 'Export Transcript',
  // {folder} is the ACTUAL folder name reported by the backend - never hardcode it: the folder is
  // named in whichever language created it, so a hardcoded name points somewhere wrong half the time
  exported: 'Exported: {name} (see the "{folder}" folder in Explorer)',
  transcriptFallbackName: 'transcript',
  exportFailed: 'Could not export: {message}',

  // ---- Auto summary and transcript ----
  // "Auto Summary" is the vendor-supplied chapter/summary/to-do material, distinct from the
  // AI-written meeting minutes; the two names must not blur together.
  autoSummary: 'Auto Summary',
  todoLeads: 'To-do Leads',
  transcript: 'Transcript',
  expand: 'Expand',
  collapse: 'Collapse',

  // ---- Delete confirmation ----
  deleteDialogTitle: 'Delete Meeting',
  deleteDialogBody: 'This deletes the transcript and the audio file for this meeting and cannot be undone. Delete it?',
  confirmDelete: 'Delete',
  deleteFailed: 'Could not delete: {message}',
}
