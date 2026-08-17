// Shared "platform services" copy: used by first-run wizard step 2 and the
// Settings -> Platform Services panel.
//
// The authoritative service list lives in the backend (GET /api/platform-services returns
// service / provider / hasLocal); this file only supplies display names and descriptions.
// A service added on the backend but missing here shows up with its raw key rather than
// disappearing -- ugly beats silently dropping a whole service.
//
// Copy red lines: no emoji anywhere, and the Chinese strings must never contain the three
// sign-out substrings (see the zh-CN file for the full reasoning).
export default {
  // ---- The seven gateway services (AI is not one of them: it ships credentials and
  //      connects from the user's own machine, bypassing the gateway) ----
  svcAsrName: 'Meeting Transcription',
  svcAsrDesc: 'Turn meeting recordings into speaker-separated text',
  svcOcrName: 'Image Text Recognition',
  svcOcrDesc: 'Pull text out of screenshots, scans, and photos',
  svcSearchName: 'Web Search',
  svcSearchDesc: 'Let the AI consult public web pages while answering',
  svcQichachaName: 'Company Registry Data',
  svcQichachaDesc: 'Look up company registration, shareholders, and affiliations',
  svcTushareName: 'Securities & Financial Data',
  svcTushareDesc: 'Look up listed-company quotes and financial indicators',
  svcPkulawName: 'Statutes & Case Law',
  svcPkulawDesc: 'Search legislation, judicial interpretations, and judgments',

  // ---- Tiers ----
  tierLabel: 'Source',
  tierPlatform: 'Platform-sourced',
  tierByok: 'Your own key',
  tierLocal: 'On this device',
  tierNeedsAccount: 'Account needed',
  tierDisabled: 'Not available yet',
  tierDisabledNote: "We have not opened this service yet — it is not an error. "
    + 'If you need it now, expand below and use your own key.',
  holdNotice: '{credits} Credits are reserved by a transcription in progress; the final amount is settled when it finishes.',
  tierPlatformNote: 'Sourced by AI Workdeck and billed as Credits from your account balance by usage, so you never open a vendor account yourself.',
  tierLocalNote: 'Runs on this device. Nothing leaves the machine and no Credits are used.',

  // ---- The three global states ----
  notConnectedTitle: 'No account connected yet',
  notConnectedBody: 'Platform-sourced services settle against your website account. Paste an account key starting with awdk_ on the account page to connect. You can also work without one by switching each service below to your own key.',
  goConnect: 'Connect an Account',
  serverModeTitle: 'This deployment does not offer platform-sourced services',
  serverModeBody: 'Team servers and cloud instances always use your own keys: external services there are shared machine-wide, so platform sourcing would bill an entire firm to one account. Enter each credential under "Use your own key (advanced)" below.',
  loadFailed: 'Could not read platform service status. Try again later.',
  switchFailed: 'Could not switch. Try again later.',
  switched: 'Switched',

  // ---- Your-own-key fold ----
  useOwnKey: 'Use your own key (advanced)',
  switchToOwnKey: 'Use your own key',
  expand: 'Expand',
  collapse: 'Collapse',
  byokPresentNote: 'This device already holds a key for this service and is using it. Switch to platform-sourced if you would rather drop your own account.',
  byokMissingNote: 'Fill in the credentials below before switching to your own key, otherwise this service will be unavailable.',
  saveHint: 'Credential changes need the "Save Settings" button below; tier switches apply immediately and need no save.',

  // ---- The AI row ----
  aiRowName: 'AI Chat & Writing',
  aiRowDesc: 'AI does not use this path: model requests go straight from this device to the provider you choose and never pass through AI Workdeck servers. Provider, model, and account balance are configured under AI Settings.',
  goAiSettings: 'Open AI Settings',

  // ---- Local tier not ready yet (flips once P3 lands local ASR) ----
  // The meeting panel's toggle has its own wording inside the component: the two contexts differ
  // (a missing dropdown option here, a disabled toggle there) and one shared sentence would read
  // wrong in one of them.
  localAsrPending: 'A local transcription engine is coming in a later release, so the "On this device" tier is not offered yet -- better than letting you pick it and discover after a two-hour recording that nothing can be transcribed.',
}
