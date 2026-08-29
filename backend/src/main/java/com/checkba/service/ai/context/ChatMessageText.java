package com.checkba.service.ai.context;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;

/**
 * 消息栈取文本 / 数图片的单一口径。
 *
 * <p><b>为什么必须收成一处</b>：langchain4j 0.36 的 {@code UserMessage.text()} 就是
 * {@code singleText()}（字节码只有一条 {@code invokevirtual singleText()}），而 {@code singleText()}
 * 在 {@code contents().size() != 1 || !(get(0) instanceof TextContent)} 时直接
 * {@code throw new RuntimeException("Expecting single text content, but got: ...")}。
 * 也就是说**「文本 + 图片」的消息一样会抛**，不是只有纯图片才抛。
 *
 * <p>接上图片多模态之前，仓里已经有四处各自写了一份「遍历 contents 只取 TextContent」的防御
 * （RunLoopCompactor / ConversationSummarizer / ProjectMemoryExtractor / MemCellExtractor），
 * 另有四处没防（AgentOrchestrator 的 debug 日志、ContextCompressor 两处、测试助手）。
 * 八处各写各的就是下一次漏一处的原因，所以收到这里，新增取文本的地方一律调本类。
 *
 * <p><b>特别注意 slf4j 的坑</b>：{@code log.debug("...", m.text().length())} 的参数是**提前求值**的，
 * 日志级别停在 INFO 也照样执行。多模态消息在这种写法下 100% 抛异常，而且抛在你以为不会执行的地方。
 */
public final class ChatMessageText {

    private ChatMessageText() {
    }

    /**
     * 统一取文本：用户消息只取 TextContent（图片不产生字符），AI 消息带上工具调用名与参数，
     * 工具结果取正文。任何消息类型都不抛异常。
     */
    public static String of(ChatMessage m) {
        if (m instanceof UserMessage um) {
            StringBuilder sb = new StringBuilder();
            for (Content c : um.contents()) {
                if (c instanceof TextContent tc) {
                    sb.append(nullToEmpty(tc.text()));
                }
            }
            return sb.toString();
        }
        if (m instanceof SystemMessage sm) {
            return nullToEmpty(sm.text());
        }
        if (m instanceof ToolExecutionResultMessage tr) {
            return nullToEmpty(tr.text());
        }
        if (m instanceof AiMessage ai) {
            StringBuilder sb = new StringBuilder(nullToEmpty(ai.text()));
            if (ai.hasToolExecutionRequests()) {
                for (ToolExecutionRequest r : ai.toolExecutionRequests()) {
                    sb.append(r.name()).append(nullToEmpty(r.arguments()));
                }
            }
            return sb.toString();
        }
        return "";
    }

    /** 这条消息里有几个图像内容块。 */
    public static int imageCountOf(ChatMessage m) {
        if (!(m instanceof UserMessage um)) {
            return 0;
        }
        int n = 0;
        for (Content c : um.contents()) {
            if (c instanceof ImageContent) {
                n++;
            }
        }
        return n;
    }

    /**
     * 消息栈里有没有图像内容块。
     *
     * <p>消费者是故障转移：带图的一轮切到读不了图的备用模型，会拿着 image 块原样重发，
     * 换来一个上游 400，而第一个模型的图像 token 已经花掉了。
     */
    public static boolean containsImage(List<ChatMessage> messages) {
        if (messages == null) {
            return false;
        }
        for (ChatMessage m : messages) {
            if (imageCountOf(m) > 0) {
                return true;
            }
        }
        return false;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
