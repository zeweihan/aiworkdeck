// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.checkba.controller;

import com.checkba.exception.UnauthorizedException;
import com.checkba.service.LangText;
import com.checkba.service.mobile.DesktopStreamService;
import com.checkba.service.mobile.MobileRelayStoreService;
import com.checkba.service.mobile.ReferenceRequestStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 桌面端门铃流与参考读取的取件/回传（dev-board#718 #719，spec 第 5、6 节）。桌面端专用。
 *
 * <p>鉴权同 {@link MobileRelayController} 那一组：{@code X-Session-Id}（桌面端带 awdt_ 设备令牌）。
 * 门铃流是 SSE，鉴权失败回裸 401（JSON 信封塞不进 text/event-stream 的协商），其余两个端点照
 * 全站惯例：未登录 4010 信封、业务错误 200 + code:1。
 *
 * <p>红线：参考材料正文只在内存里过一遍，日志只记 id、长度与条数，绝不记正文与文件路径。
 */
@RestController
@RequestMapping("/api/mobile")
@Slf4j
public class MobileRefController {

    private static final int MAX_DEVICE_ID = 64;

    private final DesktopStreamService stream;
    private final ReferenceRequestStore requests;
    private final MobileRelayStoreService relayStore;

    public MobileRefController(DesktopStreamService stream, ReferenceRequestStore requests,
                               MobileRelayStoreService relayStore) {
        this.stream = stream;
        this.requests = requests;
        this.relayStore = relayStore;
    }

    /**
     * 门铃流：连上立刻 {@code event:ready}，每 15 秒 {@code event:ping}，有待办
     * {@code event:nudge}（data {@code {"kind":"ref"|"transfer"}}）；同设备后连顶掉先连
     * （先连收到 {@code event:superseded}）。建连顺带记一次设备心跳。
     */
    @GetMapping("/desktop/stream")
    public ResponseEntity<SseEmitter> desktopStream(
            @RequestParam(value = "deviceId", required = false) String deviceId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        if (!validDeviceId(deviceId)) {
            return ResponseEntity.badRequest().build();
        }
        relayStore.touchDevice(userId, deviceId);
        SseEmitter emitter = stream.connect(userId, deviceId);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("X-Accel-Buffering", "no") // 关掉 nginx 代理缓冲，门铃要即时到
                .body(emitter);
    }

    /** 取件：取出即标记已下发，同一条不会下发第二次。 */
    @GetMapping("/ref/requests")
    public Map<String, Object> takeRequests(
            @RequestParam(value = "deviceId", required = false) String deviceId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        if (!validDeviceId(deviceId)) {
            throw new IllegalArgumentException(LangText.of("缺少设备标识", "Missing device id"));
        }
        List<Map<String, Object>> taken = requests.take(userId, deviceId);
        if (!taken.isEmpty()) {
            log.info("桌面端取走参考请求 {} 条：userId={}, deviceId={}", taken.size(), userId, deviceId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", 0);
        out.put("requests", taken);
        return out;
    }

    /**
     * 回传：body {@code {ok:true, entries:[…]}}（LIST）/ {@code {ok:true, text}}（READ）/
     * {@code {ok:true, opened:true}}（OPEN）/ {@code {ok:false, error}}。
     * 请求不属于该用户 → 403；已过期或已完成 → {@code {code:0, stale:true}}（桌面端照常收尾，不重试）。
     */
    @PostMapping("/ref/{id}/result")
    public ResponseEntity<Map<String, Object>> result(
            @PathVariable("id") String id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        Long userId = requireUser(sessionId);
        ReferenceRequestStore.Outcome outcome = requests.complete(userId, id, body);
        Object text = body == null ? null : body.get("text");
        Object entries = body == null ? null : body.get("entries");
        log.info("桌面端参考结果回传：id={}, ok={}, chars={}, entries={}, outcome={}", id,
                body == null ? null : body.get("ok"),
                text instanceof String s ? s.length() : 0,
                entries instanceof List<?> l ? l.size() : 0,
                outcome);
        return switch (outcome) {
            case FORBIDDEN -> ResponseEntity.status(403).body(Map.of("code", 1,
                    "message", LangText.of("无权访问该请求", "You do not have access to this request")));
            case STALE -> ResponseEntity.ok(Map.of("code", 0, "stale", true));
            case OK -> ResponseEntity.ok(Map.of("code", 0));
        };
    }

    private static boolean validDeviceId(String deviceId) {
        return deviceId != null && !deviceId.isBlank() && deviceId.length() <= MAX_DEVICE_ID;
    }

    private Long requireUser(String sessionId) {
        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            throw new UnauthorizedException("请先登录");
        }
        return userId;
    }
}
