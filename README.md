# AntiCheatLite

> Language: **English** | [中文](./README.zh-CN.md)

A lightweight anti-cheat plugin for Paper/Spigot with a single cross-version jar.

Current direction: keep **1.8.x ~ 1.21.x** compatibility while moving more checks toward a Grim-inspired style (**simulation + buffering**, not only hard thresholds).

---

## Features

- ✅ Cross-version support: **Minecraft 1.8.x ~ 1.21.x**
- ✅ Single jar deployment
- ✅ Core checks:
  - Movement: Speed / Fly / Movement Sim / Blink / NoFall / Velocity
  - Combat: Reach / KillAura / AutoClicker / NoSlow
  - Packet sanity: BadPackets
  - Build: Scaffold
  - Mining: XRay
- ✅ Setback and punish pipeline (configurable)
- ✅ VL decay, combo weighting, and continuous buffers
- ✅ `/acstatus <player>` shows per-check VL and last flag reason

---

## Detection Model (Short)

### Movement / NoSlow (simulation-style)
- Estimate allowed movement from simplified vanilla rules (ground/air/potion/environment/item-use)
- Use **offset + buffer** instead of single-tick thresholding

### KillAura (multi-signal)
- angle / switch / line-of-sight
- smooth_rotation (overly stable aim movement)
- GCD-like rotation quantization check (fixed-step aim patterns)
- Reach + Aura combo weighting for higher confidence when both trigger

### Scaffold (prediction constraints)
- rotation stability + snap
- lightweight prediction limits: place reach / look angle + buffer

### Velocity / AntiKB
- compare expected horizontal knockback vs actual taken movement in a short window
- ratio + sample buffer to reduce false positives

### BadPackets (lightweight)
- invalid pitch / non-finite values / abnormal snap rotation patterns

### XRay (stats + path)
- hidden ore ratio
- hidden diamond burst
- path anomaly
- per-world profile + silk touch ignore + fortune relaxation + exempt worlds

### VL Reset Guard
- detects suspicious high-VL-to-zero drops in a short window and applies protection punishment

---

## Compatibility

- **Minecraft:** 1.8.x ~ 1.21.x
- **Server:** Spigot / Paper (Paper recommended)
- **Java:**
  - 1.8.x ~ 1.16.x: usually Java 8/11
  - 1.17+: usually Java 16+ (depends on your server)

---

## Installation

1. Download the built jar (Release or Actions artifact)
2. Put it into `plugins/`
3. Restart server (hot reload not recommended)
4. Edit generated config under `plugins/AntiCheatLite/` if needed

---

## Commands

| Command | Description |
|---|---|
| `/acreload` | Reload config |
| `/acstatus` | Show your total VL |
| `/acstatus <player>` | Show per-check VL + last flag details |
| `/acdebug [on\|off]` | Toggle chat debug alerts |

> Exact permissions/commands are defined in `plugin.yml`.

---

## Config Tuning Tips

Tune in this order:
1. Buffers/window first
2. Then thresholds/offsets
3. Keep combo signals enabled to reduce single-signal false positives

Focus sections:
- `checks.killaura.*`
- `checks.noslow.*`
- `checks.scaffold.prediction.*`
- `checks.xray.*`
- `checks.vl_reset_guard.*`
- `punishments.annoy_mode_threshold_vl`
- `setback.*`

---

## Build

```bash
mvn -DskipTests package
```

Artifact:

```text
target/*.jar
```

---

## Release (Tag)

```bash
git tag -a v1.8.6 -m "Release v1.8.6"
git push origin v1.8.6
```

---

## FAQ

### Actions not triggering?
- Ensure `.github/workflows/*.yml` exists
- Ensure Actions is enabled in repo settings
- Ensure pushed branch/tag matches workflow trigger rules

### Too many false positives?
- Check `/acstatus <player>` and inspect `lastFlag`
- Raise related buffer/window before raising hard limits
- Keep multi-signal checks on

---

## Disclaimer

Anti-cheat is adversarial by nature; no solution is 100% perfect.
Use with logs, moderation workflow, and reasonable punishment settings.

---

## License

Recommended: add a LICENSE file (MIT / Apache-2.0 / etc.).
