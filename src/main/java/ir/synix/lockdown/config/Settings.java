package ir.synix.lockdown.config;

import java.util.List;

/**
 * Typed runtime representation of values loaded from config.yml.
 */
public final class Settings {

    public boolean enabled;
    public boolean debug;

    public Discord discord = new Discord();
    public Smtp smtp = new Smtp();
    public Guardians guardians = new Guardians();
    public Confirmation confirmation = new Confirmation();
    public Lockdown lockdown = new Lockdown();
    public Scan scan = new Scan();
    public Audit audit = new Audit();
    public Mongodb mongodb = new Mongodb();

    public static final class Discord {
        public String webhook = "";
        public String username = "LockDown";
        public String avatarUrl = "";
        public boolean enabled() {
            return webhook != null && !webhook.isBlank();
        }
    }

    public static final class Smtp {
        public String host = "";
        public int port = 587;
        public String username = "";
        public String password = "";
        public String encryption = "starttls";
        public String from = "lockdown@example.com";
        public String fromName = "LockDown";
        public boolean enabled() {
            return host != null && !host.isBlank();
        }
    }

    public static final class Guardians {
        public List<String> emails = List.of();
    }

    public static final class Confirmation {
        public int codeLength = 6;
        public int timeoutSeconds = 60;
        public int maxAttempts = 3;
        public String alphabet = "digits";
    }

    public static final class Lockdown {
        public List<String> unlockers = List.of();
        public String mode = "all";
        public List<String> commands = List.of();
        public boolean consoleBypass = true;
        public boolean bypassPermission = false;
        public boolean requireCode = true;
    }

    public static final class Scan {
        public boolean safeMerge = true;
        public String defaultPluginFile = "Minecraft";
    }

    public static final class Audit {
        public String file = "logs/lockdown.log";
        public int bufferSize = 256;
    }

    public static final class Mongodb {
        public boolean enabled = false;
        public String uri = "mongodb://localhost:27017";
        public String database = "lockdown";
        public String collection = "logs";
        public boolean enabled() {
            return enabled;
        }
    }
}
