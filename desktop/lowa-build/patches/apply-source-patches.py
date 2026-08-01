#!/usr/bin/env python3
# Apply the two LO-core source patches for the zh-CN tooltip-CJK build (#66):
#  1. gbuild: export FS/callMain/specialHTMLTargets unconditionally (QT5 build).
#  2. vcl/qt5/QtInstance.cxx: register the runtime-injected CJK font with Qt so
#     native QToolTip / quick-help renders Chinese instead of tofu.
import sys
CORE = '/root/lowa-build/core'

def patch(path, old, new, label):
    with open(path, 'r', encoding='utf-8') as f:
        s = f.read()
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
print('ALL_PATCHES_OK')
