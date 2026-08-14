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
     * 听悟任务快照。status 取值 ONGOING / COMPLETED / FAILED（听悟原文）。
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

        public boolean failed() {
            return "FAILED".equalsIgnoreCase(status);
        }
    }
}
