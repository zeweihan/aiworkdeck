# AI WorkDeck Governance

This document describes how the AI WorkDeck project is run: who decides what, how contributors gain responsibility, and where the boundary sits between community governance and the commercial stewardship of the project.

## Structure at a glance

- **The community** develops AI WorkDeck in the open: issues, RFCs, pull requests, and a monthly community call.
- **Contributors, reviewers, and maintainers** form a merit ladder. Roles are earned through sustained work and carry real technical authority.
- **The Steward** — 北京京微资易科技有限公司 (Beijing Jingwei Ziyi Technology Co., Ltd.), together with its international affiliate 真善美承泽有限公司 (Zhen Shan Mei Grace Legacy Limited, operating workdeck.ai) — provides infrastructure, funds development, holds the trademarks, and is the sole commercial licensor. See [legal/CLA.md](legal/CLA.md), [legal/TRADEMARKS.md](legal/TRADEMARKS.md), and [legal/COMMERCIAL-LICENSE.md](legal/COMMERCIAL-LICENSE.md).

**Governance roles are technical, not economic.** Becoming a reviewer or maintainer grants review and merge authority and a real voice in the project's direction. It does not grant — and nothing in this document should be read to grant — any right to revenue, equity, trademark use, or commercial licensing, all of which rest with the Steward. Community economics (bounties, marketplace revenue sharing, the community fund) are governed by their own documents: [BOUNTIES.md](BOUNTIES.md) and [legal/MARKETPLACE-TERMS.md](legal/MARKETPLACE-TERMS.md).

## Roles

### Contributor

Anyone who has had a pull request merged, published a Skill or plugin through the marketplace, or made a sustained non-code contribution (documentation, triage, translations). No application needed. All code contributions require a signed [CLA](legal/CLA.md).

### Reviewer

Contributors with a track record — typically several merged, non-trivial pull requests over two to three months and constructive participation in reviews — may be nominated (by anyone, including themselves) to become reviewers. Maintainers confirm by lazy consensus.

Reviewers get GitHub triage permission: they label and close issues, request changes on pull requests, and their review approval is a strong signal for merging. Reviewers are listed in [MAINTAINERS.md](MAINTAINERS.md).

### Maintainer

Reviewers who have shown consistent judgment across a subsystem — knowing not just how to change the code but when not to — may be nominated by an existing maintainer. Existing maintainers confirm by consensus.

Maintainers get merge rights and are expected to: review promptly in their areas, uphold the engineering conventions in [CLAUDE.md](CLAUDE.md) and the domain docs, shepherd RFCs, and mentor contributors. Maintainers are listed in [MAINTAINERS.md](MAINTAINERS.md).

### Inactivity and stepping down

Roles are duties, not titles. A reviewer or maintainer inactive for six months moves to emeritus status (listed, honored, no permissions) and can return by resuming activity. Anyone may step down at any time.

## Decision making

- **Day-to-day changes** (bug fixes, small features, docs): one maintainer approval merges. Lazy consensus — silence is assent.
- **Substantial changes** (new subsystems, public API or plugin-SDK contract changes, data-format or protocol changes, licensing-adjacent mechanics): require an RFC (below).
- **Reserved to the Steward**: trademark and brand use, commercial licensing, legal terms (the documents in `legal/`), security-embargo decisions, marketplace listing policy, and the operation of hosted services (aiworkdeck.com / workdeck.ai). The Steward exercises final say on these regardless of RFC outcomes.
- **Disagreements** are resolved by discussion first; if maintainers cannot reach consensus, the Steward's appointed lead maintainer decides.

## RFC process

For substantial changes, open a pull request adding a document to [`rfcs/`](rfcs/) based on [`rfcs/0000-template.md`](rfcs/0000-template.md).

1. **Draft**: copy the template to `rfcs/0000-my-feature.md` (the number is assigned at merge) and open a PR.
2. **Discussion**: at least 14 days open for comment. Maintainers and affected contributors weigh in on the PR.
3. **Decision**: maintainers accept (merge) or reject (close with rationale) by consensus. Accepted RFCs get a number and become the reference for implementation PRs.
4. **Implementation** can be done by anyone, in one PR or many, referencing the RFC.

Small, reversible changes never need an RFC. When unsure, open an issue and ask.

## Community call

A monthly community call is held online (announced in [GitHub Discussions](https://github.com/zeweihan/aiworkdeck/discussions) at least one week ahead, with agenda collected in the announcement thread). Notes are posted back to the same thread. The call covers: what shipped, roadmap movement, open RFCs, and open floor.

## Roadmap

The public roadmap lives in the [README's Roadmap section](README.md#roadmap) and is refreshed as items ship. Larger directional items land as RFCs first. Feature requests: [GitHub issues](https://github.com/zeweihan/aiworkdeck/issues) or the [feature-request form](https://www.aiworkdeck.com/en/feature-request).

## Code of conduct

All spaces are covered by [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md). Reports go to hi@aiworkdeck.com and are handled by the Steward.

## Companion repositories

The official companion repositories — the website, the mobile clients, and published plugins/SDK examples — follow this same governance, the same CLA, and the same dual-licensing arrangement as this kernel repository, and each states so in its own README.
