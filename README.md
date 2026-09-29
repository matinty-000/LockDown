# LockDown

LockDown is a command-security plugin for Paper servers. It intercepts sensitive commands, evaluates configurable security rules, records audit events, sends external alerts, requires out-of-game confirmation when configured, and can place the server into a global command lockdown.

The project targets **Java 21** and is compiled against **Paper 1.21.11**.

## Features

- Rule-based command interception for players and server command senders.
- Tokenized command patterns with `*` and `**` wildcards.
- Separate action plans for authorized and unauthorized command senders.
- Configurable actions: audit logging, notification, blocking, confirmation, and freezing.
- Global command lockdown with `all`, `blocklist`, and `allowlist` modes.
- External confirmation through Discord webhooks or SMTP email.
- Optional per-player TOTP confirmation for authenticator applications.
- Encrypted storage for TOTP secrets and persisted confirmation codes.
- Persistent global-lockdown, frozen-player, and pending-confirmation state.
- Command scanner that generates per-plugin rule files under `commands/`.
- In-game GUI for recent audit events, rules, and runtime status.
- Asynchronous audit-file writes and asynchronous external notification I/O.
- One-time command-release bypasses so an approved command is not intercepted recursively.

## Requirements

- Java 21
- Paper 1.21.11 (the current compile target; `plugin.yml` declares API version `1.21`)
- A Gradle installation for building this source snapshot

> The repository snapshot contains `gradlew`/`gradlew.bat`, but does not contain the `gradle/wrapper/gradle-wrapper.jar` wrapper binary. Use a local Gradle installation, or restore the standard Gradle wrapper files before using `./gradlew`.

## Building

Clone the repository and run:

```bash
gradle shadowJar
```

The shaded plugin JAR is produced under:

```text
build/libs/
```

The build shades the Jakarta Mail implementation used by SMTP notifications. Paper is a `compileOnly` dependency and is provided by the server at runtime.

## Installation

1. Build the shaded JAR.
2. Copy it into the Paper server's `plugins/` directory.
3. Start the server once to generate LockDown's configuration files.
4. Configure notification delivery and security rules.
5. Run `/lockdown reload` after configuration changes, or restart the server.

LockDown creates and uses the following runtime files:

```text
plugins/LockDown/
├── config.yml
├── messages.yml
├── rules.yml
├── auth.yml
├── state.yml
├── secret.key
├── commands/
│   └── <Plugin>.yml
└── logs/
    └── lockdown.log
```

`auth.yml`, `state.yml`, and `secret.key` are runtime state files and are not bundled as resources.

## Commands

The primary command is `/lockdown`, with aliases `/ld` and `/lockd`.

| Command | Purpose |
| --- | --- |
| `/lockdown help` | Show commands available to the sender. |
| `/lockdown reload` | Reload configuration, notification backends, rules, and audit settings. |
| `/lockdown scan [--force]` | Scan registered server commands and generate/update `commands/*.yml`. |
| `/lockdown gui` | Open the LockDown control GUI. |
| `/lockdown rules` | Print the currently loaded rules. |
| `/lockdown lock` | Request or immediately engage global command lockdown, depending on configuration. |
| `/lockdown unlock` | Request or immediately release global command lockdown. |
| `/lockdown unfreeze <player>` | Request or immediately perform an administrative unfreeze. |
| `/lockdown status` | Show LockDown runtime status. |
| `/lockdown confirm <code>` | Submit the confirmation code/TOTP for the sender's pending action. |
| `/lockdown resend` | Refresh a pending confirmation and resend its external notification where applicable. |
| `/lockdown auth setup` | Generate a new TOTP secret for the player. |
| `/lockdown auth verify <code>` | Verify and activate a pending TOTP setup. |
| `/lockdown auth disable <code>` | Verify a TOTP code and disable the player's authenticator. |

## Permissions

The plugin declares these permissions in `plugin.yml`:

| Permission | Default | Description |
| --- | --- | --- |
| `lockdown.admin` | OP | Access to administrative `/lockdown` subcommands. |
| `lockdown.confirm` | Everyone | Declared in plugin metadata. The current command handler does not explicitly gate `/lockdown confirm` with this node. |
| `lockdown.bypass` | OP | Bypass normal command rules; global-lockdown bypass depends on configuration. |

Global lockdown intentionally treats bypass behavior separately from ordinary rule bypassing. See `lockdown.bypass_permission` below.

## Configuration

### Main switch

```yaml
enabled: true
debug: false
```

- `enabled`: master switch for command interception.
- `debug`: enables additional audit messages in the server log.

### Discord

```yaml
discord:
  webhook: ""
  username: "LockDown"
  avatar_url: ""
```

Discord delivery is enabled when `discord.webhook` is non-empty. Webhook requests use the JDK HTTP client asynchronously.

### SMTP

```yaml
smtp:
  host: ""
  port: 587
  username: ""
  password: ""
  encryption: "starttls"
  from: "lockdown@example.com"
  from_name: "LockDown"
```

SMTP delivery is enabled when `smtp.host` is non-empty. Supported encryption values are:

- `none`
- `starttls`
- `ssl`

