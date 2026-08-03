# Baritone 路线规划 + Carpet 假玩家执行方案

## 目标

在不双开 Minecraft 客户端的前提下，继续使用 Carpet 假玩家作为 AIPlayer 本体，并尝试移植 Baritone 的路线规划能力。

最终结构：

```text
AI / 本地规则决定目标
        ↓
Baritone 风格路径规划层
        ↓
CarpetPathExecutor
        ↓
/player <bot> look / move / jump once / use once / stop
        ↓
Carpet 假玩家执行真实玩家动作
```

## 可行性结论

可行，但不是简单复制几个 Baritone 类。

Baritone 的路线规划和以下内容绑定很深：

- 玩家上下文
- 客户端世界缓存
- 方块状态查询
- 玩家碰撞箱
- Movement 代价模型
- 设置系统
- 客户端路径执行器

因此正确做法不是把完整 Baritone 客户端塞进服务端 mod，而是移植或重写 Baritone 的路径计算层，并替换执行器。

## 推荐路线

### 阶段 1：独立分支验证

建议分支名：

```text
codex/baritone-pathing-adapter
```

第一阶段只做：

- 引入或参考 Baritone 源码。
- 建立服务端版 PlayerContext。
- 建立服务端版 WorldAccess。
- 给 AIPlayerBot 和主人之间计算一条路径。
- 先把路径打印到日志，不控制 bot。

验收标准：

- 不影响当前 Carpet 假玩家召唤。
- 游戏启动不报错。
- `/aiplayer path debug` 能输出路径节点。

### 阶段 2：Carpet 执行器

新增：

- `CarpetPathExecutor`
- 将路径节点翻译为 Carpet 动作：
  - `look`
  - `move forward`
  - `sprint`
  - `jump once`
  - `use once`
  - `stop`

验收标准：

- bot 能沿路径绕过简单障碍。
- 不再遇到两格高墙无限跳。
- 门前能开门。

### 阶段 3：Movement 能力扩展

逐步支持：

- 普通行走
- 一格跳跃
- 下落
- 门
- 活板门
- 梯子
- 水边避险
- 简单卡住重算

暂时禁用：

- 自动挖掘
- 自动放置
- 高风险跳跃
- 岩浆路径
- 复杂跑酷

## 许可证注意

Baritone 是 LGPL-3.0 项目。

如果直接复制/修改 Baritone 源码：

- 需要保留许可证声明。
- 需要区分第三方代码和本项目代码。
- 需要在 README 或 NOTICE 中说明来源。

如果只参考设计、自己重写：

- 维护更轻。
- 许可证压力更小。
- 但工程量会增加。

## 风险

- Minecraft 版本升级时，映射和内部 API 容易变化。
- Baritone 原始代码偏客户端，服务端适配工作量较大。
- 完整移植可能比当前轻量巡路复杂很多。
- 一次性移植完整 Baritone 容易拖慢 AI 聊天、菜单、背包等功能迭代。

## 后续提醒

当后续继续处理以下问题时，应优先提醒此方案：

- bot 巡路仍然卡墙。
- bot 不会绕路。
- bot 不会爬梯子。
- bot 不能稳定开门。
- 需要接近真人玩家的路线规划。
- 需要从轻量跟随升级到完整路径规划。
