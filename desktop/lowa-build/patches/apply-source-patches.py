#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
# SPDX-License-Identifier: AGPL-3.0-or-later
# Apply the LO-core source patches for the zh-CN and native-review build:
#  1. gbuild: export FS/callMain/specialHTMLTargets unconditionally (QT5 build).
#  2. vcl/qt5/QtInstance.cxx: register the runtime-injected CJK font with Qt so
#     native QToolTip / quick-help renders Chinese instead of tofu.
#  3. Anchor table deletions at the table edge.
#  4-6. Expose review geometry and reserve one native page gutter (#587).
import os
import sys
CORE = os.environ.get('LOWA_CORE', '/root/lowa-build/core')

def patch(path, old, new, label):
    with open(path, 'r', encoding='utf-8') as f:
        s = f.read()
    if s.count(new) == 1:
        print(f'SKIP {label}: already applied')
        return
    n = s.count(old)
    if n != 1:
        print(f'FAIL {label}: expected exactly 1 occurrence of anchor, found {n}')
        sys.exit(1)
    s = s.replace(old, new, 1)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(s)
    print(f'OK {label}')

# ---- Patch 1: unconditional FS export ---------------------------------------
patch(
    f'{CORE}/solenv/gbuild/platform/EMSCRIPTEN_INTEL_GCC.mk',
    '"ClassHandle"$(if $(ENABLE_QT6),$(COMMA)"FS"$(COMMA)"callMain"$(COMMA)"specialHTMLTargets")]',
    '"ClassHandle"$(COMMA)"FS"$(COMMA)"callMain"$(COMMA)"specialHTMLTargets"]',
    'gbuild FS export',
)

# ---- Patch 2a: includes -----------------------------------------------------
patch(
    f'{CORE}/vcl/qt5/QtInstance.cxx',
    '#include <QtWidgets/QApplication>\n',
    ('#include <QtWidgets/QApplication>\n'
     '#include <QtGui/QFont>\n'
     '#include <QtGui/QFontDatabase>\n'
     '#include <QtCore/QDir>\n'
     '#include <QtCore/QFile>\n'),
    'QtInstance includes',
)

# ---- Patch 2b: CJK font registration at end of AfterAppInit() ----------------
cjk_block = '''
    // AI Workdeck (#66): native Qt QToolTip / quick-help bypasses VCL+fontconfig,
    // so it cannot see the runtime-injected CJK font and renders Chinese as tofu.
    // Register that font with Qt's QFontDatabase and append its family as a
    // fallback on the application + tooltip fonts (Latin metrics stay on the
    // primary family). Graceful no-op when no CJK font is present.
    {
        QStringList aCjkFamilies;
        QStringList aCandidates;
        const QString aKnownCjk(QStringLiteral("/instdir/share/fonts/truetype/AAA-CJK.ttc"));
        if (QFile::exists(aKnownCjk))
            aCandidates << aKnownCjk;
        else
        {
            QDir aFontDir(QStringLiteral("/instdir/share/fonts/truetype"));
            const QStringList aFilters{ QStringLiteral("*.ttc"), QStringLiteral("*.otf"),
                                        QStringLiteral("*.ttf") };
            for (const QString& rName : aFontDir.entryList(aFilters, QDir::Files))
                aCandidates << aFontDir.absoluteFilePath(rName);
        }
        for (const QString& rPath : aCandidates)
        {
            const int nId = QFontDatabase::addApplicationFont(rPath);
            if (nId != -1)
                aCjkFamilies << QFontDatabase::applicationFontFamilies(nId);
        }
        if (!aCjkFamilies.isEmpty())
        {
            QFont aAppFont = QApplication::font();
            QStringList aFamilies = aAppFont.families();
            if (aFamilies.isEmpty())
                aFamilies << aAppFont.family();
            for (const QString& rFam : aCjkFamilies)
                if (!aFamilies.contains(rFam))
                    aFamilies << rFam;
            aAppFont.setFamilies(aFamilies);
            QApplication::setFont(aAppFont);
            QApplication::setFont(aAppFont, "QTipLabel");
        }
    }
'''
patch(
    f'{CORE}/vcl/qt5/QtInstance.cxx',
    ': Qt::LeftToRight);\n}\n',
    ': Qt::LeftToRight);\n' + cjk_block + '}\n',
    'QtInstance AfterAppInit CJK block',
)

