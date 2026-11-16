<div align="center">

<img src="banner.jpg" alt="SkyCore: floating skyblock islands above the clouds at sunset" width="100%">

# ☁️ SkyCore

### Your own Skyblock server, ready in one command.

A complete, self-hosted Skyblock server for **Minecraft Java Edition 26.3**.<br>
It has tuned performance, deep progression and safe defaults, and you don't need to touch any Java.

<p>
  <img src="https://img.shields.io/badge/Minecraft-26.3-62B47A?style=for-the-badge&logo=minecraft&logoColor=white" alt="Minecraft 26.3">
  <img src="https://img.shields.io/badge/Paper-build%20253-F7CF0D?style=for-the-badge" alt="Paper build 253">
  <img src="https://img.shields.io/badge/Java-25-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 25">
  <img src="https://img.shields.io/badge/Docker-ready-2496ED?style=for-the-badge&logo=docker&logoColor=white" alt="Docker ready">
  <img src="https://img.shields.io/badge/pay--to--win-never-FF5E8A?style=for-the-badge" alt="No pay-to-win">
</p>

[**Quick start**](#-quick-start) · [**Features**](#-features) · [**Installation**](#-installation) · [**Everyday use**](#-everyday-use) · [**Commands**](#-commands) · [**FAQ & limits**](#-known-limitations)

</div>

---

## 💡 Why SkyCore?

Running a good skyblock server usually means stitching together a dozen plugins, then fighting version mismatches and
balancing an economy that inflates within a week. SkyCore was built so that **anyone can host their own skyblock
server with very little effort**:

- **One plugin, one design.** Islands, economy, skills, automation, market and goals were designed together, so they
  fit together.
- **One command to run.** Docker builds and starts everything. You don't need to install Java or configure plugins
  by hand.
- **Fair by design.** Rewards come from playing, not from going AFK or paying real money. The project is non-profit.
- **Easy to tune.** Every number lives in plain YAML. Change it and run `sc reload`.

## ⚡ Quick start

> [!TIP]
> You only need [Docker](https://docs.docker.com/get-docker/) and the Paper jar. Full details are in
> [Installation](#-installation).

```sh
git clone <this repository URL> skycore-server && cd skycore-server
# 1. put paper-26.3-253.jar in this folder (https://papermc.io/downloads/paper)
# 2. set EULA: "true" in docker-compose.yml
docker compose up -d --build
```

Then connect to `localhost:25565` and type `/is` to get your first island. 🏝️

## ✨ Features

<table>
<tr>
<td width="50%" valign="top">

### 🏝️ Islands
- A starter island with a tree, a chest and a **working cobblestone generator**
- Up to **3 independent islands** per player
- Each island has its own **Nether** and **End** mini-islands
- Co-op members, per-island borders and protection
- No PvP, no explosion damage, and the void sends you home

</td>
<td width="50%" valign="top">

### 💰 Two currencies
- **Dollars** come from selling items and automation. They can be traded.
- **Roses ✿** come only from **active play**. They can never be transferred.
- Items bought with Roses are **soulbound**
- Built-in inflation controls: sell multiplier cap, fees and telemetry

</td>
</tr>
<tr>
<td valign="top">

### ⛏️ Skills
- Mining, Farming, Fishing and Combat, levels **1–100**
- Roses every N actions, with a daily soft cap
- Anti-AFK and anti-macro detection
- Level perks, double drops and a drop magnet

</td>
<td valign="top">

### ⚙️ Automation (never replaces playing)
- Virtual **minions** with 8 tiers
- **Sell chests** and **chunk hoppers**
- **Stacked spawners**: one block, one entity
- Permanent **special crops**
- Offline production, up to 12 h

</td>
</tr>
<tr>
<td valign="top">

### 🛒 Market
- Admin shop, `/sell` and **sell wands**
- **Auction house** with listing fees and abuse alerts
- **Player chest shops** and `/shops <item>`

</td>
<td valign="top">

### 🎯 Always a next goal
- **Daily missions** with streaks, plus **20 island missions**
- A **10-tier career path** and **prestige**
- **Pets** and **weekly rankings** with rewards
- A scoreboard that always shows your next step

</td>
</tr>
</table>

**🛠️ Built-in operations:** daily zipped backups (kept 14 days), a scheduled daily restart, an economy telemetry
report, and `sc selftest`, which checks the core mechanics on a live server.

> [!NOTE]
> The full game design (in Portuguese) is in
> [Design Specification — Servidor Skyblock.md](Design%20Specification%20—%20Servidor%20Skyblock.md).

## 🖥️ Requirements

| | Minimum | Recommended (up to ~10 players) |
| --- | --- | --- |
| 🧠 CPU | 2 cores | 4 cores |
| ☕ RAM for the server (Java heap) | 2.5 GB | **3 GB** |
| 🐳 RAM for Docker (heap + ~1.5 GB) | 4 GB | 4.5–6 GB |
| 💾 Disk | 2 GB | 10 GB (backups grow over time) |

It runs on **macOS** (Intel or Apple Silicon), **Linux** and **Windows** with
[Docker](https://docs.docker.com/get-docker/) installed. Players need Minecraft Java Edition **26.3**.

## 📦 Installation

### 1. Get the project

```sh
git clone <this repository URL> skycore-server
cd skycore-server
```

### 2. Add the Paper server jar

The Paper jar (55 MB) isn't stored in git. Download **Paper 26.3, build 253** from
[papermc.io/downloads/paper](https://papermc.io/downloads/paper). Save it in the project root as
**`paper-26.3-253.jar`**.

<details>
<summary>🔒 Optional: verify the download</summary>

```sh
shasum -a 256 paper-26.3-253.jar
# f9df8e19f4bdee97dbded48095391ea69c64342a4e19624db4f5e0d0136dfe0b
```

</details>

### 3. Give Docker enough memory

On **macOS** and **Windows**, open Docker Desktop and go to **Settings → Resources → Memory**. Set it to at least
**4.5 GB** for a 3 GB server. On **Linux** there is nothing to do.

### 4. Configure `docker-compose.yml`

```yaml
environment:
  EULA: "true"        # you accept the Minecraft EULA: https://aka.ms/MinecraftEULA
  MEMORY: "3G"        # Java heap: see Requirements
  TZ: "Europe/Lisbon" # time zone for daily missions, weekly rankings, backups and restarts
```

> [!IMPORTANT]
> The server won't start until `EULA` is set to `"true"`.

### 5. Start the server

```sh
docker compose up -d --build
```

The first build takes a few minutes: it compiles the SkyCore plugin and downloads the pinned plugins. After that,
startup takes about **10 seconds**.

### 6. One-time setup in the console

```sh
docker attach skycore
```

> [!WARNING]
> To leave the console without stopping the server, press **Ctrl-P then Ctrl-Q**. Never press Ctrl-C.

In the console:

1. Paste the lines from [`server/luckperms-setup.txt`](server/luckperms-setup.txt). They create the `helper` and
   `admin` roles.
2. Give yourself admin:

   ```
   lp user <your-minecraft-name> parent add admin
   ```

3. Check that everything works:

   ```
   sc selftest
   ```

   It should report ✅ `All 19 checks passed`.

### 7. Let players in 🎉

- **Same network:** players connect to `<your machine's IP>:25565`.
- **Friends elsewhere:** forward **TCP port 25565** on your router to this machine.
- **Invite-only:** the server is public by default. To restrict it, type `whitelist on` and then
  `whitelist add <name>` in the console.

<details>
<summary>🧰 Running without Docker</summary>

You need **Java 25** or newer:

```sh
EULA=true MEMORY=3G scripts/run-local.sh
```

This builds the plugin and runs the server in `./run`.

</details>

## 🔁 Everyday use

| Task | Command |
| --- | --- |
| ▶️ Start / ⏹️ stop | `docker compose up -d` / `docker compose down` (the world is saved on stop) |
| 💬 Console | `docker attach skycore` (leave with Ctrl-P Ctrl-Q) |
| 📜 Logs | `docker compose logs -f` |
| 🔄 Update after changing the code | `docker compose up -d --build` |
| 🎛️ Reload balance files | `sc reload` |
| 📈 Performance | `spark tps`, `spark health`, `spark profiler` |
| 💾 Manual backup | `sc backup` |
| 📊 Economy report | `sc telemetry 7` |

### 📁 Where your data lives

Everything persistent lives in **`./data`** on the host:

| What | Where |
| --- | --- |
| 🌍 World | `data/world` |
| 🗄️ SkyCore database | `data/plugins/SkyCore/skycore.db` |
| 🎛️ Balance files | `data/plugins/SkyCore/*.yml` |
| 💾 Backups | `data/backups` |
| ⚙️ Server configs | `data/server.properties`, `data/bukkit.yml`, `data/spigot.yml`, `data/config/` |

<details>
<summary>🎛️ <b>Balance files</b>: what each one controls</summary>

<br>

The balance files are copied to `data/plugins/SkyCore/` on first start. Edit them there and run `sc reload`.

| File | Contents |
| --- | --- |
| `config.yml` | General settings, market fees, prestige, backups, restart time |
| `prices.yml` | Base sell prices: the single source for sell chests, `/sell`, the admin shop and island value |
| `skills.yml` | XP, Roses payouts, daily cap, perks |
| `islands.yml` | Island levels, limits, upgrade costs, extra islands |
| `generators.yml` | Generator odds per dimension |
| `automation.yml` | Special crops, minions, chunk hoppers, sell chests, sell wands |
| `spawners.yml` | Spawner types, prices, loot |
| `pets.yml` | Pets and rarities |
| `goals.yml` | Daily missions, island missions, tiers, weekly ranking rewards |

The server templates in `server/` (`server.properties`, `bukkit.yml`, `spigot.yml`, `config/paper-*.yml`) are copied
only on first start. After that, edit the copies in `data/`.

</details>

> [!CAUTION]
> **Never change `islands.spacing` after the first island exists.** It would misplace every island. SkyCore logs an
> error if you do.

<details>
<summary>♻️ <b>Restoring a backup</b></summary>

<br>

1. Run `docker compose down`.
2. Move `data/world` and `data/plugins/SkyCore/skycore.db` somewhere safe.
3. From a zip in `data/backups/`:
   - extract `worlds/world` to `data/world`
   - extract `skycore.db` to `data/plugins/SkyCore/skycore.db`
4. Run `docker compose up -d`.

</details>

## 🎮 Commands

| Command | What it does |
| --- | --- |
| `/is` | Island menu (creates your island the first time) |
| `/is home [n]` · `/is list` · `/is upgrades` · `/is missions` | Travel, switch islands, upgrade, view missions |
| `/is invite <player>` · `/is accept` · `/is kick <player>` · `/is leave` | Island members |
| `/is visit <player>` · `/is sethome` · `/is setwarp` · `/is warp <player>` · `/is top` | Visiting, homes, shop warps |
| `/shop` · `/sell hand` · `/sell all` | Server shop and quick selling |
| `/ah` · `/ah sell <price>` · `/shops <item>` | Auction house and player shops |
| `/skills` · `/pets` · `/missions` · `/path` · `/top` · `/rewards` | Progress and rewards |
| `/bal` · `/pay <player> <amount>` · `/baltop` · `/roses` | Money (Roses can't be paid) |
| `/wand recharge <uses>` · `/prestige` · `/spawn` | Misc |

<details>
<summary>🛡️ <b>Admin commands</b> (<code>skycore.admin</code>)</summary>

<br>

| Command | What it does |
| --- | --- |
| `sc reload` | Reload the balance files |
| `sc selftest` | Run the live self-test (creates and removes a temporary island) |
| `sc backup` | Back up now |
| `sc telemetry [days]` | Report Dollars and Roses created and destroyed, by source |
| `sc give <player> <item> …` | Give SkyCore items (minions, seeds, wands, spawners, pet eggs…) |
| `sc roses\|dollars add\|take\|set <player> <n>` | Adjust balances |
| `sc setlevel` · `sc skill` · `sc title` · `sc islandtp` | Support and testing tools |

</details>

## 🧱 Under the hood

| Part | Choice |
| --- | --- |
| 🚀 Server | Paper 26.3 build 253 on Java 25 (Eclipse Temurin), tuned for low entity and chunk load |
| 🧠 Game logic | **SkyCore**: this repository's own plugin, with SQLite storage and asynchronous batched writes |
| 🔐 Permissions | LuckPerms 5.5.71 |
| 💵 Economy bridge | VaultUnlocked 2.20.3 (SkyCore exposes Dollars only, never Roses) |
| 📈 Profiler | spark (bundled with Paper) |

External plugins are pinned with SHA-512 checksums in [`plugins.lock`](plugins.lock).

<details>
<summary>🗂️ <b>Project layout</b></summary>

<br>

| Path | Contents |
| --- | --- |
| `skycore/` | The plugin source code (Gradle) |
| `server/` | Server config templates |
| `scripts/` | `fetch-plugins.sh`, `prepare-server.sh`, `run-local.sh` |
| `Dockerfile` · `docker-compose.yml` · `docker/` | The Docker image and its entrypoint |
| `banner.jpg` | The image at the top of this README |

</details>

## 🚧 Known limitations

- **No block logging/rollback plugin yet.** CoreProtect CE 24.1 refuses to start on 26.3. It is commented out in
  `plugins.lock`; re-enable it once a compatible release exists. Until then, use the daily backups.
- **Tested:** builds, server startup with all plugins, `sc selftest`, backup and restore.
- **Not tested yet:** real players, and a load test with many players. Treat the first weeks as a **beta**.
- Islands left behind after a prestige are retired, not wiped. Their grid slot stays reserved.
- Pets have no visual entity. This is deliberate, for performance.

---

<div align="center">

Made with ☁️ and 🧱 for everyone who wants their own corner of the sky.<br>
<sub>Not affiliated with Mojang or Microsoft. Minecraft is a trademark of Mojang AB.</sub>

</div>
