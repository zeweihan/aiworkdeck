package com.checkba.service.sensitive;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/** Stateless encrypted mapping. No server-side key or plaintext mapping file is persisted. */
public final class SensitiveRecoveryKit {
    private static final String PREFIX = "AWD-RECOVERY-1:";
    private static final ObjectMapper JSON = new ObjectMapper();
    private SensitiveRecoveryKit() {}

    public static void validatePassword(String password) {
        if (password == null || password.length() < 10 || password.length() > 1024) {
            throw new IllegalArgumentException("复敏密码须为10至1024个字符，请妥善保管");
        }
    }

    public static String encrypt(Map<String, String> mapping, String password) throws Exception {
        validatePassword(password);
        if (mapping.size() > 20000) throw new IllegalArgumentException("敏感实体超过20000项，请拆分文件处理");
        byte[] salt = new byte[16], iv = new byte[12];
        SecureRandom random = new SecureRandom(); random.nextBytes(salt); random.nextBytes(iv);
        byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, password, salt, iv).doFinal(JSON.writeValueAsBytes(mapping));
        String kit = PREFIX + Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(28 + encrypted.length).put(salt).put(iv).put(encrypted).array());
        if (kit.length() > 8_000_000) throw new IllegalArgumentException("复敏映射超过8MB，请拆分文件处理");
        return kit;
    }

    public static Map<String, String> decrypt(String kit, String password) {
        try {
            validatePassword(password);
            if (kit == null || kit.length() > 8_000_000 || !kit.startsWith(PREFIX)) throw new IllegalArgumentException();
            byte[] payload = Base64.getDecoder().decode(kit.substring(PREFIX.length()));
            if (payload.length < 44) throw new IllegalArgumentException();
            byte[] plain = cipher(Cipher.DECRYPT_MODE, password,
                    Arrays.copyOfRange(payload, 0, 16), Arrays.copyOfRange(payload, 16, 28))
                    .doFinal(Arrays.copyOfRange(payload, 28, payload.length));
            Map<String, String> mapping = JSON.readValue(plain, new TypeReference<LinkedHashMap<String, String>>() {});
            if (mapping.size() > 20000 || mapping.entrySet().stream().anyMatch(e ->
                    !SensitiveTextEngine.TOKEN.matcher(e.getKey()).matches() || e.getValue() == null)) throw new IllegalArgumentException();
            return mapping;
        } catch (Exception e) {
            // Never reflect parsing errors or decrypted material into responses/logs.
            throw new IllegalArgumentException("复敏文件无效、已损坏或密码不正确");
        }
    }

    private static Cipher cipher(int mode, String password, byte[] salt, byte[] iv) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 210_000, 256);
        byte[] key;
        try { key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        try { cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv)); }
        finally { Arrays.fill(key, (byte) 0); }
        cipher.updateAAD(PREFIX.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
