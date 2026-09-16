# Store screenshots — spec and shot list

Screenshots are produced by the walkthrough card **dev-board#697**, not by this card. This file is
the spec they have to hit; the PNGs land in this directory.

## Hard requirements (Partner Center / AppSource)

| Item | Value | Source |
|---|---|---|
| Count | at least 1, at most 5 | [Checklist](https://learn.microsoft.com/partner-center/marketplace-offers/checklist) — "One screenshot is required" |
| Dimensions | **1366 w x 768 h pixels**, exactly | [Craft effective store images](https://learn.microsoft.com/partner-center/marketplace-offers/craft-effective-appsource-store-images) — "Provide at least one image that is 1366w x 768h pixels and no greater than 1024 KB" |
| File size | **≤ 1024 KB** each | same |
| Format | PNG | same |
| Aspect ratio | do not stretch or pinch — capture or pad to 1366x768, never scale non-uniformly | same |

A Retina capture is 2x; downscale to exactly 1366x768 with a proportional resize, or capture at a
window size that already gives that ratio and crop. Verify every file before uploading:

```bash
sips -g pixelWidth -g pixelHeight office-addin/appsource/screenshots/*.png
ls -l office-addin/appsource/screenshots/*.png     # every file under 1024 KB
```

## Content rules (these are what reviewers actually reject on)

- **No personal or client information.** Use a made-up matter — invented company names, invented
  people, invented dates. Nothing from a real file, no real firm name in the window chrome.
- **Show real content, not an empty document.** An empty Word page with an empty task pane says
  nothing.
- **Trim the unrelated UI.** Crop away the taskbar/dock, the file path, other applications. The
  docs say to use the Office UI sparingly — keep enough ribbon to make it obvious this is Word,
  and no more.
- **One point per image.** Each shot makes exactly one claim; put a short caption on it if the
  claim is not self-evident.
- **Legible at a glance.** Zoom the document to a size where body text is readable in the 1366px
  frame; magnify a crop rather than shrinking content.
- **Brand, lightly.** One AI WorkDeck mark is enough; do not paste the logo over content.

## Shot list

Five slots, one platform-coverage shot each for the three platforms plus two feature shots.
Policy 1120.3 requires the add-in to work on Office on the web and on Mac, so the reviewer should
be able to see all three platforms in the listing.

| # | Platform | Scene | The one claim it makes |
|---|---|---|---|
| 1 | **Word, Windows** | Signed-in task pane mid-conversation, document showing coloured tracked-change insertions and strikethrough deletions from the request in the pane | AI edits arrive as a redline you accept or reject — this is the hero shot, make it slot 1 |
| 2 | **Word, Mac** | Same conversation shape, plus a Word comment anchored to a sentence with the assistant's reply in the comment thread | It works comment-by-comment, and it works on Mac |
| 3 | **Word or Excel, web** | Office on the web in a browser, pane signed in, a visible edit in the sheet or document | It works in the browser too |
| 4 | **Excel** | A small table with a total row and bold header the assistant just wrote, pane showing the request | Spreadsheets are a first-class host, not a stub |
| 5 | **PowerPoint** | A deck with a slide the assistant just added, pane showing the request | Decks too |

If only three can be produced, keep 1, 3 and 4 — hero, platform breadth, second host.

## Naming

`NN-host-platform-scene.png`, e.g.

```
01-word-windows-tracked-changes.png
02-word-mac-comment-reply.png
03-word-web-edit.png
04-excel-total-row.png
05-powerpoint-new-slide.png
```

Upload order in Partner Center is the display order, so the numbers are the order.
