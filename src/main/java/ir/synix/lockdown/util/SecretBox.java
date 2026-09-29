package ir.synix.lockdown.util;

import ir.synix.lockdown.LockDownPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Small local secret wrapper so TOTP secrets and pending codes are not written as plain YAML. */
public final class SecretBox {
    private static final String PREFIX = "enc:v1:";
    private static final SecureRandom RNG = new SecureRandom();

    private SecretBox() {
    }

    public static String encrypt(LockDownPlugin plugin, String plain) {
        if (plain == null || plain.isEmpty()) return plain;
        if (plain.startsWith(PREFIX)) return plain;
        try {
            byte[] iv = new byte[12];
            RNG.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(plugin), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception ex) {
            if (plugin != null) plugin.getLogger().warning("[LockDown] Could not encrypt sensitive value: " + ex.getMessage());
            return plain;
        }
    }

    public static String decrypt(LockDownPlugin plugin, String value) {
        if (value == null || value.isEmpty()) return value;
        if (!value.startsWith(PREFIX)) return value; // old plaintext values are still readable and will migrate on next save
        try {
            byte[] all = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            if (all.length <= 12) return null;
            byte[] iv = Arrays.copyOfRange(all, 0, 12);
            byte[] encrypted = Arrays.copyOfRange(all, 12, all.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(plugin), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            if (plugin != null) plugin.getLogger().warning("[LockDown] Could not decrypt sensitive value: " + ex.getMessage());
            return null;
        }
    }

    private static SecretKey key(LockDownPlugin plugin) throws Exception {
        Path data = plugin.getDataFolder().toPath();
        Files.createDirectories(data);
        Path keyFile = data.resolve("secret.key");
        if (Files.exists(keyFile)) {
            byte[] raw = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.UTF_8).trim());
            return new SecretKeySpec(raw, "AES");
        }
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        try {
            generator.init(256);
        } catch (Exception ignored) {
            generator.init(128);
        }
        SecretKey key = generator.generateKey();
        Files.writeString(keyFile, Base64.getEncoder().encodeToString(key.getEncoded()), StandardCharsets.UTF_8);
        try {
            File f = keyFile.toFile();
            f.setReadable(false, false);
            f.setWritable(false, false);
            f.setReadable(true, true);
            f.setWritable(true, true);
        } catch (Exception ignored) { }
        return key;
    }
}
