package com.localgov.web.security;

import com.localgov.domain.model.UserAccount;
import com.localgov.repository.UserAccountRepository;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;

@Service
public class MfaService {

    private static final long TIME_STEP_SECONDS = 30L;
    private static final int CODE_DIGITS = 6;

    private final UserAccountRepository userAccountRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public MfaService(UserAccountRepository userAccountRepository) {
        this.userAccountRepository = userAccountRepository;
    }

    public boolean isMfaEnabled(String username) {
        return userAccountRepository.findByUsernameIgnoreCase(username)
                .map(UserAccount::getMfaEnabled)
                .filter(Boolean.TRUE::equals)
                .isPresent(); 
    }

    public boolean verifyCode(String username, String code) {
        if (code == null || !code.trim().matches("\\d{6}")) {
            return false;
        }

        return userAccountRepository.findByUsernameIgnoreCase(username)
                .map(UserAccount::getMfaSecret)
                .filter(secret -> secret != null && !secret.isBlank())
                .map(secret -> matchesCode(secret, code.trim()))
                .orElse(false);
    }

    public void recordSuccessfulLogin(String username) {
        userAccountRepository.findByUsernameIgnoreCase(username).ifPresent(account -> {
            account.setLastLoginAt(LocalDateTime.now());
            userAccountRepository.save(account);
        });
    }

    public String ensureSecret(String username) {
        UserAccount account = userAccountRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new IllegalArgumentException("User account not found: " + username));

        if (account.getMfaSecret() == null || account.getMfaSecret().isBlank()) {
            account.setMfaSecret(generateSecret());
            userAccountRepository.save(account);
        }
        return account.getMfaSecret();
    }

    private String generateSecret() {
        byte[] bytes = new byte[20];
        secureRandom.nextBytes(bytes);
        return Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean matchesCode(String secret, String code) {
        long counter = Instant.now().getEpochSecond() / TIME_STEP_SECONDS;
        for (long offset = -1; offset <= 1; offset++) {
            if (generateCode(secret, counter + offset).equals(code)) {
                return true;
            }
        }
        return false;
    }

    private String generateCode(String secret, long counter) {
        try {
            byte[] key = Base64.getDecoder().decode(secret);
            byte[] data = ByteBuffer.allocate(8).putLong(counter).array();

            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);

            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);

            int otp = binary % (int) Math.pow(10, CODE_DIGITS);
            return String.format("%0" + CODE_DIGITS + "d", otp);
        } catch (Exception exception) {
            return "";
        }
    }
}
