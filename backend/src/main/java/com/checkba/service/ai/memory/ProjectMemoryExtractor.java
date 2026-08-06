package com.checkba.service.ai.memory;

import com.checkba.model.entity.MemoryEntry;
import com.checkba.model.entity.ProjectMemory;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 项目记忆提取器
 * 从对话中自动提取项目相关信息并更新项目记忆
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProjectMemoryExtractor {

    private final MemoryManager memoryManager;

    // 法律法规模式
    private static final Pattern LEGAL_REF_PATTERN = Pattern.compile("《[^》]+》(?:第[一二三四五六七八九十百千]+条)?");
    
    // 金额模式
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("([\\d,]+\\.?\\d*)\\s*(万元|亿元|元|万|亿)");

    /** 金额合理性上限（100 万亿元）：超出视为转写/识别噪音，防 NUMERIC(20,2) 溢出 */
    private static final BigDecimal MAX_PLAUSIBLE_AMOUNT = new BigDecimal("100000000000000");
    
    // 日期模式
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日");
    
    // 公司名称模式（简化版）
    private static final Pattern COMPANY_PATTERN = Pattern.compile("([\\u4e00-\\u9fa5]+(?:股份|集团|科技|投资|控股)?有限(?:责任)?公司)");
    
    // 当事人模式
    private static final Pattern PARTY_PATTERN = Pattern.compile("(甲方|乙方|丙方|丁方|发行人|标的公司|上市公司|交易对方)[：:：]\\s*([^\\n,，。]+)");

    /**
     * 从对话消息中提取信息并更新项目记忆
     */
    public void extractAndUpdateProjectMemory(Long projectId, List<ChatMessage> messages) {
        log.info("Extracting project memory from {} messages for projectId={}", messages.size(), projectId);
        
        StringBuilder allContent = new StringBuilder();
        for (ChatMessage msg : messages) {
            if (msg instanceof UserMessage um) {
                // 安全提取文本：多模态 UserMessage（含图片）调 singleText() 会抛异常导致整轮抽取失败
                allContent.append(um.contents().stream()
                        .filter(dev.langchain4j.data.message.TextContent.class::isInstance)
                        .map(c -> ((dev.langchain4j.data.message.TextContent) c).text())
                        .collect(java.util.stream.Collectors.joining(" "))).append("\n");
            } else if (msg instanceof AiMessage am) {
                allContent.append(am.text()).append("\n");
            }
        }
        
        String content = allContent.toString();
        
        // 提取法律引用
        List<String> legalRefs = extractLegalReferences(content);
        
        // 提取金额
        Map<String, BigDecimal> amounts = extractAmounts(content);
        
        // 提取日期
        List<String> dates = extractDates(content);
        
        // 提取公司名称
        List<String> companies = extractCompanies(content);
        
        // 提取当事人
        Map<String, String> parties = extractParties(content);
        
        // 更新项目记忆
        ProjectMemory pm = memoryManager.getProjectMemory(projectId)
                .orElse(ProjectMemory.builder().projectId(projectId).build());
        
        // 合并法律引用
        if (!legalRefs.isEmpty()) {
            List<String> existingRefs = pm.getLegalRefs() != null ? pm.getLegalRefs() : new ArrayList<>();
            Set<String> allRefs = new LinkedHashSet<>(existingRefs);
            allRefs.addAll(legalRefs);
            pm.setLegalRefs(new ArrayList<>(allRefs));
        }
        
        // 设置交易金额（取最大的）
        if (!amounts.isEmpty()) {
            BigDecimal maxAmount = amounts.values().stream()
                    .max(BigDecimal::compareTo)
                    .orElse(null);
            if (maxAmount != null && (pm.getTransactionAmount() == null || 
                    maxAmount.compareTo(pm.getTransactionAmount()) > 0)) {
                pm.setTransactionAmount(maxAmount);
            }
        }
        
        // 更新关键日期
        if (!dates.isEmpty() && pm.getKeyDates() == null) {
            Map<String, String> keyDates = new HashMap<>();
            for (int i = 0; i < Math.min(dates.size(), 5); i++) {
                keyDates.put("日期" + (i + 1), dates.get(i));
            }
            pm.setKeyDates(keyDates);
        }
        
        // 更新当事方信息
        if (!parties.isEmpty()) {
            List<Map<String, String>> partyList = new ArrayList<>();
            parties.forEach((role, name) -> {
                Map<String, String> party = new HashMap<>();
                party.put("role", role);
                party.put("name", name);
                partyList.add(party);
            });
            pm.setParties(partyList);
        }
        
        memoryManager.saveProjectMemory(pm);
        log.info("Project memory updated: legalRefs={}, amounts={}, dates={}, parties={}",
                legalRefs.size(), amounts.size(), dates.size(), parties.size());
    }

    /**
     * 提取法律引用
     */
    public List<String> extractLegalReferences(String content) {
        List<String> refs = new ArrayList<>();
        Matcher matcher = LEGAL_REF_PATTERN.matcher(content);
        while (matcher.find()) {
            String ref = matcher.group();
            if (!refs.contains(ref)) {
                refs.add(ref);
            }
        }
        return refs;
    }

    /**
     * 提取金额
     */
    public Map<String, BigDecimal> extractAmounts(String content) {
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        Matcher matcher = AMOUNT_PATTERN.matcher(content);
        while (matcher.find()) {
            try {
                String numStr = matcher.group(1).replace(",", "");
                String unit = matcher.group(2);
                BigDecimal amount = new BigDecimal(numStr);

                // 转换为元
                switch (unit) {
                    case "万元", "万" -> amount = amount.multiply(new BigDecimal("10000"));
                    case "亿元", "亿" -> amount = amount.multiply(new BigDecimal("100000000"));
                }

                // 合理性钳制：语音转写噪音（如"1000100010001400万"）会撑爆
                // transaction_amount NUMERIC(20,2) 并让整条 project_memory 更新失败，
                // 超出 100 万亿元的金额按噪音丢弃
                if (amount.compareTo(MAX_PLAUSIBLE_AMOUNT) > 0) {
                    continue;
                }
                amounts.put(matcher.group(), amount);
            } catch (NumberFormatException e) {
                // 忽略解析错误
            }
        }
        return amounts;
    }

    /**
     * 提取日期
     */
    public List<String> extractDates(String content) {
        List<String> dates = new ArrayList<>();
        Matcher matcher = DATE_PATTERN.matcher(content);
        while (matcher.find()) {
            String date = matcher.group();
            if (!dates.contains(date)) {
                dates.add(date);
            }
        }
        return dates;
    }

    /**
     * 提取公司名称
     */
    public List<String> extractCompanies(String content) {
        List<String> companies = new ArrayList<>();
        Matcher matcher = COMPANY_PATTERN.matcher(content);
        while (matcher.find()) {
            String company = matcher.group(1);
            if (company.length() >= 4 && !companies.contains(company)) {
                companies.add(company);
            }
        }
        return companies;
    }

    /**
     * 提取当事人
     */
    public Map<String, String> extractParties(String content) {
        Map<String, String> parties = new LinkedHashMap<>();
        Matcher matcher = PARTY_PATTERN.matcher(content);
        while (matcher.find()) {
            String role = matcher.group(1);
            String name = matcher.group(2).trim();
            if (!name.isEmpty() && name.length() >= 2) {
                parties.put(role, name);
            }
        }
        return parties;
    }

    /**
     * 从对话中提取重要记忆条目
     */
    public List<MemoryEntry> extractMemoryEntries(Long projectId, String conversationId, 
                                                   List<ChatMessage> messages) {
        List<MemoryEntry> entries = new ArrayList<>();
        
        for (ChatMessage msg : messages) {
            String content = null;
            if (msg instanceof AiMessage am) {
                content = am.text();
            }
            
            if (content == null) continue;
            
            // 提取法律引用作为受保护记忆
            List<String> legalRefs = extractLegalReferences(content);
            for (String ref : legalRefs) {
                entries.add(MemoryEntry.builder()
                        .projectId(projectId)
                        .conversationId(conversationId)
                        .memoryType(MemoryEntry.MemoryType.REFERENCE)
                        .memoryKey("法律引用")
                        .memoryValue(ref)
                        .importanceScore(0.9)
                        .isProtected(true)
                        .build());
            }
            
            // 提取结论性语句（简单启发式）
            if (content.contains("经核查") || content.contains("综上") || 
                content.contains("结论是") || content.contains("建议")) {
                // 提取包含这些关键词的句子
                String[] sentences = content.split("[。！？]");
                for (String sentence : sentences) {
                    if (sentence.contains("经核查") || sentence.contains("综上") ||
                        sentence.contains("结论是") || sentence.contains("建议")) {
                        entries.add(MemoryEntry.builder()
                                .projectId(projectId)
                                .conversationId(conversationId)
                                .memoryType(MemoryEntry.MemoryType.CONCLUSION)
                                .memoryKey("核查结论")
                                .memoryValue(sentence.trim())
                                .importanceScore(0.8)
                                .isProtected(false)
                                .build());
                    }
                }
            }
        }
        
        return entries;
    }
}

