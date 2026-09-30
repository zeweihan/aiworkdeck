# Why AI WorkDeck

**Professional AI needs a place to do the work, with the material, tools, and review process that the work requires.**

[简体中文](WHY.zh-CN.md) · [Project home](README.md) · [Build with us](README.md#build-with-us)

## The work continues after the answer

Consider a financing due diligence. A lawyer reads contracts and company records, identifies a risk, connects it to supporting material, writes the finding, and discusses it with colleagues. The finding changes as new material arrives. Its wording may eventually appear in a report, a negotiated clause, or a condition to closing.

At every step, the professional needs more than plausible text. Which document supports the conclusion? Did the proposed edit alter another obligation? What changed since the last review? Can the client open the final file in Word? Answering those questions is part of delivering the work.

AI WorkDeck is built around that continuity. Project files, document editing, agents, source references, and version history belong in the same environment. A useful agent should be able to help carry work through that environment, while leaving the professional able to inspect and decide.

## Start where the requirements are demanding

Legal work is our starting point. Sources matter, small wording changes matter, and the resulting document must remain usable outside the application. These demands give us concrete engineering problems and a clear way to judge progress.

A review workflow, for example, should leave a file whose changes can be examined and whose references can be checked. A convincing chat response is insufficient if the saved document loses its structure. This is why editor integration, document operations, source links, and version handling are core product work.

The longer-term opportunity is broader. Transaction advisers, compliance teams, and other professional services also turn fragmented material into reviewed documents. Expansion should follow workflows that share these requirements. It needs validation in each domain; adding another label to a general-purpose assistant would prove little.

## The foundation we are building

The repository already contains a LibreOffice WASM editor integration, fine-grained AI edits with tracked changes, source links with anchor-change detection, three-way document merge workflows with paragraph-level provenance, and an extension runtime. Desktop releases and Office/WPS integrations provide routes into existing document work. The [README](README.md#what-you-can-inspect-today) links these claims to code and specifications.

We believe the value of this work can compound in three ways:

- **Document reliability:** a difficult layout or revision case, once reproduced and fixed, can improve every workflow using that editor path.
- **Reusable integration:** a well-defined source connector, document operation, or plugin interface can serve more than one specialist application.
- **Professional methods:** a repeatable review procedure can become a Skill or plugin that other practitioners can inspect, adapt, and improve.

Together, these capabilities make a specialist application more practical to build. A developer connecting a research source can reuse document navigation and review; someone building a contract workflow can reuse edits, checkpoints, and history. The goal is for each contribution to make the next application easier to create.

## Why build in the open

Developers need to know what they are building on. Publishing the core makes the editor and agent integration inspectable, allows contributors to reproduce failures, and gives specialist builders a starting point beyond a blank application.

The contribution surface extends beyond core code. A developer can build an evidence connector or review pane; a practitioner can help define the expected result and supply an appropriately redacted test case. That collaboration is particularly valuable where technical correctness and professional usefulness are different questions.

The project is company-stewarded, with published [governance](GOVERNANCE.md), [maintainers](MAINTAINERS.md), and a [contributor agreement](legal/CLA.md). The core is AGPL-3.0-or-later, with commercial licensing for the steward's code. Those terms should be clear before anyone commits substantial work.

## How the business fits

The commercial model combines usage-based platform services, commercial licensing and implementation support, and a marketplace for specialist Skills and plugins. The [licensing](legal/COMMERCIAL-LICENSE.md) and [marketplace](legal/MARKETPLACE-TERMS.md) documents set out the respective terms.

The path begins with recurring work in a specific profession. A useful review workflow creates a reason to return to the workspace; supported deployment helps bring it into an organization; specialist extensions can introduce it to adjacent teams. Usage-based services support everyday work, while licensing and implementation serve organizations and builders with different deployment needs.

We judge the next stage by repeat use on real matters, reliable deliverables, manageable support costs, and useful extensions created beyond the core team. These are the operating milestones we want to discuss with investors and partners, alongside the technical work visible in this repository.

## An invitation to build something consequential

For developers, there are difficult, tangible problems here: document round-tripping, reviewable agent actions, sources that remain traceable through edits, and extensions that fit into a coherent work environment. A small improvement can become part of another professional's daily work.

For investors and partners, the opportunity is to help a focused legal workspace grow into infrastructure that specialist builders can reuse across professional document work. The product, source, extension interfaces, and commercial framework are already available to examine. The next chapter is turning that foundation into a place professionals return to, and developers choose to build on.

[Build an extension](docs/plugin-guide.md), [contribute a focused change](.github/CONTRIBUTING.md), or contact [hi@aiworkdeck.com](mailto:hi@aiworkdeck.com) about investment and partnerships.
