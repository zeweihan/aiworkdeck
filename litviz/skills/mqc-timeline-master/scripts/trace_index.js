// 溯源索引：说明图上每一个元素分别溯源到材料的哪一处。
//
// 作者定过：不做「事实与证据对照表」，那会破坏这个 skill 的纯粹（它只画时间轴）；
// 改成一份溯源索引，只说明图上**已经画出来**的元素各自出自材料何处，让用户知道图不是瞎编的。
// 所以这份文件里没有任何评价、没有证据是否成立的判断，只有对应关系。
//
// 五列三线表：图上编号 / 卡片上的文字 / 出自材料 / 定位 / 原文摘录。
// 三线表 = 只有顶线、表头下线、底线，没有竖线与格线，法律文书里的表格惯例。
// 字体：中文宋体，英文与数字 Times New Roman（作者对正式文稿的固定要求）。
const fs = require("fs");
const {
  Document, Packer, Paragraph, TextRun, Table, TableRow, TableCell,
  WidthType, BorderStyle, AlignmentType, VerticalAlign,
  Footer, PageNumber,
} = require("docx");

const data = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
const out = process.argv[3] || "溯源索引.docx";

const CN = "宋体";
const EN = "Times New Roman";
// 三线表的三条主线用 6（0.75pt），格线用 2（0.25pt）—— 有线但比主线细，
// 作者的要求是「其他位置要画线，线可以细一点」。
const NONE = { style: BorderStyle.NONE, size: 0, color: "FFFFFF" };
const LINE = { style: BorderStyle.SINGLE, size: 6, color: "000000" };
const HAIR = { style: BorderStyle.SINGLE, size: 2, color: "808080" };

// 直角引号（「」『』）**一律换成中文引号**（作者对正式文稿的要求）。
// 收在 run() 这一个出口做：凡是进文档的文字都经过它，所以不会有"这一处忘了换"。
// 逐处改过一版，尾注那一句就漏了 —— 又是「每处都要记得做一次」的事。
function toCurly(s) {
  return String(s == null ? "" : s)
    .replace(/\u300c/g, "\u201c").replace(/\u300d/g, "\u201d")
    .replace(/\u300e/g, "\u201c").replace(/\u300f/g, "\u201d");
}

function run(text, opts) {
  opts = opts || {};
  text = toCurly(text);
  // 中文走宋体、西文与数字走新罗马：靠 font 的 ascii / eastAsia 分别指定
  return new TextRun({
    text: String(text == null ? "" : text),
    font: { ascii: EN, hAnsi: EN, eastAsia: CN },
    size: opts.size || 21,          // 半磅：21 = 10.5pt（小四）
    bold: !!opts.bold,
  });
}

function cell(text, width, opts) {
  opts = opts || {};
  return new TableCell({
    width: { size: width, type: WidthType.DXA },
    borders: {
      top: opts.top || HAIR, bottom: opts.bottom || HAIR,
      left: opts.left || HAIR, right: opts.right || HAIR,
    },
    margins: { top: 60, bottom: 60, left: 80, right: 80 },
    // 每一格都**纵向居中**。默认是上对齐，于是同一行里短的那格贴顶、长的那格
    // 占满，一行读起来是错位的。
    verticalAlign: VerticalAlign.CENTER,
    children: [new Paragraph({
      // 关掉 Word 的中西文自动间距。
      // 源文本里的空格我已经在管道里挤掉了（JSON 里是「2022年11月8日」），
      // 而渲出来仍有空隙 —— 那是 Word 自己加的：OOXML 的 autoSpaceDE / autoSpaceDN
      // 默认开启，会在中西文之间插入间距。作者要求前后不许有空格，所以两个都关掉。
      // 这类「看起来是数据问题、实际是渲染器默认行为」的坑，只有把产物渲出来看才发现。
      autoSpaceEastAsianText: false,
      autoSpaceEastAsianNumbers: false,
      // 「图上编号」与「定位」两列水平居中；其余三列**两端对齐**（Word 的
      // CTRL+J），中文正文靠两端对齐才有整齐的左右边。
      alignment: opts.center ? AlignmentType.CENTER : AlignmentType.JUSTIFIED,
      children: [run(text, opts)],
    })],
  });
}

// 列宽合计等于表宽（DXA，1440 = 1 英寸）。A4 纵向去掉页边距约 9000 DXA。
// 第一列 900 DXA 放不下「图上编号」四个字（被挤成两行），加到 1150；
// 从最宽的「原文摘录」那一列匀出来，总宽不变。列宽合计必须等于表宽。
const W = [1150, 2400, 1500, 1200, 2750];
const total = W.reduce(function (a, b) { return a + b; }, 0);

const header = new TableRow({
  tableHeader: true,
  children: ["图上编号", "卡片上的文字", "出自材料", "定位", "原文摘录"]
    .map(function (t, i) {
      return cell(t, W[i], { bold: true, center: true, top: LINE, bottom: LINE,
                             left: i === 0 ? NONE : HAIR,
                             right: i === 4 ? NONE : HAIR });
    }),
});

const rows = data.rows.map(function (r, idx) {
  return new TableRow({
    children: [r.no, r.head, r.file, r.locator, r.quote].map(function (t, i) {
      return cell(t, W[i], {
        center: i === 0 || i === 3,
        bottom: idx === data.rows.length - 1 ? LINE : HAIR,
        left: i === 0 ? NONE : HAIR,
        right: i === 4 ? NONE : HAIR,
      });
    }),
  });
});

const doc = new Document({
  sections: [{
    properties: { page: { margin: { top: 1440, bottom: 1440, left: 1440, right: 1440 } } },
    footers: {
      default: new Footer({
        children: [new Paragraph({
          alignment: AlignmentType.CENTER,     // 正下方中央
          children: [new TextRun({
            // 页码只有数字，所以全部交给新罗马；字号比正文小一档（18 半磅 = 9pt）
            children: [PageNumber.CURRENT],
            font: { ascii: EN, hAnsi: EN, eastAsia: EN },
            size: 18,
          })],
        })],
      }),
    },
    children: [
      new Paragraph({
        alignment: AlignmentType.CENTER,
        // 标题下方留一行间距（段后 240 缇 = 12pt ≈ 一行），仍用间距不用空段落
        spacing: { after: 240 },
        children: [run(data.title || "溯源索引", { bold: true, size: 32 })],
      }),
      new Paragraph({
        // 表格上方的间隔用**段后间距一行**，不用空段落。空段落在 Word 里是一个真的
        // 段落，改行距、加页眉、转 PDF 时都会各自跑偏；间距是段落属性，跟着段落走。
        spacing: { after: 240 },      // 240 缇 = 12pt ≈ 一行
        children: [run(data.note ||
          "本表说明图上各元素分别出自材料何处，仅列对应关系，不含任何评价。")],
      }),
      new Table({
        columnWidths: W,
        width: { size: total, type: WidthType.DXA },
        rows: [header].concat(rows),
      }),
      new Paragraph({
        // 表格下方同理：用**段前间距一行**，不用空段落
        spacing: { before: 240 },
        children: [run(data.foot ||
          "\u201c定位\u201d为材料规范化后的句号，可据此回到原文逐字核对。")],
      }),
    ],
  }],
});

Packer.toBuffer(doc).then(function (buf) {
  fs.writeFileSync(out, buf);
  console.log("写出", out, buf.length, "字节");
});
