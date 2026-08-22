package com.checkba.service.evidence.webverify;

import java.time.LocalDateTime;

/**
 * 一件网核材料（dev-board#100 P3）：一张截图、一份页面文本，或别的什么外部工具导出的单个文件。
 *
 * @param site        站点；认不出是 {@link WebVerifySite#OTHER}
 * @param queriedAt   查询时间（外部工具的取数时间，不是导入时间）
 * @param fileName    建议的落盘文件名（已按 {@code <站点>-<日期>.<ext>} 归一）
 * @param ext         扩展名（小写、不带点），落库的 fileType
 * @param bytes       文件字节；与 {@code sourcePath} 二选一，离线适配器给的是字节
 * @param sourcePath  文件在适配器本地的路径；联网适配器若不想整个载进内存可以给路径，本仓无实现
 * @param rawText     原始页面文本，可空（截图件通常没有；文本件是它自己的内容）
 * @param summary     结论摘要，可空（如「未见失信记录」，由外部工具在 manifest 里给出，本仓不生成）
 * @param sourceUrl   页面 URL，可空；连同 queriedAt 一起写进 ProjectFile.metaJson 与 web 型 locator
 */
public record WebVerifyResult(WebVerifySite site,
                              LocalDateTime queriedAt,
                              String fileName,
                              String ext,
                              byte[] bytes,
                              String sourcePath,
                              String rawText,
                              String summary,
                              String sourceUrl) {
}
