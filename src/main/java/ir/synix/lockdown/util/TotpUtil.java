package ir.synix.lockdown.util;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Generates Base32 TOTP secrets and verifies authenticator codes with replay protection.
 */
public final class TotpUtil {
    private static final String BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int[] BASE32_DECODE = new int[256];
    private static final Map<String, Long> USED_CODES = new ConcurrentHashMap<>();

    static {
        for (int i = 0; i < 256; i++) BASE32_DECODE[i] = -1;
        for (int i = 0; i < BASE32_CHARS.length(); i++) {
            BASE32_DECODE[BASE32_CHARS.charAt(i)] = i;
            BASE32_DECODE[Character.toLowerCase(BASE32_CHARS.charAt(i))] = i;
        }
    }

    private TotpUtil() {
    }

    public static String generateSecret() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[10];
        random.nextBytes(bytes);
        return encodeBase32(bytes);
    }

    public static String encodeBase32(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0, bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                sb.append(BASE32_CHARS.charAt((buffer >> bitsLeft) & 0x1F));
            }
        }
        if (bitsLeft > 0) {
            buffer <<= (5 - bitsLeft);
            sb.append(BASE32_CHARS.charAt(buffer & 0x1F));
        }
        return sb.toString();
    }

    public static byte[] decodeBase32(String base32) {
        if (base32 == null) return new byte[0];
        int buffer = 0, bitsLeft = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (char c : base32.toCharArray()) {
            if (c == ' ' || c == '-' || c == '=') continue;
            int val = c < 256 ? BASE32_DECODE[c] : -1;
            if (val == -1) return new byte[0];
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                out.write((buffer >> bitsLeft) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    public static boolean verify(String secret, String code) {
        return verify(secret, code, "default");
    }

    public static boolean verifyConfirmation(String secret, String code) {
        return verify(secret, code, "confirmation");
    }

    public static boolean verifyAuthenticatorChange(String secret, String code) {
        return verify(secret, code, "auth");
    }

    public static boolean verify(String secret, String code, String purpose) {
        if (code == null || code.length() != 6 || secret == null || secret.isBlank()) return false;
        if (!code.chars().allMatch(Character::isDigit)) return false;

        long now = System.currentTimeMillis();
        USED_CODES.entrySet().removeIf(entry -> now - entry.getValue() > 120000);

        String normalizedPurpose = purpose == null ? "default" : purpose.toLowerCase(Locale.ROOT);
        String uniqueKey = normalizedPurpose + ":" + secret + ":" + code;
        if (USED_CODES.containsKey(uniqueKey)) return false;

        try {
            long timeWindow = now / 30000;
            for (int i = -1; i <= 1; i++) {
                if (code.equals(generateCode(secret, timeWindow + i))) {
                    USED_CODES.put(uniqueKey, now);
                    return true;
                }
            }
            return false;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static String generateCode(String secret, long time) {
        byte[] key = decodeBase32(secret);
        if (key.length < 10) throw new IllegalArgumentException("Invalid TOTP secret");
        byte[] data = ByteBuffer.allocate(8).putLong(time).array();
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "RAW"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0xF;
            int truncatedHash = 0;
            for (int i = 0; i < 4; ++i) {
                truncatedHash = (truncatedHash << 8) | (hash[offset + i] & 0xFF);
            }
            truncatedHash &= 0x7FFFFFFF;
            truncatedHash %= 1000000;
            return String.format("%06d", truncatedHash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException(e);
        }
    }
}
