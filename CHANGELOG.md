# Changelog

## 0.1.0 — in development

- The full death loop is live: a soul-fire death portal rises at the death site — walk in
  to pay Charon (consumes an Echo Shard, or he takes half the grave's XP) and cross to
  your grave in Charon's Echo; graves are allocated on a square spiral of 40×40 fenced,
  terraced fields (48 plots each, soul lanterns on the corners) with generated headstones
  (name / cause / day on the sign); touch your stone to reclaim items + XP, then the
  return portal beside the grave carries you back to a safe spot at your death site,
  alive; reclaimed graves persist as "— at rest —" memorials; portals are pure particles,
  no blocks, and a relogged ghost always gets its return portal back

- Project scaffold: Fabric / Minecraft 26.2, Loom 1.17.12, JDK 25, sgui
- Charon's Echo graveyard dimension: monochrome rolling hills (pale moss / tuff / deepslate),
  church plateau at origin, winding river with small ponds, no caves, End-style void skybox,
  fixed time, silent gray-tinted custom biome, no natural mob spawns
- Terrain is built by a real registered chunk generator (`charons_echo:graveyard`) at the
  noise stage, so vanilla computes lighting and heightmaps correctly
- Studio authoring dimension with labeled plot grid for hand-building structure templates
- Death loop core: death is intercepted (no drops, no death screen) — items + XP are
  captured into a persistent grave record and the player rises as a ghost at the death
  site: invisible with a soul-particle silhouette, flying, invulnerable, no world
  interaction, gray name (`charon_dead` team), `charon.ghost` tag, tethered to a
  24-block radius around the death anchor; ghost state survives relog/restart and is
  re-asserted every tick against other mods
- `/charon revive [player]` restores the oldest unclaimed grave (items + XP) and lifts
  the ghost state — interim testing path until portals land
- Charon's Obol item (marked vanilla echo shard with glint + lore, crossplay-safe):
  soul-bound — the one item death cannot take; `/charon obol [count]` gives them
  (crafting/buying later). Renamed from "Echo Shard" — vanilla already has one.
- Commands: `/charon studio`, `/charon export [name]`, `/charon place <name>`,
  `/charon visit`, `/charon back` (gamemaster-level)
