<p align="center">
  <h1 align="center">Aharou</h1>
  <p align="center">
    手机上的 AI 助手 · Coding Agent · 内置 Linux 环境
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

## 下载安装

- **上游正式版**：在上游 [Releases](https://github.com/520huxiangli/Aharou/releases/latest) 下载 `Aharou-*.apk`，允许「安装未知应用」后安装。本仓库的增强功能以本仓库构建为准，上游安装包不一定包含全部改动。
- **本仓库开发版**：在本仓库 Actions 中选择成功的 [CI](.github/workflows/ci.yml) 运行，从 Artifacts 下载 `Aharou-universal-debug-<commit>`，解压后安装 `app-universal-debug.apk`。下载需要登录 GitHub；Debug 包名为 `com.aharou.agent.debug`，可与正式版并存，数据相互独立。自动 Beta 发布保持停用。
- 支持 Android 8.0+ 的 arm64-v8a / x86_64 设备，无需 root。首次使用请先进入终端，完成内置 Linux 环境初始化。
- 对话模型、密钥和端点由你自行配置，不内置对话模型或服务商密钥；离线语音识别和本地声音克隆模型需另行下载。

## 简介

Aharou 把 AI 助手、Coding Agent 与 Linux 环境装进手机：内置 Alpine 容器和多标签终端，Agent 能读写文件、执行命令和运行构建，也可以把工作区与执行后端切到远程 SSH。

本仓库在同步上游功能的基础上，加强了模型推理配置、请求定制、自动重试和聊天工具入口。本地任务在手机容器中执行，远程任务在所选服务器上执行；AI 对话会向你配置的模型服务发送请求，不代表所有功能都离线运行。

## 功能

- **AI 对话与编程** — 多供应商 / 多模型，Markdown、图片和思考过程展示；输入栏可直接打开终端，查看上下文用量明细
- **推理与服务档位** — 七档思考强度、模型级参数映射、脱敏预览及覆盖来源；等级型参数保留 `xhigh` / `max` 原值，OpenAI 类型可独立设置服务档位，支持与费用以渠道为准
- **三级请求定制** — 全局 / 供应商 / 模型分别设置请求头和 JSON 请求体，加密保存；固定 JSON 最后覆盖生成参数，聊天、预览与模型测试保持协议选择一致
- **重试与流式可靠性** — 可配置 0–100 次额外重试、退避、状态码及停止关键字；已开始输出的流不会重发，兼容已收到结束原因但没有 `[DONE]` 的中转
- **内置 Linux 与远程 SSH** — Alpine（PRoot）+ 多标签终端；预装版提供基础工具、Node.js 和 Python，支持自定义镜像、挂载宿主目录与测速选源，升级应用不会替换已有容器
- **浏览器与文件预览** — Agent 可驱动内置浏览器；支持图片 / 文本 / Markdown / 音视频 / PDF / APK / 压缩包及自包含 HTML 预览
- **工具执行可视化** — 参数写入进度、工具组活动摘要、子代理批次卡片、终端逐行输出、网页快照与影子屏直播；有副作用的工具调用支持进程内重复调用保护
- **影子屏** — 无头虚拟显示屏，Agent 可在独立显示屏里运行、截图和操作 App，不占用当前前台界面
- **语音输入与朗读** — 长按麦克风转文字到草稿，松手结束、不自动发送；支持双方语音条、独立音色、全屏通话、静音与独立背景。配置云端识别后会上传整句录音，失败回退本机；主动同步云端音色会上传参考音频及文本
- **机器人接入** — 微信 / 飞书 / QQ / Telegram / 钉钉 / 企业微信，支持主动投递、发送结果三态及会话命令；个人 QQ 通过 NapCat 与 Linux QQ 运行在独立 Debian 环境，不替换开发容器，支持登录恢复与私聊 / 群聊策略
- **插件与扩展** — MCP、技能、知识库、连接器与子代理；安装成功且声明 MCP 入口的插件可直接向 Agent 提供工具，常驻服务与 MCP 连接独立管理
- **文件保存与备份** — 编辑器保存冲突检测、远程写入失败反馈、SFTP 安全替换及备份恢复补偿
- **配置通道** — Agent 可以修改自身设置；写操作经确认与审计，可回滚的设置支持恢复旧值
- **人格与记忆** — SOUL.md 名字 / 图标 / 语言 / 人格正文即时生效；记忆支持禁谈提示、事实日期与整理提醒
- **Shizuku 支持** — 无需 root 即可用 adb shell 权限执行系统命令；访问其他应用私有目录仍需 root
- **环境变量与存储** — 自定义变量加密保存并注入容器命令和终端；按类别查看与清理存储占用，支持主题与背景

## 使用文档

- [模型、推理配置与自定义请求](docs-site/docs/guide/providers.md)
- [聊天、重试与上下文](docs-site/docs/guide/chat.md)
- [容器与镜像](docs-site/docs/guide/container.md) · [远程 SSH](docs-site/docs/guide/remote-ssh.md)
- [语音输入、通话与音色](docs-site/docs/guide/voice.md) · [机器人接入](docs-site/docs/guide/im-bots.md)
- [插件与扩展](docs-site/docs/guide/extensions.md) · [文件管理](docs-site/docs/guide/files.md) · [备份与恢复](docs-site/docs/guide/backup.md)

## 截图

| 聊天 | 设置 | 运行环境 |
| :---: | :---: | :---: |
| ![聊天](docs/screenshots/aharou-chat.png) | ![设置](docs/screenshots/aharou-settings.png) | ![运行环境](docs/screenshots/aharou-tools.png) |

## 开源说明

- 本项目以 [GPL-3.0](LICENSE) 协议开源。
- 本项目基于 AiCode 改造：部分代码版权归 AiCode contributors，新增与改动部分版权归 Aharou contributors。
- 项目开发中使用了若干开源组件；其版权与许可声明见 [LICENSE](LICENSE) 与相关 NOTICE 文件。

## 反馈与贡献

- **本仓库反馈与贡献**：针对本仓库改动，请在当前仓库提交 Issue 或 Pull Request；问题请附复现步骤、设备型号、系统版本及使用的构建版本。
- **上游交流群**：QQ 群 **141882781**
- **上游反馈**：[Issues](https://github.com/520huxiangli/Aharou/issues) · [Pull Requests](https://github.com/520huxiangli/Aharou/pulls)
