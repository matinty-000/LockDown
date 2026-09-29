package ir.synix.lockdown.notify;

import ir.synix.lockdown.LockDownPlugin;
import ir.synix.lockdown.config.Settings;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.bukkit.Bukkit;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends LockDown notifications through the configured SMTP server.
 */
public final class SmtpNotifier implements Notifier {

    private final LockDownPlugin plugin;
    private final Settings settings;
    private final Logger logger;

    public SmtpNotifier(LockDownPlugin plugin, Settings settings, Logger logger) {
        this.plugin = plugin;
        this.settings = settings;
        this.logger = logger;
    }

    @Override
    public String name() {
        return "Email";
    }

    @Override
    public boolean enabled() {
        return settings.smtp.enabled() && !settings.guardians.emails.isEmpty();
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    @Override
    public void send(String subject, String body) {
        if (!enabled()) return;
        Runnable task = () -> {
            try {
                Session session = newSession();
                MimeMessage msg = new MimeMessage(session);
                String fromName = settings.smtp.fromName == null || settings.smtp.fromName.isBlank()
                        ? "LockDown" : settings.smtp.fromName;
                msg.setFrom(new InternetAddress(settings.smtp.from, fromName));
                msg.setSubject(subject == null ? "LockDown" : subject, "UTF-8");

                String htmlContent = renderHtml(subject, body);
                msg.setContent(htmlContent, "text/html; charset=UTF-8");

                msg.setHeader("X-Mailer", "LockDown Security");
                msg.setHeader("X-Auto-Response-Suppress", "All");
                msg.setHeader("Precedence", "bulk");

                InternetAddress[] to = settings.guardians.emails.stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(s -> {
                            try { return new InternetAddress(s); } catch (Exception e) { return null; }
                        })
                        .filter(Objects::nonNull)
                        .toArray(InternetAddress[]::new);

                if (to.length == 0) return;
                msg.setRecipients(Message.RecipientType.TO, to);
                Transport.send(msg);
            } catch (Exception e) {
                logger.log(Level.WARNING, "[LockDown] SMTP delivery failed: " + e.getMessage());
            }
        };

        if (plugin != null && plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        } else {
            task.run();
        }
    }

    private String renderHtml(String subject, String text) {
        String code = null;
        Map<String, String> details = new LinkedHashMap<>();
        StringBuilder extraLines = new StringBuilder();
        if (text == null) text = "";

        String[] lines = text.split("\r?\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (code == null && trimmed.toLowerCase(Locale.ROOT).contains("code")) {
                int tick1 = trimmed.indexOf('`');
                if (tick1 != -1) {
                    int tick2 = trimmed.indexOf('`', tick1 + 1);
                    if (tick2 != -1) {
                        String possibleCode = trimmed.substring(tick1 + 1, tick2);
                        if (possibleCode.matches("^[a-zA-Z0-9]{3,16}$")) {
                            code = possibleCode;
                        }
                    }
                }
            }

            trimmed = trimmed.replace("**", "").replace("`", "");
            String safeTrimmed = escapeHtml(trimmed);

            if (trimmed.contains(":") && !trimmed.startsWith("http")) {
                int colonIdx = trimmed.indexOf(':');
                String key = escapeHtml(trimmed.substring(0, colonIdx).trim());
                String val = escapeHtml(trimmed.substring(colonIdx + 1).trim());
                if (!key.isEmpty() && !val.isEmpty() && key.length() < 30) {
                    details.put(key, val);
                    continue;
                }
            }

            if (safeTrimmed.startsWith("Player ")
                    && (safeTrimmed.contains("wants to run")
                    || safeTrimmed.contains("ran a watched command"))) {
                extraLines.append("<div class='alert-box'>");
                extraLines.append("<div class='alert-title'>").append(safeTrimmed).append("</div>");
                extraLines.append("</div>");
            } else {
                extraLines.append("<p style='font-size: 14px; line-height: 1.6; color: #4b5563; margin: 8px 0;'>")
                        .append(safeTrimmed)
                        .append("</p>");
            }
        }

        String safeSubject = escapeHtml(subject == null ? "LockDown" : subject);

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'>");
        html.append("<style>");
        html.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f3f4f6; margin: 0; padding: 20px; color: #1f2937; }");
        html.append(".card { max-width: 550px; margin: 30px auto; background: #ffffff; border-radius: 12px; box-shadow: 0 4px 20px rgba(0,0,0,0.08); overflow: hidden; border: 1px solid #e5e7eb; }");
        html.append(".header { background: #111827; padding: 24px; text-align: center; color: #ffffff; }");
        html.append(".header h2 { margin: 0; font-size: 20px; text-transform: uppercase; letter-spacing: 1.5px; font-weight: 700; color: #f9fafb; }");
        html.append(".body { padding: 32px; }");
        html.append(".alert-box { background: #fffbeb; border-left: 4px solid #f59e0b; padding: 16px; border-radius: 6px; margin-bottom: 24px; }");
        html.append(".alert-box.danger { background: #fef2f2; border-left-color: #ef4444; }");
        html.append(".alert-title { font-size: 15px; font-weight: 600; color: #78350f; line-height: 1.4; }");
        html.append(".alert-box.danger .alert-title { color: #991b1b; }");
        html.append(".code-card { background: #f9fafb; border: 2px dashed #e5e7eb; border-radius: 8px; padding: 20px; text-align: center; margin: 28px 0; }");
        html.append(".code-label { font-size: 11px; text-transform: uppercase; letter-spacing: 1px; color: #6b7280; margin-bottom: 6px; font-weight: 600; }");
        html.append(".code-val { font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace; font-size: 34px; font-weight: bold; color: #111827; letter-spacing: 4px; }");
        html.append(".table { width: 100%; border-collapse: collapse; margin-top: 24px; }");
        html.append(".table th, .table td { padding: 10px 12px; text-align: left; font-size: 14px; border-bottom: 1px solid #f3f4f6; }");
        html.append(".table th { color: #6b7280; font-weight: 500; width: 35%; }");
        html.append(".table td { color: #111827; font-weight: 600; }");
        html.append(".footer { background: #f9fafb; padding: 20px; text-align: center; font-size: 11px; color: #9ca3af; border-top: 1px solid #f3f4f6; }");
        html.append(".warning { color: #dc2626; font-weight: 600; margin-top: 8px; }");
        html.append("</style></head><body>");

        html.append("<div class='card'>");
        html.append("<div class='header'><h2>LockDown Security</h2></div>");
        html.append("<div class='body'>");

        String lowerSubject = subject == null ? "" : subject.toLowerCase(Locale.ROOT);
        String lowerText = text == null ? "" : text.toLowerCase(Locale.ROOT);
        boolean isDanger = lowerSubject.contains("failed")
                || lowerText.contains("authorized: false")
                || lowerText.contains("unauthorized: true");

        if (extraLines.length() > 0) {
            String cleanLines = extraLines.toString();
            if (!cleanLines.contains("class='alert-box'")) {
                html.append("<div class='alert-box ").append(isDanger ? "danger" : "").append("'>");
                html.append("<div class='alert-title'>").append(safeSubject).append("</div>");
                html.append("</div>");
            }
            html.append(cleanLines);
        } else {
            html.append("<div class='alert-box ").append(isDanger ? "danger" : "").append("'>");
            html.append("<div class='alert-title'>").append(safeSubject).append("</div>");
            html.append("</div>");
        }

        if (code != null) {
            html.append("<div class='code-card'>");
            html.append("<div class='code-label'>CONFIRMATION CODE</div>");
            html.append("<div class='code-val'>").append(code).append("</div>");
            html.append("</div>");
        }

        if (!details.isEmpty()) {
            html.append("<table class='table'>");
            for (Map.Entry<String, String> entry : details.entrySet()) {
                html.append("<tr><th>").append(entry.getKey()).append("</th><td>").append(entry.getValue()).append("</td></tr>");
            }
            html.append("</table>");
        }

        html.append("</div>");
        html.append("<div class='footer'>");
        html.append("This is an automated notification from your Minecraft Server Security System.<br>");
        html.append("<div class='warning'>NEVER share this confirmation code in-game or with untrusted players.</div>");
        html.append("</div>");
        html.append("</div>");
        html.append("</body></html>");

        return html.toString();
    }

    private Session newSession() {
        Properties p = new Properties();
        String host = settings.smtp.host;
        int port = settings.smtp.port;
        String enc = settings.smtp.encryption == null ? "starttls" : settings.smtp.encryption.toLowerCase(Locale.ROOT);

        p.put("mail.smtp.host", host);
        p.put("mail.smtp.port", String.valueOf(port));
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.timeout", "15000");
        p.put("mail.smtp.connectiontimeout", "15000");
        p.put("mail.smtp.writetimeout", "15000");

        switch (enc) {
            case "ssl" -> {
                p.put("mail.smtp.ssl.enable", "true");
                p.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            }
            case "starttls" -> p.put("mail.smtp.starttls.enable", "true");
            default -> { }
        }

        String user = settings.smtp.username;
        String pass = settings.smtp.password;
        if (user == null || user.isBlank()) {
            return Session.getInstance(p);
        }
        return Session.getInstance(p, new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, pass);
            }
        });
    }
}
