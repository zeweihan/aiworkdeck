// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.version.merge;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 三方合并读取器/分析器的夹具：全部用 POI 在测试里现生成，仓库里不放二进制 docx/xlsx/pptx。
 *
 * <p>docx 夹具用「段落 + 表格」的混合序，因为单元序列（正文段落与表格单元混在一条序列里）
 * 正是 {@link ThreeWayAnalyzer} 判「相邻即冲突」的地基。
 */
final class MergeFixtures {

    private MergeFixtures() {
    }

    /** 只有正文段落的 docx。 */
    static byte[] docx(String... paragraphs) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : paragraphs) {
                XWPFParagraph p = doc.createParagraph();
                p.createRun().setText(text);
            }
            doc.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 段落 + 一张表格 + 段落：body 元素序为 before…、表格、after…。
     *
     * @param table 行优先的单元文字，{@code table[r][c]}
     */
    static byte[] docxWithTable(List<String> before, String[][] table, List<String> after) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : before) {
                doc.createParagraph().createRun().setText(text);
            }
            XWPFTable t = doc.createTable(table.length, table[0].length);
            for (int r = 0; r < table.length; r++) {
                for (int c = 0; c < table[r].length; c++) {
                    t.getRow(r).getCell(c).setText(table[r][c]);
                }
            }
            for (String text : after) {
                doc.createParagraph().createRun().setText(text);
            }
            doc.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 单张工作表；值以 {@code =} 开头写成公式，其余写成字符串。 */
    static byte[] xlsx(String sheetName, Map<String, String> cells) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(sheetName);
            for (Map.Entry<String, String> e : cells.entrySet()) {
                org.apache.poi.ss.util.CellReference ref = new org.apache.poi.ss.util.CellReference(e.getKey());
                Row row = sheet.getRow(ref.getRow());
                if (row == null) {
                    row = sheet.createRow(ref.getRow());
                }
                Cell cell = row.createCell(ref.getCol());
                if (e.getValue().startsWith("=")) {
                    cell.setCellFormula(e.getValue().substring(1));
                } else {
                    cell.setCellValue(e.getValue());
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, String> cells(String... keyThenValue) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyThenValue.length; i += 2) {
            map.put(keyThenValue[i], keyThenValue[i + 1]);
        }
        return map;
    }

    /**
     * 每页一个标题 + 一段正文；{@code titles} 与 {@code bodies} 等长。
     * 用带标题占位符的版式建页，这样 {@code XSLFSlide.getTitle()} 有值。
     */
    static byte[] pptx(List<String> titles, List<String> bodies) {
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSLFSlideLayout layout = show.getSlideMasters().get(0).getLayout(SlideLayout.TITLE_AND_CONTENT);
            for (int i = 0; i < titles.size(); i++) {
                XSLFSlide slide = show.createSlide(layout);
                XSLFTextShape[] placeholders = slide.getPlaceholders();
                placeholders[0].setText(titles.get(i));
                placeholders[1].setText(bodies.get(i));
            }
            show.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 把 pptx 的页按给定的 0 基下标顺序重排，sldId 随页保持不变。 */
    static byte[] reorder(byte[] pptx, int... order) {
        try (XMLSlideShow show = new XMLSlideShow(new java.io.ByteArrayInputStream(pptx));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            List<XSLFSlide> slides = show.getSlides();
            XSLFSlide[] snapshot = slides.toArray(new XSLFSlide[0]);
            for (int target = 0; target < order.length; target++) {
                show.setSlideOrder(snapshot[order[target]], target);
            }
            show.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
