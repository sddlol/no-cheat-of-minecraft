# AntiCheatLite

一个轻量级的 Paper/Spigot 反作弊插件，主打：
- 基础移动/战斗检测（Speed / Fly / NoFall / Reach / KillAura / Scaffold 等）
- “Setback 回拉” + “恶心外挂”惩罚机制（例如扣血等，可在配置里调）
- 尽量兼容 **1.8.x ~ 1.21.x**（单 Jar）

> 说明：跨版本兼容会牵涉到 Bukkit/Paper API 在不同版本的差异，项目内部会做兼容处理（例如 Material / PotionEffect / RayTrace 等差异）。

---

## 支持版本

- **Minecraft：1.8.x ~ 1.21.x**
- **服务端：Spigot / Paper（推荐 Paper）**
- **Java：**
  - 1.8.x ~ 1.16.x：Java 8/11 通常可用
  - 1.17+：一般需要 Java 16+（以你服务端要求为准）

---

## 安装

1. 下载构建出的 jar（Actions artifact 或 Release 里）。
2. 把 jar 放进服务器的 `plugins/` 目录。
3. 重启服务器（或热加载不推荐）。
4. 首次启动会生成配置文件（通常在 `plugins/AntiCheatLite/` 或类似目录）。

---

## 指令

| 指令 | 说明 |
|------|------|
| `/acreload` | 重载配置 |
| `/acstatus` | 查看自己总 VL |
| `/acstatus <player>` | 查看指定玩家各项 VL |

> 如果你项目里权限节点名称不同，就以 `plugin.yml` 为准。

---

## 机制说明（简要）

- **VL（Violation Level）**：每个检测项都会累计 VL。
- **Setback（回拉）**：触发检测时将玩家拉回到“最近安全位置”。
- **惩罚机制**：可对连续/高频违规执行更强的惩罚（例如扣血、进一步限制移动等），用于“恶心外挂”。

---

## 配置（建议）

不同版本/分支可能配置项略有差异，一般会包含：
- 各检测项阈值（Speed/Fly/Reach…）
- setback 参数（回拉频率、最大距离、冷却等）
- punish 参数（扣血、提示、踢出/封禁接口预留等）
- debug 开关（聊天/控制台输出）

建议：
1. **先用默认配置跑一段时间**
2. 再根据误报率调整阈值
3. 对于跑酷/高延迟玩家，适当提高容错

---

## 构建（本地）

如果你本机有 Maven：

mvn -DskipTests package

输出 jar 在：

target/*.jar

---

## GitHub Actions + Release（Tag）

发布 Release（触发 tags）：

git tag -a v1.5.0 -m "Release v1.5.0"
git push origin v1.5.0

---

## 常见问题

### Actions 没触发？
- 检查仓库里是否还有 `.github/workflows/*.yml`
- 确认 Actions 没被仓库设置禁用
- 确认你 push 的分支/标签匹配 workflow 的触发条件

---

## 免责声明

- 反作弊属于对抗场景：**100% 防不住所有外挂**。
- 建议配合：反假人/反刷屏、观察插件、日志审计等一起使用。
- 任何惩罚机制请合理配置，避免误伤正常玩家。

---

## License

建议添加 LICENSE（MIT/Apache-2.0 等）。
