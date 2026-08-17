package com.checkba.service.meeting;

import com.checkba.service.LangText;
import com.checkba.service.SystemSettingService;

import java.time.Instant;
import java.util.Map;

/**
 * 平台档会议转写的<b>单独告知</b>（每台机器确认一次）。
 *
 * <h3>为什么单独拎出来告知，而不是并进服务条款</h3>
 * 会议录音是全产品<b>唯一会完整落到我们磁盘上的用户内容</b>，而且录的往往是第三方
 * （客户、对方当事人）的声音——被录的人不是我们的用户，没同意过任何条款。
 * 把这件事混在一揽子条款里，等于让律师在不知情的情况下替第三方做了决定。
 *
 * <h3>与跨境同意（{@code ai.crossBorder.*}）的关系：形态照抄，强度不照抄</h3>
 * <b>版本机制照抄</b>：告知的实质内容改了就把 {@link #VERSION} 往前推，旧确认随即失效、
 * 重新征求一次。文本与版本号<b>放在同一个文件里</b>是有意的——分开放（文案在 Vue、
 * 版本在 Java）必然出现「改了文案忘了推版本」，那时全体用户的旧确认会覆盖到他们
 * 从没看过的新处理方式。
 *
 * <p><b>硬闸不照抄</b>：跨境那条是《个人信息保护法》第三十九条「向境外提供」
 * 必须取得单独同意，所以它在 {@code AdminConfigController} 里做成了写库前的硬拦截。
 * 会议录音走境内听悟 + 我们的境内对象存储，<b>不出境</b>，触发的是告知义务而不是
 * 第三十九条。做成同强度的硬闸，代价是律师录完两小时会、点转写时被模态框拦住——
 * 那是最差的打断时机。因此这里只提供状态与文本，<b>不在任何服务端路径上拦截</b>；
 * 呈现点在会议面板、<b>录音开始之前</b>（也是唯一一个「什么都还没发生」的时刻）。
 *
 * <p>文案红线：不得含「登录」「未授权」「请先」——{@code frontend/src/services/api.js}
 * 拿这三个子串判掉线并清会话。全站禁 emoji。
 */
public final class MeetingRecordingNotice {

    /**
     * 告知文本的版本。<b>改了下面 {@link #body()} 的实质内容就要推它</b>
     * （新增接收方、改变留存时长、改变删除时机都算实质变化；错字不算）。
     * 推了之后旧确认失效，用户会在下次录音前重新看到这段告知。
     */
    public static final String VERSION = "2026-08-17";

    /** 形态照 {@code ai.crossBorder.consentAt} / {@code .consentVersion}，同样落 system_setting。 */
    public static final String KEY_ACKNOWLEDGED_AT = "meeting.recordingNotice.acknowledgedAt";
    public static final String KEY_VERSION = "meeting.recordingNotice.version";

    private MeetingRecordingNotice() {
    }

    /**
     * 告知正文。四件事必须说全：传到哪、转给谁、什么时候删、不想出网怎么办。
     *
     * <p>最后那条不是客套——本机转写（P3）已经可用，「改用本地档」是一条真出路。
     * 只讲风险不给出路的告知，用户唯一能做的是放弃这个功能。
     */
    public static String body() {
        return LangText.of(
                "开始录音前知悉：选择「平台代采」转写时，录音文件会上传到 AI WorkDeck 的对象存储（境内），"
                        + "再转给阿里云通义听悟完成转写；转写结束后我们即刻删除该文件，另有 24 小时的存储桶"
                        + "生命周期规则兜底清理。转写稿与录音本身仍留在你本机的项目文件里。"
                        + "会议里往往还有第三方的声音，确认你已取得他们的同意再录。"
                        + "这场谈话不能出网的话，打开「录音不出本机」，转写全程在本机完成，不上传任何音频。",
                "Before you start recording: with platform-sourced transcription, the audio file is uploaded to "
                        + "AI WorkDeck object storage (inside mainland China) and then handed to Alibaba Cloud Tingwu "
                        + "for transcription. We delete the file as soon as transcription finishes, with a 24-hour "
                        + "bucket lifecycle rule as a backstop. The transcript and the recording itself stay in your "
                        + "local project files. Meetings usually capture other people's voices as well; make sure you "
                        + "have their consent before recording. If this conversation must not leave the machine, turn on "
                        + "\"Keep recordings on this machine\" and the whole transcription runs locally with no upload.");
    }

    /**
     * 这台机器是否已就<b>当前版本</b>的告知确认过。
     *
     * <p>版本不一致按未确认处理：告知内容变了，旧确认覆盖不到新的处理方式。
     * 默认必须是「没确认过」——预先勾选的同意在个保法下无效，这里连「默认已知悉」都不给。
     */
    public static boolean acknowledged(SystemSettingService settings) {
        String at = settings.get(KEY_ACKNOWLEDGED_AT, "");
        String version = settings.get(KEY_VERSION, "");
        return at != null && !at.isBlank() && VERSION.equals(version);
    }

    /** 已确认的时间戳；未确认（含版本过期）时为空串。 */
    public static String acknowledgedAt(SystemSettingService settings) {
        return acknowledged(settings) ? settings.get(KEY_ACKNOWLEDGED_AT, "") : "";
    }

    /**
     * 写入确认 / 撤回确认。
     *
     * <p>撤回（个保法第十五条的对应动作）写空串而不是删行：删行会让
     * {@code SystemSettingService.get(key, default)} 回落到默认值，而这里的默认值恰好
     * 也是空串——两种写法今天等价，但空串是显式的，不依赖「默认值刚好也是空」这个巧合。
     */
    public static Map<String, String> updates(boolean acknowledged) {
        return acknowledged
                ? Map.of(KEY_ACKNOWLEDGED_AT, Instant.now().toString(), KEY_VERSION, VERSION)
                : Map.of(KEY_ACKNOWLEDGED_AT, "", KEY_VERSION, "");
    }
}
