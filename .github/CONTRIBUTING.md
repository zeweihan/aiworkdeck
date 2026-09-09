# Contributing to AI WorkDeck

Thank you for your interest in contributing to AI WorkDeck! We welcome contributions from the community to help improve the kernel.

New here? The project's governance — roles, the contributor → reviewer → maintainer ladder, and the RFC process for substantial changes — is described in [GOVERNANCE.md](../GOVERNANCE.md). Current maintainers are listed in [MAINTAINERS.md](../MAINTAINERS.md). Some issues carry cash bounties — see [BOUNTIES.md](../BOUNTIES.md).

## Licensing & CLA

All code contributions require a signed [Contributor License Agreement (CLA)](../legal/CLA.md). Briefly:

1. You assert that you own the code you are submitting (DCO), and you grant a patent license for it.
2. You grant 北京京微资易科技有限公司 (the project's Steward) the right to distribute your code under both the **AGPLv3** (for the community) and a **commercial license** (for enterprise customers).
3. You keep the copyright in your work — the CLA is a license, not an assignment.

Signing is automatic in the PR flow: on your first pull request, the CLA Assistant bot comments with a link, and you sign by replying with the sentence it requests. One signature covers all your past and future contributions. **Unsigned PRs are not merged.**

*This rights grant is essential for the project's long-term sustainability and dual-licensing model.*

## How to Contribute

1. **Fork the repository** on GitHub.
2. **Create a new branch** for your feature or bugfix.
3. **Commit your changes** with clear, descriptive commit messages.
4. **Push to your branch** and submit a **Pull Request (PR)**.

For substantial changes (new subsystems, public API or plugin-SDK contract changes), open an [RFC](../rfcs/0000-template.md) first — see [GOVERNANCE.md](../GOVERNANCE.md#rfc-process).

## Code Style

- Please follow the existing code style in the project.
- Ensure all tests pass before submitting.
- Add new tests for new features where appropriate.

## Reporting Issues

- Use the GitHub Issues tab to report bugs.
- Please check existing issues to avoid duplicates.
- Provide reproduction steps and environment details.

## Pull Request Process

1. We will review your PR as soon as possible.
2. We may ask for changes or clarifications.
3. Once approved, we will merge your contribution.

## Provenance and license notices

AI WorkDeck is AGPL-3.0-or-later. We welcome forks, modifications and redistribution; the one thing we ask is that authorship and license information is preserved. These rules keep that information intact and machine-readable (full rationale: `docs/superpowers/specs/2026-09-09-provenance-license-compliance-design.md`).

1. **Every first-party source file carries the SPDX two-line header** (`SPDX-FileCopyrightText` + `SPDX-License-Identifier: AGPL-3.0-or-later`) at the top: before `package` in Java, before `<template>` in Vue, after the shebang in shell. CI runs `scripts/check-spdx.mjs` on files added in a PR and fails if the header is missing.
2. **Third-party code keeps its upstream header untouched** and lives beside an `UPSTREAM.md` naming the source URL, version and license. Never relicense vendored code by editing its header.
3. **Re-check a file's license header when its content outgrows its origin.** A file that started as a 500-line upstream sample and became 6,000 lines of our own code must say so (`AGPL-3.0-or-later AND MIT`, with a note on which parts are which).
4. **Do not "unify" legacy identifiers.** The Java package root `com.checkba.*`, the `--awd-` CSS token prefix, the `checkba://` URI scheme, the `X-AWD-*` commit trailers and the `awd: 1` plugin envelope are public contracts that third-party plugins and users' project repositories depend on. Renaming them breaks compatibility; contract tests lock the literals.
5. **Strings that end up in user artifacts** (documents, Git history, wire protocols) are declared as named constants with a literal-asserting test, so a refactor cannot silently change them.
6. **Every interactive surface shows the legal notice** required by AGPL section 0 (version, copyright line, no-warranty statement, license and source links, trademark note): the workbench settings page, the Office and WPS task panes, the desktop About panel. Backend responses carry `X-Source-Code`. Do not turn the notice into forced logo display (badgeware); branding is protected by trademark, not copyright.
7. **Comments explain why, in Chinese, with concrete coordinates** (dates, PR and dev-board numbers, the failure that was hit). This is how the codebase is already written; do not translate or strip such comments for "cleanliness".
8. **Never add covert behavior.** No phone-home, no build-origin probes, no unlisted outbound requests, no deliberate misbehavior as a "trap". Telemetry stays whitelisted and user-switchable as documented in `legal/PRIVACY.md`. Obfuscation, magic-number watermarks and comment ciphers are pointless for an open-source project and will be rejected.

## Code of Conduct

Please be respectful and professional in all interactions ([CODE_OF_CONDUCT.md](../CODE_OF_CONDUCT.md)). We want to keep this community welcoming and helpful.
