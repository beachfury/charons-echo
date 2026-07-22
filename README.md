# Charon's Echo

**Death, reimagined.** When you die, your items don't scatter — Charon takes them. A smoking
portal rises at the site of your death, and your ghost must cross into *Charon's Echo*, an
endless misty graveyard dimension, to pay the Ferryman and reclaim what you lost. Every death
digs a headstone. The graveyard **is** the history of your server.

Built for the CurseForge **Minecraft ModJam 2026 — "Echoes of the Past."**

## Highlights

- **Server-side only** — vanilla Java clients and Bedrock players (via Geyser) join with no
  client mod, no resource pack.
- **The graveyard dimension** — monochrome hills of pale moss and deepslate under the End's
  void sky. A gothic church, endless terraced grave fields, a winding river, silence in the
  air, and blind Wardens patrolling the paths as passive gravekeepers.
- **Echo Shards** — Charon's fare, crafted or bought in advance. Soul-bound: the one thing
  death cannot take. Die without one and the Ferryman takes his toll — half your XP, or your
  most valuable item, held ransom in his vault.
- **Ghosts** — invisible but for a wisp of soul-fire, tethered to the site of death until
  they cross the portal. Teammates can kneel and drop a shard on your body to pay your fare.
- **Every grave persists** — epitaphs, the Book of the Dead, flower tributes, and a Death of
  the Week enshrined by the church door.
- **An expanding world border** — the world of the dead literally grows as your server's
  dead accumulate.

## Requirements

- Minecraft **26.2** (Fabric) — a 26.1.2 build is planned
- Fabric API, [sgui](https://maven.nucleoid.xyz) (jar in the mods folder)

## Status

Early development for the jam — the core death loop is under construction. See
[DESIGN.md](DESIGN.md) for the full design and [CHANGELOG.md](CHANGELOG.md) for progress.

## For builders: Studio mode

Landmark builds (church, headstones, pale trees) are hand-built structure templates:

1. `/charon studio` — teleports you to the authoring world with labeled, outlined plots
2. Build inside an outline (lime block = NW anchor, orange = south-facing entrance)
3. `/charon export` while standing in the plot — saves the `.nbt` under `world/generated/`
4. Copy exported files into `src/main/resources/data/charons_echo/structure/`
5. `/charon place <name>` — paste any template back for review

## License

MIT
