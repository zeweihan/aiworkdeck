// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.openxmlformats.schemas.presentationml.x2006.main.CTSlideIdList;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 pptx 拆成页。
 *
 * <p>页的身份取 {@code presentation.xml} 里的 {@code <p:sldId id>}——调了页序它跟着页走，
 * 所以「同事把第 3 页挪到第 1 页」不会被误判成「删了一页又加了一页」。
 * 页文本按形状序拼接，是页内容有没有变的判据。
 */
public final class PptxSlideReader {

    private PptxSlideReader() {
    }

    public static List<Slide> read(byte[] pptx) throws IOException {
        List<Slide> slides = new ArrayList<>();
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(pptx))) {
            List<XSLFSlide> xslfSlides = show.getSlides();
            CTSlideIdList idList = show.getCTPresentation().getSldIdLst();
            for (int i = 0; i < xslfSlides.size(); i++) {
                XSLFSlide slide = xslfSlides.get(i);
                String sldId = idList != null && i < idList.sizeOfSldIdArray()
                        ? String.valueOf(idList.getSldIdArray(i).getId())
                        : "#" + (i + 1);
                String title = slide.getTitle();
                slides.add(new Slide(sldId, i + 1, title == null ? "" : title, textOf(slide)));
            }
        }
        return slides;
    }

    private static String textOf(XSLFSlide slide) {
        StringBuilder sb = new StringBuilder();
        for (XSLFShape shape : slide.getShapes()) {
            if (shape instanceof XSLFTextShape textShape) {
                String text = textShape.getText();
                if (text == null || text.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(text);
            }
        }
        return sb.toString();
    }
}
