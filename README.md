# AIPlayer Companion

一个 Minecraft Java 版 Fabric Mod 第一版原型：生成一个类玩家 AI 伙伴，绑定主人，跟随、等待，并通过本机 LM Studio 的 OpenAI-compatible API 做简单聊天。

## 版本要求

- Java：21
- Minecraft：1.21.8
- Fabric Loader：0.17.2 或更高的 0.17.x
- Fabric API：0.136.1+1.21.8
- Gradle：项目自带 Gradle Wrapper，首次构建会自动下载 Gradle 8.14.3

## 编译

在项目目录运行：

```powershell
.\gradlew.bat build
```

生成的可安装 jar 位于：

```text
build/libs/aiplayer-companion-1.0.0.jar
```

## 安装

1. 安装 Minecraft Java 版 1.21.8。
2. 安装 Fabric Loader 0.17.2 或兼容版本。
3. 下载并放入 Fabric API `0.136.1+1.21.8` 到 `.minecraft/mods`。
4. 将 `build/libs/aiplayer-companion-1.0.0.jar` 放入 `.minecraft/mods`。
5. 用 Fabric 配置启动游戏。

## 配置文件

首次启动后会自动生成：

```text
config/aiplayer_companion.json
```

主要字段：

- `lmStudioApiUrl`：默认 `http://127.0.0.1:1234/v1/chat/completions`
- `modelName`：LM Studio 中加载的模型名称
- `requestTimeoutSeconds`：默认 15 秒
- `aiName`：AI 伙伴显示名称
- `aiChatEnabled`：是否启用聊天
- `startFollowDistance`：超过多少格开始跟随
- `stopFollowDistance`：小于多少格停止移动
- `teleportDistance`：超过多少格尝试安全传送
- `ownerPlayerName` / `ownerPlayerUuid`：生成伙伴后记录最近主人信息
- `enableFileLog`：是否写入单独日志文件，默认开启
- `enableOwnerDebugMessages`：是否把关键调试日志发到主人聊天栏
- `logChatContent`：是否记录 `/aiplayer chat` 的输入内容
- `maxLogMessageLength`：单条日志最长记录长度

如果配置加载失败，Mod 会使用默认值，并在日志中输出错误。

## LM Studio Local Server

1. 打开 LM Studio。
2. 下载或选择一个本地聊天模型。
3. 进入 Local Server。
4. 加载模型并启动服务。
5. 确认 API 地址为 `http://127.0.0.1:1234/v1/chat/completions`。
6. 将配置文件里的 `modelName` 改成 LM Studio 当前服务显示的模型名。

API Key 可以留空，本地 LM Studio 通常不需要。

## 游戏命令

- `/aiplayer spawn`：在玩家附近生成 AI 伙伴，并绑定当前玩家。
- `/aiplayer follow`：让 AI 伙伴开始跟随。
- `/aiplayer stop`：让 AI 伙伴原地等待并蹲下。
- `/aiplayer come`：让 AI 伙伴切回跟随，并立即重新寻路。
- `/aiplayer menu`：显示可点击交互菜单。
- `/aiplayer rename-auto`：按当前模型名自动命名 AI 伙伴。
- `/aiplayer remove`：删除自己的 AI 伙伴。
- `/aiplayer status`：查看名称、状态、生命值、坐标、距离和 LM Studio 连接状态。
- `/aiplayer debug on`：把 AI 行为调试日志同步显示到你的聊天栏。
- `/aiplayer debug off`：关闭聊天栏调试日志。
- `/aiplayer logs`：在聊天栏显示最近 10 条 AI 日志。
- `/aiplayer logs <数量>`：显示最近 1 到 50 条 AI 日志。
- `/aiplayer chat <内容>`：与 AI 伙伴聊天。
- `/aiplayer config show`：显示当前 AI API 配置。
- `/aiplayer config api <地址>`：设置 API 地址，支持只填 `http://IP:端口`，会自动补全 `/v1/chat/completions`。
- `/aiplayer config model <模型名>`：设置 LM Studio 或 OpenAI-compatible 服务的模型名。
- `/aiplayer config apikey <密钥>`：设置 API Key，适合 LM Studio 开启 Token 验证时使用。
- `/aiplayer config apikey-clear`：清空 API Key。
- `/aiplayer config chat on`：开启 AI 聊天。
- `/aiplayer config chat off`：关闭 AI 聊天。
- `/aiplayer config timeout <秒>`：设置请求超时时间。
- `/aiplayer config max_tokens <数量>`：设置最大输出 Token，范围 32 到 4096。R1/Thinking 模型建议 1024 或更高。
- `/aiplayer config test`：立即测试当前 API 是否能连通。

例如接入局域网 LM Studio：

```text
/aiplayer config api http://192.168.0.237:1234
/aiplayer config model 你的模型ID
/aiplayer config apikey 你的LMStudioToken
/aiplayer config test
```

如果 LM Studio 没有开启 API Token 验证，可以不执行 `apikey` 命令，或执行：

```text
/aiplayer config apikey-clear
```

## 查看日志

Mod 会写入 Minecraft 默认日志，也会单独写入：

```text
.minecraft/logs/aiplayer_companion.log
```

想要像后台 cmd 一样实时看日志，可以在 PowerShell 里运行：

```powershell
Get-Content "$env:APPDATA\.minecraft\logs\aiplayer_companion.log" -Wait
```

也可以打开 Minecraft Launcher 的输出日志窗口查看带有 `[aiplayer_companion]` 的日志。

LM Studio 只能看到它自己的 API 请求和模型输出，不能显示 AI 伙伴的寻路、等待、传送、绑定主人等游戏内部行为。游戏行为日志应看 Minecraft 日志或 `aiplayer_companion.log`。

聊天中的本地控制词不会调用模型：

- 普通聊天默认直接发给 AI，不需要 `/aiplayer chat`。
- 动作命令需要触发词，例如：`AI 跟着我`、`伙伴 停下`、`DeepSeek 过来`。
- 跟随动作词：`跟着我`、`跟随我`、`过来`、`来我这里`
- 等待动作词：`停下`、`别跟了`、`在这里等`、`原地等待`

## 交互菜单

- 右键 AI 伙伴：打开可点击交互菜单。
- 准星对准 AI 伙伴并按 `Q`：打开可点击交互菜单。
- 菜单里可以点击：跟随、等待、过来、状态、自动改名、测试 API、删除。

## 行为增强

- 主人距离较远时，AI 伙伴会提高跟随速度并疾跑。
- WAITING 状态会蹲下等待。
- 被攻击时会逃跑，不会反击，并给主人一条简短反馈。
- 距离太远时仍会尝试安全瞬移到主人附近。

## 常见问题

- `JAVA_HOME is not set`：安装 Java 21，并设置 `JAVA_HOME` 到 JDK 目录。
- 找不到 Fabric API：确认 Fabric API jar 已放入 `.minecraft/mods`。
- `/aiplayer chat` 提示 LM Studio 不可用：确认 LM Studio Local Server 已启动，模型已加载，端口是 `1234`。
- 模型不回复或返回错误：检查 `config/aiplayer_companion.json` 中的 `modelName` 是否与 LM Studio 服务中的模型名一致。
- AI 不跨维度跟随：第一版不会跨维度传送，主人回到同一维度后会继续跟随。

## 第一版限制

- 不挖矿。
- 不建造。
- 不攻击。
- 没有背包。
- 没有长期记忆。
- 不伪造完整 `ServerPlayerEntity`。
- 模型只能聊天，不能执行代码、系统命令或直接调用游戏内部方法。
