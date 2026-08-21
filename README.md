# Room Backup

G-Earth extension to **save** and **remount** Habbo room layouts (floor + wall furniture), including stacked heights via Magic Stack Tile, and furniture states.

Inspired by / borrows patterns from **[G-Presets](https://github.com/sirjonasxx/G-Presets)** (stackmagic remount flow, state handling) and **[G-BuildTools](https://github.com/sirjonasxx/G-BuildTools)** (wall item move/place). Huge thanks to those projects.

## Features

- Save room snapshots (`.mount.json` + G-Presets-compatible `.gpreset.json`)
- Remount by furniture **id** first: skip if already correct, move if wrong, place only when missing
- Magic Stack Tile height / overlap remount (G-Presets-style)
- Furniture **states** (wired `-110` when possible, otherwise `UseFurniture` clicks)
- Walls first (G-BuildTools-style `MoveWallItem` / `PlaceObject`)
- Missing mode: **skip** · **stop** · **place new** (stage aside, then stackmagic to final tile)
- Chat commands and JavaFX UI

## Requirements

- [G-Earth](https://github.com/sirjonasxx/G-Earth) 1.5+
- Java 17+
- At least one **Magic Stack Tile** in the room for floor remounts
- Optional: `furnidata.xml` next to the JAR (otherwise downloaded from the connected hotel)

## Install

### G-ExtensionStore (recommended)

Install **Room Backup** from the built-in G-ExtensionStore once the PR is merged.

### Manual

1. Build or download `RoomBackup.jar`
2. Point G-Earth at the jar with cookie / port / filename CLI args (same as other Native extensions)

```text
java -jar RoomBackup.jar -c {cookie} -p {port} -f {filename}
```

## Usage

1. Enter a room and place a Magic Stack Tile
2. Open **Room Backup**
3. Save a name → **Save** (or `:msave name`)
4. Remount later with **Mount** (or `:mload name`)

### Chat

| Command | Action |
|--------|--------|
| `:msave name` | Save snapshot |
| `:mload name` | Mount snapshot |
| `:mdel name` | Delete snapshot |
| `:mlist` | List snapshots |
| `:mstop` | Stop mount |
| `:mstack` | Stack tile hop test |
| `:mopt missing skip\|stop\|place` | Missing behavior |
| `:mopt delay 40-1500` | Move delay (ms) |
| `:mhelp` | Help |

### Missing dropdown

- **skip** — do not place missing items
- **stop** — abort when something is missing
- **place new** — place from BC/inventory on a free stage tile, then stackmagic to the saved position

## Hotel compatibility

**Tested on Habbo ES (`.es`).** Packet names are used throughout (no hardcoded header IDs), so it should work on other hotels G-Earth supports (`.com`, `.fr`, `.com.tr`, etc.). Always verify with a small room first on a new hotel.

Marked compatible with Flash, Unity, Nitro, and Origins in the store metadata; Flash ES is the primary test environment.

## Build from source

```bash
cd java
# install local G-Earth once if needed:
# mvn install:install-file -Dfile=../../G-Earth/G-Earth.jar -DgroupId=G-Earth -DartifactId=G-Earth -Dversion=1.5-local -Dpackaging=jar
mvn -DskipTests package
```

Output: `java/target/RoomBackup.jar`

Store zip helper:

```powershell
cd java
.\pack-store.ps1
```

Creates `store-submission/Room Backup/extension.zip`.

## Credits

- [sirjonasxx](https://github.com/sirjonasxx) — G-Earth, G-Presets, G-BuildTools, G-ExtensionStore
- Stack / wall remount approaches adapted from G-Presets and G-BuildTools

## Author

- **reactruler** — Habbo: **Habbito** (`.es` / `.com` / `.fr` / `.com.tr`)
- Discord: **reactruler28**

## License

MIT
