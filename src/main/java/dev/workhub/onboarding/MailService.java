package dev.workhub.onboarding;

import static dev.workhub.common.Ids.*;

import dev.workhub.messaging.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.util.*;

import javax.crypto.*;
import javax.crypto.spec.*;

@Service
public class MailService {
    private final JdbcTemplate db;
    private final BusinessJobs jobs;
    private final byte[] key;
    private final String base;

    public MailService(
            JdbcTemplate db,
            BusinessJobs jobs,
            @Value("${workhub.mail.key-file:.tools/mail.key}") String path,
            @Value("${workhub.public-url:http://127.0.0.1:18080}") String base)
            throws Exception {
        this.db = db;
        this.jobs = jobs;
        this.base = base;
        Path p = Path.of(path);
        if (!Files.exists(p)) {
            Files.createDirectories(p.toAbsolutePath().getParent());
            byte[] k = new byte[32];
            new java.security.SecureRandom().nextBytes(k);
            try {
                Files.write(p, k, StandardOpenOption.CREATE_NEW);
                Files.setPosixFilePermissions(
                        p, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (FileAlreadyExistsException ignored) {
            }
        }
        key = Files.readAllBytes(p);
        if (key.length != 32)
            throw new IllegalArgumentException("Mail encryption key must contain 32 bytes");
    }

    public void send(String email, String purpose, String token) {
        send(email, purpose, token, null);
    }

    public void send(String email, String purpose, String token, String invitationToken) {
        UUID id = UUID.randomUUID();
        String text =
                "Work Hub\n\n"
                        + (purpose.equals("verify")
                                ? "请验证您的邮箱（24小时有效）"
                                : purpose.equals("reset")
                                        ? "请重置您的密码（30分钟有效）"
                                        : "您收到了一份团队邀请（7天有效）。已有账号请登录；尚未注册请用收件邮箱注册并验证。")
                        + "\n\n"
                        + base
                        + "/#"
                        + purpose
                        + "/"
                        + token
                        + (invitationToken == null
                                ? ""
                                : "?invitation="
                                        + java.net.URLEncoder.encode(
                                                invitationToken,
                                                java.nio.charset.StandardCharsets.UTF_8))
                        + "\n\n如果不是您发起，请忽略此邮件。链接只能使用一次。";
        db.update(
                "INSERT INTO mail_delivery(id,recipient,encrypted_body) VALUES (?,?,?)",
                bytes(id),
                email,
                encrypt(text));
        jobs.enqueue("MAIL_DELIVERY", null, id, "mail/" + id, Map.of());
    }

    private String encrypt(String text) {
        try {
            byte[] iv = new byte[12];
            new java.security.SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] ciphertext = c.doFinal(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv)
                    + ":"
                    + Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String decrypt(String text) {
        try {
            String[] parts = text.split(":");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            return new String(
                    c.doFinal(Base64.getDecoder().decode(parts[1])),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Mail decryption failed", e);
        }
    }
}
