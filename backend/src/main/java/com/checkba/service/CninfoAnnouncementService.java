package com.checkba.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 巨潮资讯网公告拉取：股东大会通知 + 董事会决议公告。
 * 移植自内核 skill 的 fetch_cninfo_announcements.py（挑选启发式保持一致）。
 * 设计原则：不 fail-hard，异常收集到 FetchResult.errors，调用方据此降级提示上传。
 */
@Service
@Slf4j
public class CninfoAnnouncementService {

    public static final String MARKET_SZ_SH_BJ = "沪深京";
    public static final String MARKET_NEEQ = "三板";

    private static final String STOCK_LIST_URL_MAIN = "http://www.cninfo.com.cn/new/data/szse_stock.json";
    private static final String STOCK_LIST_URL_NEEQ = "http://www.cninfo.com.cn/new/data/gfzr_stock.json";
    private static final String QUERY_API = "http://www.cninfo.com.cn/new/hisAnnouncement/query";
    private static final String STATIC_BASE = "http://static.cninfo.com.cn/";

    private static final String CATEGORY_SHAREHOLDERS = "category_gddh_szsh";
    private static final String CATEGORY_BOARD = "category_dshgg_szsh";

    /** 巨潮 POST 要用浏览器型 UA 否则可能被拒 */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 巨潮公告元数据（挑选启发式的输入/输出） */
    public record Announcement(String title, Long timeMs, String announcementId,
                               String orgId, String adjunctUrl, String secCode) {

        public String pdfUrl() {
            if (adjunctUrl == null || adjunctUrl.isBlank()) return null;
            return STATIC_BASE + adjunctUrl.replaceFirst("^/+", "");
        }

        public LocalDate publishDate() {
            if (timeMs == null || timeMs == 0) return null;
            return Instant.ofEpochMilli(timeMs).atZone(CST).toLocalDate();
        }
    }

    public static class FetchResult {
        public Announcement notice;
        public Announcement resolution;
        public List<Announcement> noticeCandidates = new ArrayList<>();
        public List<Announcement> resolutionCandidates = new ArrayList<>();
        public List<String> errors = new ArrayList<>();
    }

    // ==================== 挑选启发式（纯函数，与 Python 版一致） ====================

