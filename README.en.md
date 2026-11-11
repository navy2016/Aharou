<p align="center">
  <h1 align="center">Aharou</h1>
  <p align="center">
    AI assistant &amp; coding agent on Android · Built-in Linux environment
    <br />
    <a href="README.md">中文</a> · <a href="README.en.md">English</a>
  </p>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-blue.svg" alt="License GPL-3.0" /></a>
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Android" />
  <img src="https://img.shields.io/badge/Language-Kotlin-purple.svg" alt="Kotlin" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4.svg" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/MinSDK-26-orange.svg" alt="Min SDK 26 (Android 8.0)" />
  <a href="https://github.com/520huxiangli/Aharou/releases"><img src="https://img.shields.io/github/v/release/520huxiangli/Aharou?display_name=tag&include_prereleases" alt="Upstream Release" /></a>
  <a href="https://github.com/520huxiangli/Aharou/releases"><img src="https://img.shields.io/github/downloads/520huxiangli/Aharou/total" alt="Upstream Downloads" /></a>
</p>

---

## Download

- **Upstream releases**: download `Aharou-*.apk` from the upstream [Releases](https://github.com/520huxiangli/Aharou/releases/latest), allow "install unknown apps", and install. Enhancements in this repository require a build from this repository; upstream APKs may not include every change.
- **Development builds**: select a successful [CI](.github/workflows/ci.yml) run in this repository's Actions tab, download `Aharou-universal-debug-<commit>` from Artifacts, extract it, and install `app-universal-debug.apk`. Artifact downloads require a GitHub login. The Debug package, `com.aharou.agent.debug`, can coexist with the release app and keeps separate data. Automatic Beta publishing remains disabled.
- Supports Android 8.0+ on arm64-v8a / x86_64 devices, with no root required. Open the terminal first to initialize the built-in Linux environment.
- Configure your own chat models, keys, and endpoints. No chat model or provider key is bundled; offline speech recognition and local voice-cloning models require separate downloads.

## About

Aharou brings an AI assistant, coding agent, and Linux environment to your phone: a built-in Alpine container with a multi-tab terminal, file operations, commands, and builds. Workspaces and task execution can also use a remote SSH server.

This repository follows upstream Aharou while strengthening reasoning configuration, custom requests, automatic retries, and chat tool access. Local tasks run in the phone's container; remote tasks run on the selected server. AI conversations send requests to your configured model service, so not every feature is offline.

## Features

- **AI chat and coding** — multiple providers / models, Markdown, images, and reasoning display; open the terminal from the input bar and inspect context usage
- **Reasoning and service tiers** — seven effort levels, per-model parameter mappings, redacted previews, and override sources; level-based parameters preserve `xhigh` / `max`, while OpenAI providers offer independent service tiers whose support and cost depend on the endpoint
- **Three-level custom requests** — encrypted headers and JSON bodies at global / provider / model scope; fixed JSON overrides generated parameters last, with consistent protocol selection across chat, previews, and model tests
- **Retries and streaming reliability** — 0–100 additional retries, backoff, status codes, and stop keywords; streams are not replayed after output starts, and gateways that report completion without `[DONE]` are supported
- **Built-in Linux and remote SSH** — Alpine (PRoot) with a multi-tab terminal; preinstalled images include basic tools, Node.js, and Python, with custom images, host mounts, and mirror speed tests. App upgrades do not replace existing containers
- **Browser and file previews** — agent-driven browser plus image / text / Markdown / audio / video / PDF / APK / archive and self-contained HTML previews
- **Live tool visualization** — argument progress, tool-group activity summaries, terminal output, page snapshots, and shadow-screen streams; side-effecting tool calls have in-process duplicate-call protection
- **Shadow screen** — a headless virtual display where the agent can run, capture, and control apps without occupying the current foreground interface
- **Speech input and playback** — hold the microphone to transcribe into a draft, release to finish without sending, or tap for voice calls; reply read-aloud and local voice cloning are supported. Explicit cloud voice sync uploads reference audio and text using a SiliconFlow-style API
- **Messaging bots** — WeChat / Feishu / QQ / Telegram; personal QQ runs NapCat and Linux QQ in a separate Debian environment, preserving the development container and supporting login recovery and private / group chat policies
- **Plugins and extensions** — MCP, skills, knowledge bases, connectors, and sub-agents; successfully installed plugins with an MCP entry can expose tools to the agent, with service and MCP connection lifecycles managed separately
- **File saving and backups** — editor save-conflict detection, remote write-error reporting, safe SFTP replacement, and compensating recovery for backup restores
- **Config channel** — agent-accessible settings with write confirmation and auditing; revertible settings can restore earlier values
- **Personality (SOUL.md)** — name / icon / language / persona text, effective immediately in chat
- **Shizuku support** — system commands with adb shell privileges without root; access to other apps' private directories still requires root
- **Environment variables and storage** — encrypted custom variables injected into container commands and terminals; usage and cleanup by category, plus themes and backgrounds

## User guides

The detailed guides below are currently in Chinese.

- [Models, reasoning configuration, and custom requests](docs-site/docs/guide/providers.md)
- [Chat, retries, and context](docs-site/docs/guide/chat.md)
- [Containers and images](docs-site/docs/guide/container.md) · [Remote SSH](docs-site/docs/guide/remote-ssh.md)
- [Speech input, calls, and voices](docs-site/docs/guide/voice.md) · [Messaging bots](docs-site/docs/guide/im-bots.md)
- [Plugins and extensions](docs-site/docs/guide/extensions.md) · [File management](docs-site/docs/guide/files.md) · [Backup and restore](docs-site/docs/guide/backup.md)

## Screenshots

| Chat | Settings | Environment |
| :---: | :---: | :---: |
| ![Chat](docs/screenshots/aharou-chat.png) | ![Settings](docs/screenshots/aharou-settings.png) | ![Environment](docs/screenshots/aharou-tools.png) |

## License

- Released under [GPL-3.0](LICENSE).
- This project is derived from AiCode: portions are Copyright (C) 2026 AiCode contributors, and new or modified portions are Copyright (C) 2026 Aharou contributors.
- This project uses several open-source components; their copyright and license notices are preserved in [LICENSE](LICENSE) and the related NOTICE files.

## Feedback

- **This repository**: report issues or submit pull requests here for changes maintained in this repository. Include reproduction steps, device model, OS version, and the build version you are using.
- **Upstream community**: QQ group **141882781**
- **Upstream feedback**: [Issues](https://github.com/520huxiangli/Aharou/issues) · [Pull Requests](https://github.com/520huxiangli/Aharou/pulls)
