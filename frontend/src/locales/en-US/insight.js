// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// References panel (InsightPane.vue, dev-board#181/#182).
export default {
  title: 'References',

  noDoc: 'No document open',
  parse: 'Analyze',
  reparse: 'Re-analyze',
  running: 'Analyzing…',
  done: 'Analysis complete',
  failed: 'Analysis failed',
  loading: 'Loading…',
  loadingDetail: 'Fetching lookup details…',
  noDetail: 'No lookup details for this item',
  loadFailed: 'Could not load the analysis result',
  parseFailed: 'Could not start the analysis',
  detailFailed: 'Could not load the lookup details',
  refreshFailed: 'Could not re-run the lookup',
  retry: 'Retry',

  // Next step for configuration failures (dev-board#458). Retrying cannot fix any of these.
  goConnectAccount: 'Connect account',
  goRecharge: 'Add credits',
  hint: {
    NO_CREDENTIAL: 'This lookup channel needs a credential configured on the server by your administrator.',
  },

  tab: {
    retrieval: 'Lookups',
    checks: 'Consistency',
  },

  kind: {
    COMPANY: 'Companies',
    LAW: 'Statutes',
    CASE: 'Cases',
    // Fourth entity kind: a file of this project referenced in the text (dev-board#541)
    DOC: 'Documents',
  },
  // Badge for a single entity (hover card / entity tab) — the group headings above are plural.
  entityKind: {
    COMPANY: 'Company',
    LAW: 'Statute',
    CASE: 'Case',
    DOC: 'Document',
  },
  openInNewTab: 'Open in a new tab',
  openDocFile: 'Open file',
  docNotFound: 'No such file in this project',
  docMissing: 'That file is no longer in this project',
  source: {
    projectFile: 'Project file',
    qichacha: 'Qichacha',
  },

  mentions: '{count} mention(s)',
  mentionsTitle: 'Mentions in this document',
  shareholders: 'Shareholders',
  moreCandidates: 'Other candidates',
  caseSection: {
    ascertain: 'Facts found',
    reason: 'Reasoning',
    result: 'Judgment',
    gist: 'Key holding',
    fullText: 'Full judgment',
  },

  authoritative: 'Authoritative text (Pkulaw)',
  implementDate: 'In force from {date}',
  recognition: 'Case number recognition',
  openInPkulaw: 'Open in Pkulaw',

  citation: {
    citedText: 'Cited article',
    candidates: 'Articles matched by content',
    article: 'Article {n}',
  },

  severity: {
    warn: 'Questionable',
    error: 'Error',
  },
  unifyTo: 'Set all to {value}',
  cannotFix: 'Cannot fix automatically: {reason}',
  fixNotUnique: 'Could not locate a unique match — please edit manually.',
  fixPartial: 'Fixed {done}; {failed} had no unique match — please edit those manually.',
  fixed: 'Fixed',
  fixedHint: 'Press Cmd+Z to undo; re-analyze to refresh the findings.',
  noEditor: 'Activate a document editor window first',

  empty: {
    noRun: 'This document has not been analyzed yet',
    noRunHint: 'Click Analyze: the AI reads the whole document, extracts companies, statutes and cases, looks them up in external sources, and checks the document against itself.',
    noEntity: 'No lookupable entities found',
    noEntityHint: 'The document mentions no company name, statute article or case number.',
    noFinding: 'No inconsistencies found',
    noFindingHint: 'Quantity statements and unified social credit codes all check out.',
  },
}
