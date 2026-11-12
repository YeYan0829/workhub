package dev.workhub.agent;

import dev.workhub.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AES-GCM, workspace-bound AAD. No generated fallback or plaintext persistence. */
@Component
public class ModelKeyCipher {
  private final byte[] master;

  public ModelKeyCipher(@Value("${workhub.team-model.master-key:}") String encoded) {
    try {
      master = encoded.isBlank() ? null : Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid team model encryption key");
    }
    if (master != null && master.length != 32)
      throw new IllegalArgumentException("Team model encryption key must be 32 bytes");
  }

  public String encrypt(UUID workspace, String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 4096
        || value.chars().anyMatch(Character::isISOControl)) throw ApiException.invalid("需要有效的模型密钥");
    byte[] nonce = new byte[12];
    new SecureRandom().nextBytes(nonce);
    return "v1."
        + Base64.getEncoder().encodeToString(nonce)
        + "."
        + Base64.getEncoder()
            .encodeToString(
                crypt(
                    Cipher.ENCRYPT_MODE, workspace, nonce, value.getBytes(StandardCharsets.UTF_8)));
  }

  public String decrypt(UUID workspace, String encoded) {
    try {
      var parts = encoded.split("\\.");
      if (parts.length != 3 || !parts[0].equals("v1")) throw new IllegalArgumentException();
      return new String(
          crypt(
              Cipher.DECRYPT_MODE,
              workspace,
              Base64.getDecoder().decode(parts[1]),
              Base64.getDecoder().decode(parts[2])),
          StandardCharsets.UTF_8);
    } catch (RuntimeException e) {
      throw unavailable();
    }
  }

  private byte[] crypt(int mode, UUID workspace, byte[] nonce, byte[] data) {
    if (master == null) throw unavailable();
    try {
      var cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, new SecretKeySpec(master, "AES"), new GCMParameterSpec(128, nonce));
      cipher.updateAAD(("workhub/team-model/" + workspace).getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(data);
    } catch (Exception e) {
      throw unavailable();
    }
  }

  private ApiException unavailable() {
    return new ApiException(503, "MODEL_KEY_UNAVAILABLE", "模型密钥加密配置不可用");
  }
}
