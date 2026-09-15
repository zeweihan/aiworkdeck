// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.service.team;

import com.checkba.service.LangText;
import com.checkba.service.SystemSettingService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 项目名随团队统计上云前的<b>一次性确认</b>（每台机器一次）。
 *
 * <h3>为什么要有它</h3>
 * {@code shareProjectNames} 默认 true 是维护者裁决（设计 §9 第 1 条），不改。但那条裁决说的是
 * 「团队层面允许项目名上云」，不是「机器主人已经知道自己的客户名会出现在别人的看板上」。
 * v0.44.1 真机实测踩到的正是这个差：用户刚加入团队、打开共享开关，团队看板里立刻出现了
 * 他其他客户的真实项目名——那些名字是律师的客户信息，不是产品的计数指标。
 *
 * <p>所以闸门落在「第一次真的要把项目名发出去」那一刻：把将要上传的名字原样摆出来，
 * 说清它们会出现在哪、谁看得到，由机器主人当场决定。<b>拦的是项目名，不是整条统计通道</b>——
 * 拒绝之后统计照常上报，只是项目行只剩不可逆短码。
 *
 * <h3>形态照 {@code MeetingRecordingNotice}</h3>
 * <b>文本与版本号放在同一个文件里</b>：分开放（文案在 Vue、版本在 Java）必然出现
 * 「改了文案忘了推版本」，那时全体用户的旧确认会覆盖到他们从没看过的新处理方式。
 *
 * <p><b>默认必须是「没决定过」</b>：预先勾选的同意在个保法下无效，这里连「默认已知悉」都不给。
 *
 * <p>文案红线：不得含「登录」「未授权」「请先」——{@code frontend/src/services/api.js}
 * 历史上拿这三个子串判掉线并清会话。全站禁 emoji。
 */
@Service
public class TeamProjectNameNotice {

    /**
     * 告知文本的版本。<b>改了 {@link #body()} 的实质内容就要推它</b>
     * （新增接收方、改变可见范围都算实质变化；错字不算）。推了之后旧决定失效，
     * 机器主人会在下一次上传项目名之前重新看到这段告知。
     */
    public static final String VERSION = "2026-09-15";

    /** 形态照 {@code meeting.recordingNotice.*}，同样落 system_setting。 */
    public static final String KEY_DECISION = "team.usage.projectNames.decision";
    public static final String KEY_DECIDED_AT = "team.usage.projectNames.decidedAt";
    public static final String KEY_VERSION = "team.usage.projectNames.noticeVersion";

    static final String GRANTED = "granted";
    static final String DECLINED = "declined";

    private final SystemSettingService settings;

    public TeamProjectNameNotice(SystemSettingService settings) {
        this.settings = settings;
    }

    /**
     * 告知正文。四件事必须说全：上传什么、出现在哪、谁看得到、不同意会怎样。
     *
     * <p>最后那条不是客套：拒绝之后统计仍然上报，只是项目行退成匿名短码，
     * 管理者可以自己给短码起别名。只讲风险不给出路的告知，用户唯一能做的是放弃这个功能。
     */
    public String body() {
        return LangText.of(
                "下面这些项目名会随每日使用统计一起上传到你的 AI WorkDeck 账户，"
                        + "并出现在团队看板的项目表里：团队与律所的管理者能看到全部，普通成员只看到自己的。"
                        + "选择「只传匿名编号」的话，项目名不出本机，看板上只显示不可逆短码，"
                        + "管理者可以自己给短码起别名。两种选择下统计都只含计数，不含文档内容、文件名与对话。",
                "These project names will be uploaded to your AI WorkDeck account together with the daily "
                        + "usage statistics and shown in the project table of the team dashboard: team and firm "
                        + "managers see all of them, ordinary members only see their own. If you choose "
                        + "\"anonymous codes only\", the names never leave this machine and the dashboard shows "
                        + "irreversible short codes instead, which managers can alias themselves. Either way the "
                        + "statistics carry only counts, never document content, file names or conversations.");
    }

    /** 这台机器是否已就<b>当前版本</b>的告知做过决定（同意或拒绝都算）。 */
    public boolean decided() {
        return !current().isEmpty();
    }

    /** 已同意：项目名可以随统计上传。 */
    public boolean granted() {
        return GRANTED.equals(current());
    }

    /** 已拒绝：项目名一律抹成 null，统计其余部分照常上报。 */
    public boolean declined() {
        return DECLINED.equals(current());
    }

    /** 记下决定。同意与拒绝都写，落的是「机器主人看过这一版告知并选了什么」。 */
    public void decide(boolean granted) {
        settings.set(KEY_DECISION, granted ? GRANTED : DECLINED);
        settings.set(KEY_DECIDED_AT, Instant.now().toString());
        settings.set(KEY_VERSION, VERSION);
    }

    /**
     * 回到「没决定过」。换账户 / 退出团队时调用：这个决定是针对<b>那个团队</b>的听众给的，
     * 换了听众必须重新问一次。
     */
    public void reset() {
        settings.set(KEY_DECISION, "");
        settings.set(KEY_DECIDED_AT, "");
        settings.set(KEY_VERSION, "");
    }

    /** 供设置页展示的状态（不含名字清单，那要跑一遍聚合，见 TeamUsageUploadService）。 */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decided", decided());
        out.put("granted", granted());
        out.put("version", VERSION);
        return out;
    }

    /** 版本不一致按「没决定过」处理：告知内容变了，旧决定覆盖不到新的可见范围。 */
    private String current() {
        if (!VERSION.equals(settings.get(KEY_VERSION, ""))) return "";
        String decision = settings.get(KEY_DECISION, "");
        return GRANTED.equals(decision) || DECLINED.equals(decision) ? decision : "";
    }
}
