// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.checkba.service.writing;

import java.util.*;
import java.util.regex.Pattern;
import static com.checkba.service.writing.WritingTypes.*;

/** A short paragraph is source prose, not a verified fact. Preserve attribution and negatives intact. */
public final class WritingNarrativeSources {
    private WritingNarrativeSources() {}
    private static final Pattern CLAIM=Pattern.compile("^(原告|被告|第三人)(?:主张|认为|辩称|抗辩|称)[：:]?.+");
    public static List<Fact> append(String profile,List<Source> sources,List<Fact> structured) {
        // Unknown contract scope must remain unknown; callers should supply the live editor section.
        return append(profile,new Context(profile,"","","","","",""),sources,structured);
    }
    public static List<Fact> append(String profile,Context context,List<Source> sources,List<Fact> structured) {
        List<Fact> out=new ArrayList<>(structured);
        for(Source source:sources) {
            if("contract".equals(profile) && !"active-document".equals(source.id())) continue;
            Map<Integer,String> contractScope="contract".equals(profile)
                    ? ContractWritingProfile.narrativeScope(context,source) : Map.of();
            int index=0;
            for(String raw:source.text().split("\\R",-1)) {
                int paragraph=++index;
                String text=raw.strip();
                if(text.length()<12 || text.length()>400 || out.size()>=200)continue;
                if("contract".equals(profile) && !contractScope.containsKey(paragraph)) continue;
                // Never create a second citation path around a structured conflict/missing/role fact.
                if(structured.stream().anyMatch(f -> source.id().equals(f.sourceId()) && !clean(f.quote()).isEmpty()
                        && (text.contains(f.quote().strip()) || f.quote().contains(text))))continue;
                String status="DOCUMENT_STATES",role="";
                String contractStatus=contractScope.get(paragraph);
                if(contractStatus!=null && !contractStatus.equals("CONTRACT_TERM")) status=contractStatus;
                var claim=CLAIM.matcher(text);
                if(claim.matches() && status.equals("DOCUMENT_STATES")) {status="CLAIM";role=claim.group(1);}
                else if("diligence".equals(profile) && text.matches(".*(?:未取得|未提供|未收到|无法读取|未能取得).*"))status="MISSING";
                out.add(new Fact("prose:"+source.id()+":"+paragraph,"SOURCE_TEXT","材料第"+paragraph+"段",text,role,status,source.id(),text));
            }
        }
        return List.copyOf(out);
    }
}