Recipients are configured under:

```yaml
guardians:
  emails: []
```

### Confirmation codes

```yaml
confirmation:
  code_length: 6
  timeout_seconds: 60
  max_attempts: 3
  alphabet: digits
```

`alphabet` accepts `digits` or `alphanumeric`. TOTP confirmations always use the player's authenticator code rather than generated confirmation codes.

### Global command lockdown

```yaml
lockdown:
  unlockers: []
  mode: all
  commands: []
  console_bypass: true
  bypass_permission: false
  require_code: true
```

Supported modes:

- `all` — block every command while global lockdown is active.
- `blocklist` — block only commands matching `lockdown.commands`.
- `allowlist` — block every command except those matching `lockdown.commands`.

`denylist` and `whitelist` are also accepted internally as aliases for `blocklist` and `allowlist`.

A plain lockdown entry matches that command and its arguments. For example, `op` matches both `op` and `op Player`. Wildcard entries use the same token matcher as normal rules.

`console_bypass` applies only to the real server console. `bypass_permission` controls whether holders of `lockdown.bypass` may also bypass an active global lockdown.

`unlockers` is loaded as administrative ownership/reference data. In the current implementation it does not independently bypass the global command policy.

### Scanner

```yaml
scan:
  safe_merge: true
  default_plugin_file: "Minecraft"
```

- `safe_merge: true` preserves customized generated entries and only adds newly discovered commands.
- `/lockdown scan --force` regenerates generated command files from scratch.
- Commands that cannot be attributed to a plugin are grouped using `default_plugin_file`.

### Audit log

```yaml
audit:
  file: "logs/lockdown.log"
  buffer_size: 256
```

Audit writes are performed asynchronously while the plugin is enabled. `buffer_size` controls how many recent entries remain in memory for the GUI.

The audit path is normalized under the LockDown data directory; unsafe paths that escape that directory are rejected and fall back to `logs/lockdown.log`.

## Rules

Manual rules live in `rules.yml`. Generated rules live in `commands/<Plugin>.yml`.

A rule contains:

```yaml
rules:
  - id: custom_lp_permission_block
    enabled: true
    permission: "luckperms.admin"
    patterns:
      - "lp user * permission **"
      - "luckperms user * permission **"
    on_authorized:
      log: true
      notify: true
      require_confirmation: true
    on_unauthorized:
      log: true
      notify: true
      block: true
```

### Pattern syntax

Patterns are split into command tokens and matched case-insensitively.

- Literal token — must match exactly.
- `*` — matches exactly one token.
- `**` — matches one or more tokens.

Examples:

```text
lp user * parent
```

matches:

```text
lp user Steve parent
```

but not:

```text
lp user Steve parent set admin
```

This pattern:

```text
lp user * permission **
```

matches commands such as:

```text
lp user Steve permission set example.node true
lp user Alex permission unset example.node
```

The matcher also strips a namespace from the first token, so namespaced command labels are normalized for matching.

### Authorization

When a rule defines `permission`, LockDown checks that permission directly on the sender.

When no permission hint is configured, LockDown resolves the registered command and checks authorization through Bukkit's command permission behavior. Console senders and operators are treated as authorized by that fallback resolver.

### Action plans

The following action flags are supported:

```yaml
log: true
notify: true
block: true
require_confirmation: true
freeze: true
```

Rules may define separate plans under:

```yaml
on_authorized:
on_unauthorized:
on_failure:
```

`on_failure` is used after a confirmation expires or exceeds its allowed attempts.

Action flags interact as follows in the current implementation:

- `require_confirmation: true` cancels the original command and starts the confirmation flow.
- `block: true` together with `require_confirmation: true` blocks immediately instead of starting confirmation.
- `freeze: true` freezes a player sender and also prevents the matched command from executing.
- `notify: true` without confirmation sends the normal alert; confirmation flows send their own confirmation notification.
- If a confirmation is required but the sender has no TOTP secret and no notification backend is enabled, the command is blocked and no pending freeze/confirmation is created.

After successful confirmation, LockDown releases the original command with a one-use bypass so it does not immediately match the same interception path again.

## Command normalization

Before rule evaluation, LockDown normalizes command strings to reduce common matching bypasses. The current normalization includes:

- removing a leading `/`;
- collapsing repeated whitespace;
- removing command-label namespaces;
- repeatedly unwrapping supported `execute ... run ...` command prefixes;
- canonicalizing LockDown's own aliases back to `lockdown` for internal command handling.

## Confirmation flow

A confirmation-protected command follows this sequence:

1. The original command is cancelled.
2. A pending confirmation is registered for the original sender.
3. The player is frozen while confirmation is pending.
4. If the player has TOTP configured, the player's authenticator code is required.
5. Otherwise a random code is sent through configured external notification backends.
6. The sender submits `/lockdown confirm <code>`.
7. On success, the pending state is removed and the original command is released once.
8. On expiry or exhausted attempts, the rule's `on_failure` plan is applied.

Confirmations are bound to the sender that created them; a code is not used to locate another sender's pending request.

## TOTP authenticator security

