# 通知日历同步

![应用图标](app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp)

通过监听来自工作、即时通讯或其他应用的通知内容，自动识别会议、活动、截止时间等日程信息，并写入 Android 系统日历。

[![GitHub repo size](https://img.shields.io/github/repo-size/stevezmtstudios/calsync?style=flat-square)](https://github.com/stevezmtstudios/calsync)
[![GitHub release](https://img.shields.io/github/v/release/stevezmtstudios/calsync?style=flat-square)](https://github.com/stevezmtstudios/calsync/releases)
[![GitHub issues](https://img.shields.io/github/issues/stevezmtstudios/calsync?style=flat-square)](https://github.com/stevezmtstudios/calsync/issues)
[![GitHub license](https://img.shields.io/github/license/stevezmtstudios/calsync?style=flat-square)](LICENSE)

> [!WARNING]
> 本项目包含经过人工审查的 AI 生成内容和人工编写修正内容。  
> 当前版本主要面向简体中文通知文本。

## 软件简介

通知日历同步是一款 Android 日程辅助工具。它可以从系统通知中提取标题、正文、长文本和消息样式内容，根据关键词和来源应用进行过滤，然后识别其中的日期、时间、标题和地点，最后自动创建日历事件。

本软件适合经常通过通知接收会议安排、工作通知、群消息提醒的用户。用户可以通过设置关键词、来源应用、解析引擎、目标日历和提醒时间，让常见通知自动沉淀为日历事件，减少手动录入。

## 核心功能

- 监听系统通知，提取可用通知文本。
- 按关键词过滤通知，减少无关消息进入解析流程。
- 支持选择指定来源应用。
- 自动识别日期、时间、标题和地点。
- 将识别结果写入 Android 系统日历。
- 支持配置事件提醒时间。
- 创建成功后发送确认通知。
- 支持从通知中打开或删除已创建事件。
- 提供模拟通知测试，便于验证解析效果。
- 支持后台保活和通知监听服务刷新。

## 解析能力

软件支持多种解析方式：

- 内置规则解析：速度快，适合常见中文时间表达。
- TimeNLP / xk-time：辅助处理更复杂的中文时间表达。
- ML Kit：Full 版本可使用 Google ML Kit 实体提取能力。
- 本地 GGUF 模型：可使用本地模型进行实验性解析。
- 外部 AI API：Full 版本支持 OpenAI 兼容接口，如 DeepSeek、Kimi 等。

外部 AI 模式下，AI 主要负责判断通知是否需要生成日程、提取标题和地点。对于明确的时间表达，软件会优先使用本地确定性解析，减少 AI 直接生成时间戳不稳定的问题。

## 外部 AI 行为说明

当启用外部 AI API 后，软件会向用户配置的接口发送待解析文本和提示词。程序会区分以下结果：

- AI 成功解析并创建日程：通知中显示“日历已创建（外部 AI）”。
- AI 判断无需生成日程：通知中显示“外部 AI 已跳过创建日程”。
- AI 调用或解析失败：通知中显示“外部 AI 解析失败”，并说明失败原因。

这样用户可以清楚区分“AI 分析后决定不创建”和“网络、接口、返回格式错误导致失败”。

## 版本类型

本软件提供两个构建版本：

- FOSS 版本：不包含闭源 SDK，不申请网络权限，更适合注重隐私和离线使用的场景。
- Full 版本：包含 ML Kit 和外部 AI API 支持，需要网络权限，可获得更强的语义解析能力。

## 权限说明

软件可能使用以下权限：

- 日历读取和写入权限：用于列出日历并创建事件。
- 通知访问权限：用于监听通知内容，是核心功能所必需。
- 通知发送权限：用于显示创建成功、跳过创建或错误提示。
- 前台服务权限：用于在后台保持通知监听能力。
- 精确闹钟权限：用于部分提醒或恢复场景。
- 查询应用列表权限：用于让用户选择需要监听的来源应用。
- 网络权限：仅 Full 版本用于 ML Kit 或外部 AI API。

详情请查看 [隐私政策](POLICY.md)。

## 使用流程

1. 安装应用。
2. 首次启动后阅读并同意隐私提示。
3. 授予日历权限和通知访问权限。
4. 在设置中配置关键词、来源应用、目标日历和解析引擎。
5. 可通过“模拟通知测试”输入样例文本验证效果。
6. 后台收到匹配通知后，软件会自动解析，并根据结果创建日历事件或给出明确反馈。

## 示例

通知文本：

```text
后天上午9点到10点，市公司总经理开会
```

在当前日期为 2026-06-19 时，软件应解析为：

- 日期：2026-06-21
- 开始时间：09:00
- 结束时间：10:00
- 标题：由解析引擎或外部 AI 根据文本生成

## 构建项目

依赖环境：

- JDK 21
- Gradle 8.13+
- Android SDK API 23+

构建命令：

```bash
# 构建 FOSS 版本
./gradlew assembleFossRelease

# 构建 Full 版本，包含 ML Kit 和外部 AI 支持
./gradlew assembleFullRelease
```

## 技术信息

- 平台：Android
- 包名：`top.stevezmt.calsync`
- 最低系统版本：Android 6.0
- minSdk：23
- targetSdk：35
- 当前版本：`1.0.0`
- 构建类型：FOSS / Full
- 支持 ABI：`armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64`、`riscv64`

## 注意事项

- 通知监听能力受系统和厂商后台限制影响，部分设备需要额外设置自启动、电池优化和后台保活。
- 外部 AI API 的效果取决于用户配置的模型、提示词、网络状态和服务可用性。
- AI 功能属于实验性能力，结果可能不完全准确，重要日程建议人工复核。
- FOSS 版本无网络权限；Full 版本在启用相关能力时可能向外部服务发送待解析文本。

## 许可证

本项目基于 GPL-3.0 License 开源。详情见 [LICENSE](LICENSE)。
