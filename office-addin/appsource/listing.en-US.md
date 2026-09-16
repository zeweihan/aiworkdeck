# Marketplace listing — en-US

Paste each block into the matching field on Partner Center → **Marketplace listings** → `English (United States)`.
Field limits are quoted from
[Create effective listings](https://learn.microsoft.com/partner-center/marketplace-offers/create-effective-office-store-listings)
("Apply guidelines for name and description length").

---

## Name

> Maximum 50 characters, recommended 30. Must be the same or very similar to the manifest `DisplayName`.

```
AI WorkDeck
```

11 characters. Identical to `manifest.xml` → `<DisplayName>` and to its `en-US` Override.

Not "AI WorkDeck for Word" — one offer covers Word, Excel and PowerPoint, and the docs say not to
put the Microsoft product name in the listing name.

---

## Summary (search results summary / short description)

> Maximum 100 characters, recommended 70, key message in the first 30.

```
Redline, draft and review documents with AI inside Word, Excel and PowerPoint.
```

78 characters. First 30 characters read "Redline, draft and review docu" — the verbs come first,
the brand name is not repeated (the docs call repeating the name a "don't").

---

## Description (long description)

> The length table gives **10,000 characters** maximum; the prose on the same page says
> "The maximum length for descriptions is 4,000 characters". We stay under 4,000 to satisfy both.
> Recommended body length is 300–500 words, key message in the first 300 words. HTML is supported —
> compose it in an HTML editor first, Partner Center has no preview.
>
> Policy 1100.1 and the pre-submission checklist require the description itself to disclose that
> the add-in needs a separate account and paid usage, with links to acquire them. The
> "Requirements" section below is that disclosure — do not trim it.

```html
<p>AI WorkDeck puts an AI drafting and review assistant in the Word, Excel and PowerPoint task pane. It reads the document you have open, edits it with track changes turned on, replies in comment threads, and answers questions using the files in your matter — so you review a redline instead of copying text into a chat window and pasting the result back.</p>

<p>Built for lawyers and in-house legal teams who spend the day inside Office.</p>

<h3>What you can do</h3>
<ul>
  <li><strong>Redline in place.</strong> Ask for a change in plain language. Edits land in the open document as tracked changes you can accept or reject one by one, exactly as if a colleague had made them.</li>
  <li><strong>Work through the comments.</strong> The assistant reads every comment in a document, makes the edit each one asks for, and replies in the thread explaining what it changed.</li>
  <li><strong>Proofread a long document section by section.</strong> Typos, inconsistent defined terms and awkward phrasing, without changing the meaning — with a progress indicator so you can see how far it has read.</li>
  <li><strong>Ground answers in your own files.</strong> Attach documents from your AI WorkDeck project, or upload a local file, and ask questions across them.</li>
  <li><strong>Excel and PowerPoint too.</strong> Read and write ranges, formulas, number formats, filters, charts and pivot tables in a workbook; edit slide text, tables, shapes and slide order in a deck.</li>
</ul>

<h3>Before you install — account and paid usage</h3>
<p>This add-in is the Office client for the AI WorkDeck service; it does not work on its own.</p>
<ul>
  <li><strong>An AI WorkDeck account is required.</strong> You sign in inside the task pane with your email address or mobile number and a one-time code. Accounts are free to create at <a href="https://www.workdeck.ai">https://www.workdeck.ai</a>.</li>
  <li><strong>AI usage requires an additional purchase.</strong> Every AI request draws Credits from your AI WorkDeck account balance, and AI features stop working when the balance runs out. Credits are bought on the account page at <a href="https://www.workdeck.ai">https://www.workdeck.ai</a>. The add-in itself is free to install and contains no checkout — it shows your remaining balance in the account menu and links out to the website when you need to top up.</li>
</ul>
<p>Terms of service: <a href="https://www.aiworkdeck.com/en/legal/terms">https://www.aiworkdeck.com/en/legal/terms</a>. Privacy policy: <a href="https://www.aiworkdeck.com/en/legal/privacy">https://www.aiworkdeck.com/en/legal/privacy</a>.</p>

<h3>Supported applications</h3>
<p>Word, Excel and PowerPoint — on Windows, on Mac, and on the web. The interface is available in English and Simplified Chinese and follows your Office display language.</p>

<h3>How your document is handled</h3>
<p>The text of the document you have open, and any files you attach, are sent to the AI WorkDeck service so it can answer and make the edits you ask for. Nothing is sent until you send a message. Edits are written back into your document as tracked changes, so you always see and control what changed. See the privacy policy above for what is stored and for how long.</p>

<h3>Support</h3>
<p><a href="https://www.workdeck.ai">https://www.workdeck.ai</a></p>
```

---

## Search keywords

> Partner Center accepts a small number of free-text keywords. Keep them to terms a customer
> would actually type; do not stuff competitor or Microsoft product names.

```
legal AI
contract review
redline
track changes
document drafting
```

---

## Categories (pick at least one, at most three)

Recommended, in order of preference:

1. **Productivity**
2. **Compliance & Legal** (list it first if Partner Center offers it — the audience is legal)
3. **Content Management / Document Management**

The category list in Partner Center is the authority; pick the closest live equivalents and keep
the total at three or fewer.

---

## Industries (optional, at most two)

1. **Professional Services** — law firms are the primary audience.
2. **Financial Services** — in-house legal and compliance teams.

Leave both blank rather than guessing if neither fits the live list; the docs say not to pick an
industry when the product is not industry specific, and picking a wrong one hurts discovery.

---

## Video (optional)

None for the first submission.
