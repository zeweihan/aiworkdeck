# Notes for certification — template

Paste the block below into Partner Center → **Review and publish** → **Notes for certification**,
replacing `{{REVIEW_EMAIL}}` and `{{REVIEW_CODE}}` with the real reviewer test account.

**Do not commit real credentials to this repository.** Fill the placeholders in the Partner Center
form only. The maintainer keeps the reviewer account details with the other publishing secrets
(see the infrastructure handbook), not here.

Why this matters (quoting the submission guide):

> "Don't include an email address of a company employee who can provide sign-in information. Our
> reviewers **won't be able to contact you for sign-in information**. Applications that don't list
> clear instructions in the certification notes will automatically fail the submission process."

Before submitting, verify that the reviewer account:

- signs in with the **Email** form with `{{REVIEW_EMAIL}}` + `{{REVIEW_CODE}}`, where the second value
  is **fixed and handed to the reviewer in these notes** — never a real one-time code, because a
  reviewer cannot receive our email or SMS;
- never expires and is not rate-limited out after repeated sign-ins;
- has a positive Credits balance large enough for a full review pass, and is topped up before
  every resubmission (the reviewer will exercise Word, Excel and PowerPoint);
- already contains one project with a couple of sample files, so the reviewer is not blocked by an
  empty state.

---

```text
TEST ACCOUNT (no expiry, no developer involvement needed)

Email:      {{REVIEW_EMAIL}}
Code:       {{REVIEW_CODE}}   (fixed verification code for this account - nothing is emailed or texted to you)

This account has a pre-loaded Credits balance, so every AI feature below can be exercised
without any purchase. It is permanent and is topped up before each submission.

WHY AN ACCOUNT IS NEEDED
This add-in is the Office client for the AI WorkDeck service. All AI work happens in that
service, so a sign-in is required. The value proposition is stated on the task pane's first
screen before any sign-in is requested. There is no SSO and no Microsoft Entra ID dependency,
so there is no SSO fallback flow to test.

ADDITIONAL PURCHASES
AI usage consumes Credits from the signed-in AI WorkDeck account. The add-in contains no
checkout: when the balance runs out it shows a message and a link that opens the account page
at https://www.workdeck.ai in the browser. Purchasing is not required to review the add-in -
use the pre-loaded test account above.

--------------------------------------------------------------------------
STEP 1 - OPEN THE TASK PANE  (Word)
1. Open any Word document, or create one and paste two or three paragraphs of text so the
   document is not empty.
2. On the Home tab, in the "AI WorkDeck" group, choose "Open AI WorkDeck".
   The task pane opens on the right with a welcome card.
   IF THE "AI WorkDeck" GROUP IS NOT ON THE HOME TAB: choose Home > "Add-ins" and
   pick AI WorkDeck from the flyout that opens. That completes activation and the
   group then appears. (This is the standard Office fallback documented at
   https://learn.microsoft.com/office/dev/add-ins/testing/sideload-an-office-add-in-on-mac
   - "on some versions of Office, the add-in may not fully activate ... the add-in's
   buttons may not appear on the ribbon". We have seen it specifically in PowerPoint
   on Mac.) The same fallback applies to Steps 6 and 7 below.

STEP 2 - SIGN IN
3. The pane opens on a welcome card that explains what the add-in does. Choose the
   sign-in entry on that card (there is no control labelled "Sign in" in the header).
4. The sign-in form shows an Email field and a Verification code field (on this
   international site the phone-number method is not offered, so there is no tab to pick).
5. Enter {{REVIEW_EMAIL}} in the Email field and {{REVIEW_CODE}} in the Verification code
   field. Do NOT press "Get code" - the code above is fixed for this account and nothing
   is emailed; pressing it is harmless but unnecessary. If a human-verification widget
   appears, complete it once.
6. Choose "Sign in and connect". The pane returns to the chat view and an avatar
   appears at the top right. The sign-in form is deliberately not reachable again once
   signed in; use the avatar menu to sign out first if you need to sign in a second time.

STEP 3 - SELECT A PROJECT
7. Use the project dropdown at the top of the pane. The test account already has a project;
   select it. (If you prefer, choose "+ New project", type any name, and choose Create.)

STEP 4 - EDIT THE DOCUMENT  (this is the core scenario)
8. Type this into the message box and send it:

      Please make the tone of the first paragraph more formal, and add a short
      closing sentence at the end of the document.

9. Within a few seconds the pane streams a reply and the document changes.
   Expected result: the edits appear in the document as TRACKED CHANGES (coloured,
   underlined insertions and struck-through deletions). Confirm this on the Word
   Review tab - the changes can be accepted or rejected individually.
   How the tracked changes are drawn depends on your own Word setting (Review tab,
   Display for Review): balloons in the right margin, inline markup, or - in Simple
   Markup - only vertical change bars. Choose All Markup to see the edits themselves.
   The pane also shows a chip naming each document operation it performed.

STEP 5 - ADD A COMMENT
10. Send:

      Please add a comment on the first sentence asking whether the date is correct.

    Expected result: a Word comment is anchored to that sentence.

--------------------------------------------------------------------------
STEP 6 - EXCEL
11. Open Excel, and in a blank sheet enter a few rows - for example A1:B4 with
    headers "Item" and "Amount" and three numeric rows.
12. Home tab -> "AI WorkDeck" group -> "Open AI WorkDeck". You are still signed in.
    (If that group is not on the ribbon, use Home tab -> Add-ins -> AI WorkDeck.)
13. Send:

      Please add a total row under the table and make the header row bold.

    Expected result: a SUM formula is written under the amounts and the header row is
    formatted bold. (Excel has no track-changes mechanism, so the edits apply directly.)

--------------------------------------------------------------------------
STEP 7 - POWERPOINT
14. Open PowerPoint, create a presentation with a title slide plus one content slide,
    and put some text on both.
15. Home tab -> "AI WorkDeck" group -> "Open AI WorkDeck".
    (If that group is not on the ribbon, use Home tab -> Add-ins -> AI WorkDeck.)
16. Send:

      Please add a new slide at the end summarising this deck in three bullet points.

    Expected result: a new slide is appended with the bullets.
    (PowerPoint text editing requires the PowerPointApi 1.4 requirement set; please test on
    a Microsoft 365 build of PowerPoint rather than a perpetual 2019/2021 build.)

--------------------------------------------------------------------------
PLATFORMS
Tested on Word, Excel and PowerPoint for Windows, for Mac and on the web. The add-in is not
submitted for iPad, so the iOS checkbox is intentionally left unchecked.

LANGUAGES
The task pane follows the Office display language and ships English and Simplified Chinese.
To check the English interface, either run Office with an English display language or use the
globe button at the top of the pane to switch language manually.

CONTACT / SUPPORT
https://www.workdeck.ai
Privacy policy: https://www.aiworkdeck.com/en/legal/privacy
Terms of service: https://www.aiworkdeck.com/en/legal/terms
```

---

## Optional: "Additional certification info" PDF

Partner Center also accepts a PDF of reviewer instructions, which persists across submissions and
can contain screenshots. If the first submission comes back with "instructions unclear", export
this same text plus the screenshots from `screenshots/` to a PDF and attach it there instead of
lengthening the notes field.
