---
name: 作者名片
skill: mqc-timeline-master
project: 新诉讼可视化 · New Litigation Visualization
---

<h1 align="center">新诉讼可视化 · New Litigation Visualization</h1>
<p align="center"><b>把法律画出来 · Make the Law Visible</b></p>

---

本模块（`mqc-timeline-master` · 时间轴大师）是 **「新诉讼可视化 / New Litigation
Visualization」** 开源项目的第二个模块，专责把律师手上的原始材料**忠实还原**成一张
案件经过时间轴。第一个模块 `mqc-litigation-visual-redraw`（诉讼可视化重画）负责
另一件事：把已经有的一张图重画成克制专业、可直接进诉讼材料的图。

---

## 作者

**缪奇川　MIAO QICHUAN**
律师 · 法律 AI 博主 · 讲师 · 畅销书作者

北京执业律师 · 朝阳律协海商海事法专业委员会委员 · 中国政法大学知识产权研究院研究员 ·
亚太国际仲裁院（APIAC）国际仲裁调解员 · iCourt 法律 AI 研究院特聘专家顾问 ·
《法律人养虾手册》作者。

---

## 方法论

法律人的 AI 工具，不该是一条提示词，而应是一个**工作系统**。「新诉讼可视化」遵循
三条原则：

- **场景极度垂直** · 只做诉讼可视化这一件事，而这个模块只做其中的时间轴；
- **SOP 极度精简** · 每一步判断都有明确规则、可复现结果，弱模型也能稳；
- **交付极度优雅** · 输出看起来像 McKinsey 的图表，而非学生的作业。

落到时间轴上，多出一条只属于这个模块的主张：**画图不是把法律变轻佻，而是把思考
变透明。** 一段绕来绕去的长论未必是严谨，有时只是想得还不够清楚，便用文字盖住了。
所以工具的方向不是「让图好看」，而是逼作图的人先把案子想透，再把骨架摊开，每一个
节点都经得起对方和法官逐一推敲。

由此长出两条纪律，写进代码而不是写进说明书：

- **难的那部分不在模型手里。** 模型只读懂意思、只输出 JSON；位置、尺寸、层数、
  字数容量全部由确定性脚本算。换一个更弱的模型，出来仍是同一张图。
- **图上的每一个字都要能追回材料原文。** 只许删减，不许改写；承诺与约定不进主轴；
  说不准就不画。一张看着正常其实排错了的图，比不出图危险得多。

视觉只有一条主张：**克制的灰阶 + 唯一深红重点**（`#991B1B`，与本系列 LOGO 同源），
秩序感来自对齐、留白、层级，不靠装饰。

---

## 联系

- **项目仓库**：[new-litigation-visualization](https://github.com/MiaoQichuan/new-litigation-visualization)
- **GitHub**：[@MiaoQichuan](https://github.com/MiaoQichuan)
- **邮箱**：miaoqichuan@hotmail.com

---

<p align="center"><b>把法律画出来 · Make the Law Visible</b><br/>
新诉讼可视化 New Litigation Visualization · 缪奇川 出品</p>
