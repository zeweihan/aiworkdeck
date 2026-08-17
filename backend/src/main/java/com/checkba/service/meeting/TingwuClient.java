package com.checkba.service.meeting;

/**
 * 通义听悟客户端抽象（测试用桩的接缝，SmsTransport 同款做法）。
 * 真实实现见 {@link TingwuClientImpl}。
 */
public interface TingwuClient {

    /** 提交离线转写任务（转写+说话人分离+章节+摘要+待办），返回听悟 TaskId。 */
    String submitTask(MeetingAsrSettings settings, String fileUrl) throws Exception;

    /** 查询任务状态；COMPLETED 时四个结果 URL 至少 transcriptionUrl 非空。 */
    TaskInfo getTask(MeetingAsrSettings settings, String taskId) throws Exception;

    /**
     * 听悟任务快照。status 取值 ONGOING / COMPLETED / FAILED / INVALID（听悟原文，<b>是四个不是三个</b>）。
     * 结果 URL 是听悟侧的临时下载地址（有效期 30 天），拿到后立即下载落库，不持久化 URL 本身。
     */
    record TaskInfo(
            String status,
            String errorMessage,
            String transcriptionUrl,
            String autoChaptersUrl,
            String summarizationUrl,
            String meetingAssistanceUrl
    ) {
        public boolean completed() {
            return "COMPLETED".equalsIgnoreCase(status);
        }

        /**
         * 终态失败。<b>INVALID 必须算在内</b>：它既不是 COMPLETED 也不是 FAILED，
         * 漏判就会被当成「还在跑」，会议永远停在转写中——用户既拿不到稿也看不到错误，
         * 挂在失败路径上的 OSS 中转对象清理也永远不会执行。
         */
        public boolean failed() {
            return "FAILED".equalsIgnoreCase(status) || invalid();
        }

        /** INVALID = 听悟压根没受理这个任务（音频取不到/格式不支持/参数非法），与「跑起来失败了」的成因不同，文案要分得开。 */
        public boolean invalid() {
            return "INVALID".equalsIgnoreCase(status);
        }
    }
}
