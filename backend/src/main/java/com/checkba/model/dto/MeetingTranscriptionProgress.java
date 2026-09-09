// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.model.dto;

/**
 * 「转写中」的进度提示（dev-board#532）。只在 {@code status=TRANSCRIBING} 时出现，
 * 挂在 MeetingRecording 的 {@code @Transient progress} 上随会议实体一起序列化，
 * <b>不新增端点、不改动既有字段</b>。
 *
 * @param stage       当前阶段。{@code PREPARING}=转码与上传（云端两档，还没拿到上游任务号）、
 *                    {@code LOCAL}=本机转写（local 档全程没有任务号）、
 *                    {@code UPSTREAM}=上游转写与说话人分离（已有任务号）。
 *                    界面按这三个值取自己的文案，后端不下发中文串。
 * @param elapsedSec  已用秒数（now - transcribingStartedAt）。存量行补盖时间戳之前为 0。
 * @param estimatedSec 预计总秒数；音频时长未知（右键转写注册的文件没有 durationMs）时为 null，
 *                    此时界面只显示已用时，不编一个预计值。
 * @param percent     0..99 的估算进度；{@code estimatedSec} 为 null 时为 null。
 *                    <b>刻意封顶 99</b>：真正到 100 的唯一凭据是状态机跳到 TRANSCRIBED。
 * @param estimated   预计值与百分比是否为估算。<b>当前恒为 true</b>——通义听悟的
 *                    GetTaskInfo 只回 ONGOING/COMPLETED/FAILED，平台网关只回
 *                    processing/completed/failed，本机 asr-service 是一次性同步调用，
 *                    三条路都没有百分比可取。留着这个位是为了将来上游真给了百分比时，
 *                    界面上「(估算)」那个标注能自动消失，而不用改契约。
 */
public record MeetingTranscriptionProgress(
        String stage,
        long elapsedSec,
        Long estimatedSec,
        Integer percent,
        boolean estimated) {

    public static final String STAGE_PREPARING = "PREPARING";
    public static final String STAGE_LOCAL = "LOCAL";
    public static final String STAGE_UPSTREAM = "UPSTREAM";
}
