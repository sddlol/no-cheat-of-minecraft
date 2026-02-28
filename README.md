# AntiCheatLite

一个轻量级的 Paper/Spigot 反作弊插件（单 Jar，跨版本兼容）。

当前方向：在保持 **1.8.x ~ 1.21.x** 兼容前提下，引入更多 **Grim 风格的“行为/物理模拟 + 缓冲判定”**，减少纯硬阈值误报。

---

## 主要特性

- ✅ 跨版本兼容：**Minecraft 1.8.x ~ 1.21.x**
- ✅ 单 Jar 运行（无需按版本分别构建）
- ✅ 核心检测：
  - Movement：Speed / Fly / Movement Sim / Blink / NoFall
  - Combat：Reach / KillAura / AutoClicker / NoSlow
  - Build：Scaffold
  - Mining：XRay
- ✅ Setback 回拉 + 惩罚机制（可配置）
- ✅ VL 衰减、组合权重、buffer 缓冲（连续异常再处罚）
- ✅ `/acstatus <player>` 可查看各项 VL + 最近一次触发原因

---

## 检测思路（简述）

### Movement / NoSlow（模拟化）
- 基于简化 vanilla 规则估算允许移动（地面/空中/药水/环境/使用物品）
- 通过 **offset + buffer** 判断，而非单点硬阈值

### KillAura（多信号）
- angle / switch / LOS（穿墙）
- smooth_rotation：异常平滑转头检测
- GCD-like rotation：固定步进转头模式检测（常见于部分辅助瞄准）
- Reach + Aura 支持组合权重（combo 更高可信度）

### Scaffold（预测约束）
- 旋转稳定性 + 突变
- 增加轻量预测约束：放置距离/视线夹角 + buffer

### XRay（统计与路径）
- 隐藏矿比例
- 钻石突增
- 连挖路径异常
- 支持世界维度阈值、丝触忽略、时运容错、世界白名单

### VL Reset Guard
- 检测“高 VL 在短时间异常归零”并执行保护性惩罚（可配置）

---

## 支持版本

- **Minecraft：1.8.x ~ 1.21.x**
- **服务端：Spigot / Paper（推荐 Paper）**
- **Java：**
  - 1.8.x ~ 1.16.x：通常 Java 8/11 可用
  - 1.17+：通常需要 Java 16+（以服务端要求为准）

---

## 安装

1. 下载构建产物 jar（Release 或 Actions artifact）
2. 放入服务器 `plugins/` 目录
3. 重启服务器（不建议热加载）
4. 首次启动后生成配置文件（通常在 `plugins/AntiCheatLite/`）

---

## 指令

| 指令 | 说明 |
|------|------|
| `/acreload` | 重载配置 |
| `/acstatus` | 查看自己总 VL |
| `/acstatus <player>` | 查看指定玩家各项 VL + 最近一次触发信息 |
| `/acdebug [on\|off]` | 开关聊天调试告警 |

> 实际权限与命令以 `plugin.yml` 为准。

---

## 配置建议

建议先用默认配置跑一段时间，再按误报/漏报调整。

优先关注：
- `checks.killaura.*`
- `checks.noslow.*`
- `checks.scaffold.prediction.*`
- `checks.xray.*`
- `checks.vl_reset_guard.*`
- `setback.*`

调参原则：
1. 先调 `buffer` 和窗口，再调阈值
2. 高延迟服优先放宽基础阈值，保留组合/连续判定
3. 观察 `/acstatus <player>` 的 lastFlag 再做定向微调

---

## 本地构建

```bash
mvn -DskipTests package
```

输出 jar：

```text
target/*.jar
```

---

## Release（Tag）

```bash
git tag -a v1.8.3 -m "Release v1.8.3"
git push origin v1.8.3
```

> 按你的版本规则可继续递增：小更新 +0.01，大更新 +0.1。

---

## 常见问题

### 1) Actions 没触发
- 检查 `.github/workflows/*.yml` 是否存在
- 检查仓库 Actions 是否被禁用
- 检查 push 分支或 tag 是否命中 workflow 触发条件

### 2) 误报偏多
- 先看 `/acstatus <player>` 的 `lastFlag`
- 提高对应 check 的 `buffer_min` 或放宽 `offset/angle` 类参数
- 保留组合判定，避免只靠单一信号

---

## 免责声明

- 反作弊是对抗场景，不存在 100% 防护。
- 建议配合日志审计、行为回放、管理流程一起使用。
- 惩罚项请谨慎调参，避免误伤正常玩家。

---

## License

建议补充 LICENSE（MIT / Apache-2.0 等）。
