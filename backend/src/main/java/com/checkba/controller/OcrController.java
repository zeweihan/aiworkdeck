package com.checkba.controller;

import com.checkba.model.entity.User;
import com.checkba.repository.UserRepository;
import com.checkba.service.LangText;
import com.checkba.service.OcrService;
import com.checkba.service.ocr.OcrResult;
import com.checkba.storage.StorageServiceFactory;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * OCR 接口（H5 截图摘录使用）
 */
@RestController
@RequestMapping("/api/ocr")
@RequiredArgsConstructor
public class OcrController {

    private final OcrService ocrService;
    private final UserRepository userRepository;
    private final StorageServiceFactory storageServiceFactory;

    @PostMapping("/recognize")
    public ResponseEntity<?> recognize(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody OcrRequest request) {

        Long userId = AuthController.getUserIdFromSession(sessionId);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error("请先登录"));
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error("请先登录"));
        }

        OcrResult result = ocrService.recognizeGeneral(request.getImageBase64());
        Map<String, Object> ok = new HashMap<>();
        ok.put("code", 0);
        ok.put("message", "OK");
        Map<String, Object> data = new HashMap<>();
        data.put("text", result.getText());
        data.put("raw", result.getRaw());
        ok.put("data", data);
        return ResponseEntity.ok(ok);
    }

    /**
     * 提供临时图片的公网访问（供阿里云 OCR 读取 ImageURL）
     * 注意：该 URL 需要通过公网域名访问，localhost 无法被阿里云访问。
     */
    @GetMapping("/temp/{fileName}")
    public ResponseEntity<?> tempImage(@PathVariable String fileName) {
        if (fileName == null || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error(LangText.of("fileName 非法", "Invalid fileName")));
        }
        String path = "ocr/tmp/" + fileName;
        try {
            Resource res = storageServiceFactory.getStorageService().load(path);
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("not found"));
        }
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 1);
        result.put("message", message);
        result.put("data", new HashMap<>());
        return result;
    }

    public static class OcrRequest {
        private String imageBase64;

        public String getImageBase64() { return imageBase64; }
        public void setImageBase64(String imageBase64) { this.imageBase64 = imageBase64; }
    }
}


