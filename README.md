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

## For builders: the Studio

All landmark builds (headstones, trees, gates, ruins, the church, crypt pieces) are
hand-built structure templates, authored in the Studio — a flat builders-only world:

- `/charon studio` — enter (gamemasters and rostered gravekeepers only; add builders
  with `/charon builder add <player>`)
- Build inside a plot outline. **Lime = NW anchor, orange = south** — your build's
  front faces its label sign. Include exactly ONE sign per headstone (any sign type);
  the mod writes name/date/cause onto it. You may dig below grade — coffins, roots,
  and foundations ship (depth varies by category).
- `/charon plot new <category> <name>` — stake a new correctly-sized plot (tab-complete
  shows categories; names are auto-prefixed)
- `/charon export [name]` — capture the plot to a template. Exports overwrite in place.
- `/charon place <name>` — paste any template at your feet for review
- Sets (style families): `/charon set new <name> [size]` stakes a bordered area where a
  coherent style is built; admin `set trust` locks it to its steward + invitees,
  `set approve` puts every exported piece into generation, `set reopen` allows
  additions (shipped after the next approval). Each graveyard region draws from ONE set.

**EXPORT = SAVE.** Studio builds are just blocks in a world; templates are forever.
- Anything **exported** is safe: it generates in-game immediately, and the Studio
  self-restores it — any empty plot with a known template regrows its build at server
  start. Deleting the studio dimension resets it to a clean gallery of everything ever
  exported.
- Anything **not exported** exists only as blocks and dies with the world. Treat
  `/charon export` like Ctrl+S: export early, export often — overwriting is free.
- Layout changes and restamps never touch builds; only marker blocks (outlines,
  anchors, borders, stray labels) are ever cleaned up automatically.

## License

MIT