    /**
     * 从「股东大会」类目挑主通知：标题含「通知」+（「股东大会」或「股东会」），
     * 排除决议公告/会议资料/取消/延期/更正；多候选取公告时间距会议日最近的。
     */
    public static Announcement pickShareholdersNotice(List<Announcement> anns, LocalDate meetingDate) {
        List<Announcement> candidates = new ArrayList<>();
        for (Announcement a : anns) {
            String title = a.title() == null ? "" : a.title();
            if (!title.contains("通知")) continue;
            if (!(title.contains("股东大会") || title.contains("股东会"))) continue;
            if (containsAny(title, "决议公告", "会议资料", "取消", "延期", "更正")) continue;
            candidates.add(a);
        }
        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparingLong(a -> distanceSeconds(a, meetingDate)));
        return candidates.get(0);
    }

    /**
     * 从「董事会」类目挑决定召开股东会的决议公告：标题含「董事会」+「决议公告」，
     * 排除专门委员会；偏好会议日之前披露且最接近的。
     */
    public static Announcement pickBoardResolution(List<Announcement> anns, LocalDate meetingDate) {
        List<Announcement> candidates = new ArrayList<>();
        for (Announcement a : anns) {
            String title = a.title() == null ? "" : a.title();
            if (!title.contains("董事会")) continue;
            if (!title.contains("决议公告")) continue;
            if (containsAny(title, "独立", "专门委员会", "审计委员会", "提名委员会", "薪酬委员会", "战略委员会")) continue;
            candidates.add(a);
        }
        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparing(a -> boardScore(a, meetingDate)));
        return candidates.get(0);
    }

    private static boolean containsAny(String s, String... kws) {
        for (String kw : kws) {
            if (s.contains(kw)) return true;
        }
        return false;
    }

    private static long distanceSeconds(Announcement a, LocalDate meetingDate) {
        if (a.timeMs() == null || a.timeMs() == 0) return Long.MAX_VALUE;
        long meetingMs = meetingDate.atStartOfDay(CST).toInstant().toEpochMilli();
        return Math.abs(meetingMs - a.timeMs()) / 1000;
    }

    /** 会前披露优先（rank 0），会后次之（rank 1），无时间最末（rank 2）；同 rank 按距离升序 */
    private static long boardScore(Announcement a, LocalDate meetingDate) {
        if (a.timeMs() == null || a.timeMs() == 0) return Long.MAX_VALUE;
        long meetingMs = meetingDate.atStartOfDay(CST).toInstant().toEpochMilli();
        long diffSec = (meetingMs - a.timeMs()) / 1000;
        if (diffSec >= 0) {
            return diffSec; // 会前：距离越近越小
        }
        // 会后：加一个大偏移保证排在所有会前候选之后
        return 100L * 365 * 24 * 3600 + (-diffSec);
    }

    // ==================== HTTP 拉取 ====================

    /**
     * 主入口：按股票代码 + 会议日期搜索并挑选通知/决议。不下载 PDF。
     */
    public FetchResult fetchForMeeting(String stockCode, String market, LocalDate meetingDate) {
        FetchResult result = new FetchResult();
        String orgId;
        try {
            orgId = fetchOrgId(stockCode, market);
        } catch (Exception e) {
            log.warn("cninfo 获取 orgId 失败: stockCode={}", stockCode, e);
            result.errors.add("获取公司 orgId 失败: " + e.getMessage());
            return result;
        }

        // 向前 45 天（覆盖年度股东大会 20 日通知期 + 余量），向后留 3 天缓冲
        LocalDate start = meetingDate.minusDays(45);
        LocalDate end = meetingDate.plusDays(3);

        try {
            result.noticeCandidates = searchCategory(stockCode, orgId, CATEGORY_SHAREHOLDERS, start, end);
            result.notice = pickShareholdersNotice(result.noticeCandidates, meetingDate);
            if (result.notice == null) {
                result.errors.add("未找到股东大会通知（类目内 " + result.noticeCandidates.size() + " 条公告无命中）");
            }
        } catch (Exception e) {
            log.warn("cninfo 搜索股东大会类目失败", e);
            result.errors.add("搜索「股东大会」类目失败: " + e.getMessage());
        }

        try {
            // 对巨潮服务器友好一点
            Thread.sleep(1000);
            result.resolutionCandidates = searchCategory(stockCode, orgId, CATEGORY_BOARD, start, end);
            result.resolution = pickBoardResolution(result.resolutionCandidates, meetingDate);
            if (result.resolution == null) {
                result.errors.add("未找到董事会决议公告（类目内 " + result.resolutionCandidates.size() + " 条公告无命中）");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            result.errors.add("拉取被中断");
        } catch (Exception e) {
            log.warn("cninfo 搜索董事会类目失败", e);
            result.errors.add("搜索「董事会」类目失败: " + e.getMessage());
        }
        return result;
    }

    public byte[] downloadPdf(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("PDF 下载失败，HTTP " + response.statusCode() + ": " + url);
        }
        return response.body();
    }

    private String fetchOrgId(String stockCode, String market) throws IOException, InterruptedException {
        String url = MARKET_NEEQ.equals(market) ? STOCK_LIST_URL_NEEQ : STOCK_LIST_URL_MAIN;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("股票列表请求失败，HTTP " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        for (JsonNode item : root.path("stockList")) {
            if (stockCode.equals(item.path("code").asText())) {
                return item.path("orgId").asText();
            }
        }
        throw new IOException("股票代码 " + stockCode + " 不在巨潮" + market + "列表中（代码错误/已退市/板块不对）");
    }

    private List<Announcement> searchCategory(String stockCode, String orgId, String categoryCode,
                                              LocalDate start, LocalDate end) throws IOException, InterruptedException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("pageNum", "1");
        form.put("pageSize", "30");
        form.put("column", "szse");
        form.put("tabName", "fulltext");
        form.put("plate", "");
        form.put("stock", stockCode + "," + orgId);
        form.put("searchkey", "");
        form.put("secid", "");
        form.put("category", categoryCode);
        form.put("trade", "");
        form.put("seDate", start + "~" + end);
        form.put("sortName", "time");
        form.put("sortType", "desc");
        form.put("isHLtitle", "true");

        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(QUERY_API))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("公告查询失败，HTTP " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        List<Announcement> anns = new ArrayList<>();
        for (JsonNode a : root.path("announcements")) {
            anns.add(new Announcement(
                    a.path("announcementTitle").asText(null),
                    a.path("announcementTime").asLong(0),
                    a.path("announcementId").asText(null),
                    a.path("orgId").asText(null),
                    a.path("adjunctUrl").asText(null),
                    a.path("secCode").asText(null)
            ));
        }
        return anns;
    }
}