# ---- Patch 3: margin-redline anchor inside tables (tdf#34355 follow-up) ------
# ShowChangesInMargin paints the deleted text right-aligned at m_nX (the text
# frame's left edge). Inside a table that frame is the CELL, so the deleted
# text lands on top of the NEIGHBORING cell's content. The change bar (m_nRedX)
# already anchors at the table frame via FindTabFrame() — do the same here so
# in-table deletions render in the true page margin, left of the whole table.
patch(
    f'{CORE}/sw/source/core/text/frmpaint.cxx',
    ('    Point aTmpPos( m_nX, nY );\n'
     '    aTmpPos.AdjustY(nAsc );\n'
     '    if ( pRedlineText )\n'
     '    {\n'
     '        Size aSize = pTmpFnt->GetTextSize_( aDrawInf );\n'
     '        aTmpPos.AdjustX( -(aSize.Width()) - 200 );\n'
     '    }\n'),
    ('    Point aTmpPos( m_nX, nY );\n'
     '    aTmpPos.AdjustY(nAsc );\n'
     '    if ( pRedlineText )\n'
     '    {\n'
     '        Size aSize = pTmpFnt->GetTextSize_( aDrawInf );\n'
     '        // AI Workdeck: inside a table m_nX is the CELL\'s left edge —\n'
     '        // right-aligning the deleted text there paints it over the\n'
     '        // neighboring cell\'s content. Anchor at the table frame\'s left\n'
     '        // edge instead (the change bar m_nRedX already does this via\n'
     '        // FindTabFrame in the SwExtraPainter ctor).\n'
     '        const SwFrame* pTabAnchor = m_pTextFrame->FindTabFrame();\n'
     '        if ( pTabAnchor )\n'
     '            aTmpPos.setX( pTabAnchor->getFrameArea().Left() );\n'
     '        aTmpPos.AdjustX( -(aSize.Width()) - 200 );\n'
     '    }\n'),
    'frmpaint margin-redline table anchor',
)
# ---- Patch 4: native review geometry, without moving the editing cursor -----
# XPropertySet keeps this usable through the existing zeta bridge; no new IDL.
review_geometry = r'''
namespace
{
void lcl_WriteReviewRect(tools::JsonWriter& rJson, const SwRect& rRect)
{
    rJson.put("x", rRect.Left());
    rJson.put("y", rRect.Top());
    rJson.put("width", rRect.Width());
    rJson.put("height", rRect.Height());
}

bool lcl_ReviewAnchor(SwWrtShell& rShell, const SwPosition& rPosition,
                     SwLayoutInfo& rInfo, const sw::mark::IMark* pMark)
{
    const auto eStatus = SwPostItHelper::getLayoutInfos(rInfo, rPosition, pMark);
    return eStatus != SwPostItHelper::INVISIBLE && eStatus != SwPostItHelper::NONE
        && eStatus != SwPostItHelper::HIDDEN && rInfo.mpAnchorFrame
        && rInfo.mpAnchorFrame->getRootFrame() == rShell.GetLayout()
        && rInfo.mPosition.Height() > 0;
}

void lcl_WriteReviewAnchor(tools::JsonWriter& rJson, SwWrtShell& rShell,
                          const SwPosition& rStart, const SwPosition& rEnd,
                          const sw::mark::IMark* pMark = nullptr)
{
    SwLayoutInfo aInfo;
    // Hidden deletions retain a live collapsed boundary. Ask layout for that
    // boundary; never inspect the deleted-content storage section or guess a line.
    const bool bAvailable = lcl_ReviewAnchor(rShell, rStart, aInfo, pMark)
        || (rStart != rEnd && lcl_ReviewAnchor(rShell, rEnd, aInfo, nullptr));
    rJson.put("available", bAvailable);
    if (bAvailable)
    {
        rJson.put("page", aInfo.mnPageNumber);
        auto aAnchor = rJson.startNode("anchor");
        lcl_WriteReviewRect(rJson, aInfo.mPosition);
    }
}

OUString lcl_ReadReviewGeometry(SwView& rView)
{
    SwWrtShell& rShell = rView.GetWrtShell();
    SwPostItMgr* pMgr = rView.GetPostItMgr();
    tools::JsonWriter aJson;
    aJson.put("version", 1);
    aJson.put("unit", "twip");
    {
        auto aRevisions = aJson.startArray("revisions");
        const auto& rTable = rShell.getIDocumentRedlineAccess().GetRedlineTable();
        for (SwRedlineTable::size_type nIndex = 0; nIndex < rTable.size() && nIndex < 500; ++nIndex)
        {
            const SwRangeRedline& rRedline = *rTable[nIndex];
            auto aRevision = aJson.startStruct();
            aJson.put("index", nIndex);
            aJson.put("id", rRedline.GetId());
            lcl_WriteReviewAnchor(aJson, rShell, *rRedline.Start(), *rRedline.End());
        }
    }
    {
        auto aComments = aJson.startArray("comments");
        if (pMgr)
        {
            sal_Int32 nComments = 0;
            for (const auto& pItem : *pMgr)
            {
                if (nComments >= 500)
                    break;
                if (!pItem->UseElement(*rShell.GetLayout(), rShell.getIDocumentRedlineAccess()))
                    continue;
                ++nComments;
                const auto* pField = static_cast<const SwPostItField*>(
                    pItem->GetFormatField().GetField());
                const auto* pTextField = dynamic_cast<const SwTextAnnotationField*>(
                    pItem->GetFormatField().GetTextField());
                const auto* pMark = pTextField ? pTextField->GetAnnotationMark() : nullptr;
                const SwPosition aPosition = pItem->GetAnchorPosition();
                auto aComment = aJson.startStruct();
                aJson.put("id", pField->GetPostItId());
                aJson.put("name", pField->GetName());
                lcl_WriteReviewAnchor(aJson, rShell, aPosition, aPosition, pMark);
            }
        }
    }
    // GetCharRect can finish a pending frame layout. Read page bounds after the
    // anchors so a newly created page is included in this same response.
    {
        auto aView = aJson.startNode("view");
        lcl_WriteReviewRect(aJson, rShell.VisArea());
    }
    {
        auto aPages = aJson.startArray("pages");
        for (const SwFrame* pFrame = rShell.GetLayout()->Lower(); pFrame;
             pFrame = pFrame->GetNext())
        {
            if (!pFrame->IsPageFrame())
                continue;
            const auto* pPage = static_cast<const SwPageFrame*>(pFrame);
            auto aPage = aJson.startStruct();
            aJson.put("number", pPage->GetPhyPageNum());
            lcl_WriteReviewRect(aJson, pPage->getFrameArea());
            const auto eSide = pPage->SidebarPosition();
            aJson.put("sidebar", eSide == sw::sidebarwindows::SidebarPosition::LEFT ? "left"
                : eSide == sw::sidebarwindows::SidebarPosition::RIGHT ? "right" : "none");
            aJson.put("gutterWidth", pMgr && pMgr->HasNotes() && pMgr->ShowNotes()
                ? pMgr->GetSidebarWidth() + pMgr->GetSidebarBorderWidth() : 0);
        }
    }
    return OUString::fromUtf8(aJson.finishAndGetAsOString());
}
}

'''
patch(
    f'{CORE}/sw/source/uibase/uno/unotxvw.cxx',
    '#include <fmtanchr.hxx>\n',
    ('#include <fmtanchr.hxx>\n'
     '#include <PostItMgr.hxx>\n'
     '#include <postithelper.hxx>\n'
     '#include <docufld.hxx>\n'
     '#include <txtannotationfld.hxx>\n'
     '#include <pagefrm.hxx>\n'
     '#include <IDocumentRedlineAccess.hxx>\n'
     '#include <redline.hxx>\n'
     '#include <tools/json_writer.hxx>\n'),
    'Writer review geometry includes',
)
patch(
    f'{CORE}/sw/source/uibase/uno/unotxvw.cxx',
    'uno::Any SAL_CALL SwXTextView::getPropertyValue(\n',
    review_geometry + 'uno::Any SAL_CALL SwXTextView::getPropertyValue(\n',
    'Writer review geometry helpers',
)
patch(
    f'{CORE}/sw/source/uibase/uno/unotxvw.cxx',
    '    Any aRet;\n\n    const SfxItemPropertyMapEntry* pEntry = m_pPropSet->getPropertyMap().getByName( rPropertyName );\n',
    ('    Any aRet;\n\n'
     '    if (rPropertyName == "AwdReviewSidebarWidth")\n'
     '    {\n'
     '        if (!m_pView || !m_pView->GetPostItMgr())\n'
     '            throw RuntimeException("Writer view is unavailable");\n'
     '        aRet <<= m_pView->GetPostItMgr()->GetExternalReviewWidth();\n'
     '        return aRet;\n'
     '    }\n'
     '    if (rPropertyName == "AwdReviewGeometry")\n'
     '    {\n'
     '        if (!m_pView)\n'
     '            throw RuntimeException("Writer view is unavailable");\n'
     '        aRet <<= lcl_ReadReviewGeometry(*m_pView);\n'
     '        return aRet;\n'
     '    }\n\n'
     '    const SfxItemPropertyMapEntry* pEntry = m_pPropSet->getPropertyMap().getByName( rPropertyName );\n'),
    'Writer review geometry getter',
)
patch(
    f'{CORE}/sw/source/core/unocore/unomap.cxx',
    '                    {UNO_NAME_PAGE_COUNT,             WID_PAGE_COUNT,             cppu::UnoType<sal_Int32>::get(),   PropertyAttribute::READONLY, 0},\n',
    ('                    {"AwdReviewGeometry", 0, cppu::UnoType<OUString>::get(), PropertyAttribute::READONLY, 0},\n'
     '                    {UNO_NAME_PAGE_COUNT,             WID_PAGE_COUNT,             cppu::UnoType<sal_Int32>::get(),   PropertyAttribute::READONLY, 0},\n'),
    'Writer review geometry property',
)
# ---- Patch 5: use Writer's page gutter for the external review cards --------
# Width belongs to this view's PostItMgr only, never the document or config.
# 0 restores native comments; positive values are 96-DPI pixels at 100% zoom.
patch(
    f'{CORE}/sw/inc/PostItMgr.hxx',
    '        bool                            mbIsShowAnchor;\n',
    ('        bool                            mbIsShowAnchor;\n'
     '        sal_Int32                       mnExternalReviewWidth = 0;\n'),
    'Writer external review width state',
)
patch(
    f'{CORE}/sw/inc/PostItMgr.hxx',
    '        tools::ULong GetSidebarWidth(bool bPx = false) const;\n',
    ('        tools::ULong GetSidebarWidth(bool bPx = false) const;\n'
     '        sal_Int32 GetExternalReviewWidth() const { return mnExternalReviewWidth; }\n'
     '        void SetExternalReviewWidth(sal_Int32 nWidth);\n'),
    'Writer external review width methods',
)
patch(
    f'{CORE}/sw/inc/PostItMgr.hxx',
    '        bool IsShowAnchor() const { return mbIsShowAnchor;}\n',
    '        bool IsShowAnchor() const { return mbIsShowAnchor && mnExternalReviewWidth == 0;}\n',
    'Writer suppress native comment anchors in external gutter',
)
patch(
    f'{CORE}/sw/source/uibase/docvw/PostItMgr.cxx',
    '    const bool bShowNotes = ShowNotes();\n',
    '    const bool bShowNotes = ShowNotes() && mnExternalReviewWidth == 0;\n',
    'Writer hide native cards in external gutter',
)
patch(
    f'{CORE}/sw/source/uibase/docvw/PostItMgr.cxx',
    '                if (!aVisiblePostItList.empty() && ShowNotes())\n',
    '                if (!aVisiblePostItList.empty() && bShowNotes)\n',
    'Writer skip native card placement in external gutter',
)
patch(
    f'{CORE}/sw/source/uibase/docvw/PostItMgr.cxx',
    '                            pPostIt->GrabFocus();\n',
    '                            if (bShowNotes)\n                                pPostIt->GrabFocus();\n',
    'Writer avoid focusing hidden native cards',
)
patch(
    f'{CORE}/sw/source/uibase/docvw/PostItMgr.cxx',
    'void SwPostItMgr::Focus(const SfxBroadcaster& rBC)\n{\n',
    ('void SwPostItMgr::Focus(const SfxBroadcaster& rBC)\n{\n'
     '    if (mnExternalReviewWidth > 0)\n'
     '        return;\n'),
    'Writer keep native comment focus out of external gutter',
)
patch(
    f'{CORE}/sw/source/uibase/docvw/AnnotationWin2.cxx',
    '    const bool bShowNotes = mrMgr.ShowNotes();\n',
    '    const bool bShowNotes = mrMgr.ShowNotes() && mrMgr.GetExternalReviewWidth() == 0;\n',
    'Writer skip hidden native card sizing',
)
patch(
    f'{CORE}/sw/source/uibase/docvw/AnnotationWin2.cxx',
    'void SwAnnotationWin::ShowNote()\n{\n',
    ('void SwAnnotationWin::ShowNote()\n{\n'
     '    if (mrMgr.GetExternalReviewWidth() > 0)\n'
     '    {\n'
     '        HideNote();\n'
     '        return;\n'
     '    }\n'),
    'Writer suppress native card display in external gutter',
)
patch(
    f'{CORE}/sw/source/uibase/docvw/PostItMgr.cxx',
    '    return mpWrtShell->GetViewOptions()->IsPostIts();\n',
    '    return mnExternalReviewWidth > 0 || mpWrtShell->GetViewOptions()->IsPostIts();\n',
    'Writer external review gutter visibility',
)
patch(
    f'{CORE}/sw/source/uibase/docvw/PostItMgr.cxx',
    '    return !mvPostItFields.empty();\n',
    '    return mnExternalReviewWidth > 0 || !mvPostItFields.empty();\n',
    'Writer reserve gutter in revision-only documents',
)
patch(
    f'{CORE}/sw/source/uibase/docvw/PostItMgr.cxx',
    'tools::ULong SwPostItMgr::GetSidebarWidth(bool bPx) const\n',
    ('''void SwPostItMgr::SetExternalReviewWidth(sal_Int32 nWidth)
{
    if (nWidth > 0)
        nWidth = std::max<sal_Int32>(180, nWidth);
    if (mnExternalReviewWidth == nWidth)
        return;
    if (mpActivePostIt)
    {
        mpActivePostIt->UpdateData();
        mpActivePostIt->GrabFocusToDocument();
        SetActiveSidebarWin(nullptr);
    }
    mnExternalReviewWidth = nWidth;
    PrepareView(true);
    CalcRects();
    LayoutPostIts();
    mpEditWin->Invalidate();
}

''' + 'tools::ULong SwPostItMgr::GetSidebarWidth(bool bPx) const\n'),
    'Writer external review width setter',
)
patch(
    f'{CORE}/sw/source/uibase/docvw/PostItMgr.cxx',
    ('    bool bEnableMapMode = !mpWrtShell->GetOut()->IsMapModeEnabled();\n'
     '    sal_uInt16 nZoom = mpWrtShell->GetViewOptions()->GetZoom();\n'),
    ('''    bool bEnableMapMode = !mpWrtShell->GetOut()->IsMapModeEnabled();
    if (mnExternalReviewWidth > 0)
    {
        // Store a document-space width, not physical device pixels. Otherwise
        // a Retina canvas halves the CSS width when native pixels are converted.
        const tools::Long nWidth = static_cast<tools::Long>(mnExternalReviewWidth) * 15;
        if (!bPx)
            return nWidth;
        if (bEnableMapMode)
            mpWrtShell->GetOut()->EnableMapMode();
        const tools::Long nPixels = mpWrtShell->GetOut()->LogicToPixel(Size(nWidth, 0)).Width();
        if (bEnableMapMode)
            mpWrtShell->GetOut()->EnableMapMode(false);
        return nPixels;
    }
    sal_uInt16 nZoom = mpWrtShell->GetViewOptions()->GetZoom();
'''),
    'Writer external review width scaling',
)
patch(
    f'{CORE}/sw/source/uibase/uno/unotxvw.cxx',
    '    else\n    {\n        switch (pEntry->nWID)\n',
    ('    else\n    {\n'
     '        if (rPropertyName == "AwdReviewSidebarWidth")\n'
     '        {\n'
     '            sal_Int32 nWidth = 0;\n'
     '            if (!(rValue >>= nWidth) || nWidth < 0)\n'
     '                throw IllegalArgumentException("Review sidebar width must be non-negative", nullptr, 1);\n'
     '            if (!m_pView || !m_pView->GetPostItMgr())\n'
     '                throw RuntimeException("Writer view is unavailable");\n'
     '            m_pView->GetPostItMgr()->SetExternalReviewWidth(nWidth);\n'
     '            return;\n'
     '        }\n'
     '        switch (pEntry->nWID)\n'),
    'Writer external review width UNO setter',
)
patch(
    f'{CORE}/sw/source/core/unocore/unomap.cxx',
    '                    {"AwdReviewGeometry", 0, cppu::UnoType<OUString>::get(), PropertyAttribute::READONLY, 0},\n',
    ('                    {"AwdReviewSidebarWidth", 0, cppu::UnoType<sal_Int32>::get(), PROPERTY_NONE, 0},\n'
     '                    {"AwdReviewGeometry", 0, cppu::UnoType<OUString>::get(), PropertyAttribute::READONLY, 0},\n'),
    'Writer external review width property',
)
# ---- Patch 6: keep native insertion marks, replace only margin deletion text -
patch(
    f'{CORE}/sw/source/core/text/frmpaint.cxx',
    '#include <viewopt.hxx>\n',
    '#include <viewopt.hxx>\n#include <PostItMgr.hxx>\n',
    'Writer external review deletion paint include',
)
patch(
    f'{CORE}/sw/source/core/text/frmpaint.cxx',
    ('void SwExtraPainter::PaintExtra( SwTwips nY, tools::Long nAsc, tools::Long nMax, bool bRed, const OUString* pRedlineText )\n'
     '{\n'),
    ('void SwExtraPainter::PaintExtra( SwTwips nY, tools::Long nAsc, tools::Long nMax, bool bRed, const OUString* pRedlineText )\n'
     '{\n'
     '    const SwPostItMgr* pMgr = m_pSh->GetPostItMgr();\n'
     '    if (pRedlineText && pMgr && pMgr->GetExternalReviewWidth() > 0)\n'
     '    {\n'
     '        // The shared page gutter owns deleted text; retain the native change bar.\n'
     '        const tools::Long nDiff = m_bGoLeft ? m_nRedX - m_nX : m_nX - m_nRedX;\n'
     '        if (bRed && nDiff > REDLINE_MINDIST)\n'
     '            PaintRedline(nY, nMax);\n'
     '        return;\n'
     '    }\n'),
    'Writer external review suppress legacy deletion text only',
)
print('ALL_PATCHES_OK')
