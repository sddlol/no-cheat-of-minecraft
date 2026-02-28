# AntiCheatLite

> 语言： [English](./README.md) | **中文**

一个轻量级的 Paper/Spigot 反作弊插件，单 Jar 跨版本运行。

当前方向：在保持 **1.8.x ~ 1.21.x** 兼容的前提下，逐步把检测迁移到更接近 Grim 的方式（**模拟 + 缓冲**，而不是只靠硬阈值）。

---

## 主要特性

- ✅ 跨版本支持：**Minecraft 1.8.x ~ 1.21.x**
- ✅ 单 Jar 部署
- ✅ 核心检测：
  - 移动：Speed / Fly / Movement Sim / Blink / NoFall / Velocity
  - 战斗：Reach / KillAura / AutoClicker / NoSlow
  - 协议健全性：BadPackets
  - 搭路：Scaffold
  - 挖矿：XRay
- ✅ Setback 回拉 + 惩罚流程（可配置）
- ✅ VL 衰减、组合权重、连续缓冲判定
- ✅ `/acstatus <player>` 可查看分项 VL 与最近触发原因

---

## 检测模型（简述）

### Movement / NoSlow（模拟化）
- 基于简化 vanilla 规则估算允许移动（地面/空中/药水/环境/使用物品）
- 使用 **offset + buffer**，而不是单次越线即判

### KillAura（多信号）
- angle / switch / line-of-sight
- smooth_rotation（异常平滑转头）
- GCD-like 步进检测（固定步长转头模式）
- Reach + Aura 组合权重（同时触发时提高可信度）

### Scaffold（预测约束）
- 旋转稳定性 + 突变
- 轻量预测约束：放置距离 / 视线夹角 + buffer

### Velocity / AntiKB
- 在短窗口内比较预期击退水平速度与实际吃到的位移
- 使用比例 + 样本缓冲，减少误报

### BadPackets（轻量）
- 无效 pitch / 非有限数值 / 异常转头突变模式

### XRay（统计 + 路径）
- 隐藏矿比例
- 隐藏钻石突增
- 路径异常
- 世界维度配置 + 丝触忽略 + 时运容错 + 世界白名单

### VL Reset Guard
- 识别高 VL 在短时间异常归零，并执行保护性惩罚

---

## 兼容性

- **Minecraft：** 1.8.x ~ 1.21.x
- **服务端：** Spigot / Paper（推荐 Paper）
- **Java：**
  - 1.8.x ~ 1.16.x：通常 Java 8/11
  - 1.17+：通常 Java 16+（以服务端要求为准）

---

## 安装

1. 下载构建产物 jar（Release 或 Actions artifact）
2. 放入 `plugins/` 目录
3. 重启服务器（不建议热加载）
4. 需要时编辑 `plugins/AntiCheatLite/` 下的配置

---

## 指令

| 指令 | 说明 |
|---|---|
| `/acreload` | 重载配置 |
| `/acstatus` | 查看自己总 VL |
| `/acstatus <player>` | 查看分项 VL + 最近触发详情 |
| `/acdebug [on\|off]` | 开关聊天调试告警 |

> 实际权限和命令以 `plugin.yml` 为准。

---

## 调参建议

推荐顺序：
1. 先调 buffer / window
2. 再调阈值 / offset
3. 保留组合信号，减少单信号误报

重点配置段：
- `checks.killaura.*`
- `checks.noslow.*`
- `checks.scaffold.prediction.*`
- `checks.xray.*`
- `checks.vl_reset_guard.*`
- `punishments.annoy_mode_threshold_vl`
- `setback.*`

---

## 本地构建

```bash
mvn -DskipTests package
```

产物：

```text
target/*.jar
```

---

## 发布（Tag）

```bash
git tag -a v1.8.6 -m "Release v1.8.6"
git push origin v1.8.6
```

---

## 常见问题

### Actions 没触发？
- 检查 `.github/workflows/*.yml` 是否存在
- 检查仓库设置里 Actions 是否启用
- 检查分支/tag 是否匹配 workflow 触发条件

### 误报偏多？
- 先看 `/acstatus <player>` 的 `lastFlag`
- 先提高相关 buffer/window，再考虑放宽硬阈值
- 尽量保留多信号联判

---

## 免责声明

反作弊属于对抗场景，不存在 100% 完美方案。
建议配合日志、管理流程与合理惩罚设置一起使用。

---

## License

建议补充 LICENSE（MIT / Apache-2.0 等）。