Players can configure a personal authenticator with `/lockdown auth setup` and `/lockdown auth verify <code>`.

TOTP verification uses:

- Base32 secrets;
- HMAC-SHA1 TOTP;
- 30-second time steps;
- a ±1-step verification window;
- per-purpose replay protection for recently used codes.

Stored TOTP secrets are encrypted before being written to `auth.yml`.

## Secret storage

LockDown uses AES-GCM to encrypt sensitive persisted values such as TOTP secrets and pending generated confirmation codes.

The local AES key is stored in:

```text
plugins/LockDown/secret.key
```

Keep `secret.key` with the corresponding `auth.yml` and `state.yml` files when migrating a server. Losing the key can make encrypted persisted values unreadable.

## Frozen-player restrictions

While a player is frozen, LockDown blocks the relevant interactions handled by `FreezeListener`, including:

- movement and teleporting;
- chat;
- block interaction, breaking, and placing;
- inventory clicks, drags, and opens;
- item dropping, item pickup, held-slot changes, and hand swapping;
- dealing or receiving entity damage;
- entity interaction;
- commands other than LockDown's confirmation, resend, and authenticator path.

Frozen players receive the configured title/chat prompts, and pending confirmation state can be restored after a restart.

## Command scanner

`/lockdown scan` reads the server command map, groups commands by owning plugin, and writes generated rule definitions to:

```text
plugins/LockDown/commands/<Plugin>.yml
```

Generated rules are disabled by default. Their default templates are designed to be reviewed and explicitly enabled by an administrator.

When safe merge is enabled, existing customized rule entries are retained and new commands are added around them. `--force` discards that merge behavior for generated files and rebuilds them.

## GUI

`/lockdown gui` opens the main control interface. The current GUI provides navigation to:

- recent audit logs;
- loaded rules;
- runtime status.

The rules and logs screens are paginated. The GUI is primarily an inspection/status interface; rule editing remains configuration-file based.

## Persistence

`state.yml` persists security state required across restarts, including:

- global lockdown status;
- the lockdown engager UUID;
- frozen player UUIDs;
- pending confirmation metadata and attempt counts.

Pending generated codes and persisted TOTP-related confirmation data are encrypted before being written.

`auth.yml` stores encrypted per-player authenticator secrets.

The current build uses local file persistence. The settings model contains reserved MongoDB fields, but this version does not include MongoDB driver/runtime synchronization; if such settings are enabled externally, audit logging remains local and a warning is emitted.

## Project structure

```text
src/main/java/ir/synix/lockdown/
├── LockDownPlugin.java          # Plugin bootstrap and service wiring
├── admin/                       # /lockdown command handling
├── approval/                    # Confirmation codes, TOTP, pending approvals
├── audit/                       # Audit entries and asynchronous log writer
├── command/                     # Interception, command scan/index, authorization
├── config/                      # YAML loading and typed settings
├── gui/                         # Inventory GUI screens and navigation
├── lockdown/                    # Global lockdown, freeze enforcement, persistence
├── notify/                      # Discord, SMTP, and composite notification backends
├── rules/                       # Rule loading, matching, and runtime rule engine
│   └── model/                   # Rule/action models
└── util/                        # Colors, command-map access, JSON, crypto, time, TOTP
```

Resources:

```text
src/main/resources/
├── config.yml
├── messages.yml
├── plugin.yml
└── rules.yml
```

## Runtime architecture

At startup, `LockDownPlugin` wires the major services in this order:

1. configuration;
2. lockdown state manager;
3. audit service;
4. approval and authenticator services;
5. notification backends;
6. rule engine and command index;
7. freeze/interception/GUI/command listeners;
8. periodic confirmation-expiry purge;
9. persisted state restoration.

Normal command processing is intentionally layered:

```text
Incoming command
      │
      ▼
Normalization
      │
      ▼
Global lockdown policy
      │
      ▼
LockDown internal command handling / bypass checks
      │
      ▼
Rule matching
      │
      ▼
Authorization branch
      │
      ▼
Action plan
      │
      ├── log
      ├── notify
      ├── block
      ├── freeze
      └── confirmation → release-on-success
```

## Messages and colors

Player-facing messages are stored in `messages.yml`.

The message formatter supports:

- legacy `&` color codes;
- hex colors using `&#RRGGBB`;
- `%placeholder%` replacement;
- `{prefix}` insertion where configured.

## Operational notes

- Keep at least one recovery path available when using global lockdown. With `mode: all`, player commands are blocked; `console_bypass: true` provides console recovery.
- Test Discord/SMTP delivery before enabling confirmation requirements for critical administrative commands.
- Back up `secret.key` together with the encrypted YAML state files.
- Review generated command rules before enabling them.
- Prefer `safe_merge: true` if generated command files are customized manually.

## Source-code conventions

This repository includes an `.editorconfig` to keep Java/Kotlin source at four-space indentation, YAML at two-space indentation, UTF-8 encoding, LF line endings, and trailing-whitespace cleanup.

The codebase is organized by responsibility and avoids coupling configuration parsing, interception, persistence, notifications, and GUI rendering into the plugin bootstrap class.
