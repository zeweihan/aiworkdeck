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
print('ALL_PATCHES_OK')
