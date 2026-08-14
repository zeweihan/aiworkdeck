package com.checkba.service.meeting;

import com.aliyun.tingwu20230930.Client;
import com.aliyun.tingwu20230930.models.CreateTaskRequest;
import com.aliyun.tingwu20230930.models.CreateTaskResponse;
import com.aliyun.tingwu20230930.models.GetTaskInfoResponse;
import com.aliyun.tingwu20230930.models.GetTaskInfoResponseBody;
import com.aliyun.teaopenapi.models.Config;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通义听悟官方 SDK 实现（OpenAPI 2023-09-30，Tea 核心与 OCR SDK 共享）。
 * 端点固定北京地域——听悟目前仅 cn-beijing 提供服务。
 */
@Component
public class TingwuClientImpl implements TingwuClient {

    private static final String ENDPOINT = "tingwu.cn-beijing.aliyuncs.com";

    private Client client(MeetingAsrSettings s) throws Exception {
        Config config = new Config()
                .setAccessKeyId(s.accessKeyId())
                .setAccessKeySecret(s.accessKeySecret())
                .setEndpoint(ENDPOINT);
        return new Client(config);
    }

    @Override
    public String submitTask(MeetingAsrSettings settings, String fileUrl) throws Exception {
        CreateTaskRequest.CreateTaskRequestInput input = new CreateTaskRequest.CreateTaskRequestInput()
                .setFileUrl(fileUrl)
                .setSourceLanguage("cn");

        // SpeakerCount=0：说话人数不定，由听悟自行聚类——开会前没人知道会来几个人
        CreateTaskRequest.CreateTaskRequestParametersTranscriptionDiarization diarization =
                new CreateTaskRequest.CreateTaskRequestParametersTranscriptionDiarization()
                        .setSpeakerCount(0);
        CreateTaskRequest.CreateTaskRequestParametersTranscription transcription =
                new CreateTaskRequest.CreateTaskRequestParametersTranscription()
                        .setDiarizationEnabled(true)
                        .setDiarization(diarization);

        CreateTaskRequest.CreateTaskRequestParametersSummarization summarization =
                new CreateTaskRequest.CreateTaskRequestParametersSummarization()
                        .setTypes(List.of("Paragraph", "Conversational", "QuestionsAnswering"));

        CreateTaskRequest.CreateTaskRequestParametersMeetingAssistance meetingAssistance =
                new CreateTaskRequest.CreateTaskRequestParametersMeetingAssistance()
                        .setTypes(List.of("Actions", "KeyInformation"));

        CreateTaskRequest.CreateTaskRequestParameters parameters =
                new CreateTaskRequest.CreateTaskRequestParameters()
                        .setTranscription(transcription)
                        .setAutoChaptersEnabled(true)
                        .setSummarizationEnabled(true)
                        .setSummarization(summarization)
                        .setMeetingAssistanceEnabled(true)
                        .setMeetingAssistance(meetingAssistance);

        CreateTaskRequest request = new CreateTaskRequest()
                .setType("offline")
                .setAppKey(settings.appKey())
                .setInput(input)
                .setParameters(parameters);

        CreateTaskResponse response = client(settings).createTask(request);
        if (response == null || response.getBody() == null || response.getBody().getData() == null
                || response.getBody().getData().getTaskId() == null) {
            throw new IllegalStateException("听悟建任务失败：返回缺少 TaskId"
                    + (response != null && response.getBody() != null
                        ? "（" + response.getBody().getMessage() + "）" : ""));
        }
        return response.getBody().getData().getTaskId();
    }

    @Override
    public TaskInfo getTask(MeetingAsrSettings settings, String taskId) throws Exception {
        GetTaskInfoResponse response = client(settings).getTaskInfo(taskId);
        if (response == null || response.getBody() == null || response.getBody().getData() == null) {
            throw new IllegalStateException("听悟查任务失败：返回为空");
        }
        GetTaskInfoResponseBody.GetTaskInfoResponseBodyData data = response.getBody().getData();
        GetTaskInfoResponseBody.GetTaskInfoResponseBodyDataResult result = data.getResult();
        return new TaskInfo(
                data.getTaskStatus(),
                data.getErrorMessage(),
                result != null ? result.getTranscription() : null,
                result != null ? result.getAutoChapters() : null,
                result != null ? result.getSummarization() : null,
                result != null ? result.getMeetingAssistance() : null);
    }
}
