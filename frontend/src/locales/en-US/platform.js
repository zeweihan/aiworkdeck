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
  tierPlatformNote: 'Sourced by AI WorkDeck and billed as Credits from your account balance by usage, so you never open a vendor account yourself.',
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

  // ---- This month's spend (the number next to each service) ----
  // Unknown always renders as a dash, never as 0 -- same rule as ai-usage. A 0 states a
  // fact we do not actually know: a user who just ran a two-hour transcription would read
  // it as "the charge never landed".
  usageMonthLabel: 'This month',
  usageUnknown: '—',
  usageCredits: '{credits} Credits',
  usageTotal: '{credits} Credits this month ({month})',
  usageUnavailable: "This month's spend is temporarily unreadable; that does not mean it is zero.",

  // ---- Spending thresholds ----
  budgetTitle: 'Spending alerts',
  budgetSubtitle: 'Set either to 0 to turn it off. These decide when we ask you a question, not a hard cap.',
  budgetLowBalanceLabel: 'Warn when balance drops below',
  budgetLowBalanceNote: 'Shows a warning on this panel once the balance falls below this amount, so a transcription does not run out halfway.',
  budgetUnit: 'Credits',
  budgetSave: 'Save thresholds',
  budgetSaved: 'Saved',
  budgetSaveFailed: 'Could not save. Try again later.',
  budgetInvalid: 'Invalid amount. Use 0 to turn the threshold off.',
  lowBalanceNotice: 'Account balance is {credits} Credits, below the {threshold} Credits you set.',

  // ---- The AI row ----
  aiRowName: 'AI Chat & Writing',
  aiRowDesc: 'AI does not use this path: model requests go straight from this device to the provider you choose and never pass through AI WorkDeck servers. Provider, model, and account balance are configured under AI Settings.',
  goAiSettings: 'Open AI Settings',

  // ---- Local tier not ready on this machine yet ----
  // The meeting panel's toggle has its own wording inside the component: the two contexts differ
  // (a missing dropdown option here, a toggle that downloads the model there) and one shared
  // sentence would read wrong in one of them. The readiness test itself is shared
  // (the probe in config/platformServices.js); only the wording differs.
  localAsrPending: 'The on-device transcription model has not been downloaded, so the "On this device" tier is not offered yet. Turn on "Keep recordings on this device" in the meeting panel to download it there (about 1.5GB), or download it under Components.',
}
