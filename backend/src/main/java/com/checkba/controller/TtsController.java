package com.checkba.controller;

import com.checkba.service.TtsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.List;

@RestController
@RequestMapping("/api/tts")
public class TtsController {

    @Autowired
    private TtsService ttsService;

    /**
     * 合成走的是账号配置里的 ElevenLabs 付费 key，匿名可调等于把它借给任何人烧额度；
     * 口径与 OcrController/ClipboardController 一致，只要求登录，不涉及项目归属。
     */
    private static void requireLogin(String sessionId) {
        if (AuthController.getUserIdFromSession(sessionId) == null) {
            throw new com.checkba.exception.UnauthorizedException("请先登录");
        }
    }

    @GetMapping("/voices")
    public List<TtsService.VoiceOption> getVoices(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireLogin(sessionId);
        return ttsService.getVoices();
    }

    @PostMapping("/generate")
    public ResponseEntity<Resource> generate(@RequestBody GenerateRequest request,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        requireLogin(sessionId);
        File audioFile = ttsService.generateAudio(
                request.getText(),
                request.getVoice(),
                request.getRate(),
                request.getPitch(),
                request.getVolume()
        );

        FileSystemResource resource = new FileSystemResource(audioFile);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + audioFile.getName() + "\"")
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .body(resource);
    }

    public static class GenerateRequest {
        private String text;
        private String voice;
        private String rate;
        private String pitch;
        private String volume;

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getVoice() { return voice; }
        public void setVoice(String voice) { this.voice = voice; }
        public String getRate() { return rate; }
        public void setRate(String rate) { this.rate = rate; }
        public String getPitch() { return pitch; }
        public void setPitch(String pitch) { this.pitch = pitch; }
        public String getVolume() { return volume; }
        public void setVolume(String volume) { this.volume = volume; }
    }
}
