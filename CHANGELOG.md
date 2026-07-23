# Changelog

## 0.1.0 — in development

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
- Commands: `/charon studio`, `/charon export [name]`, `/charon place <name>`,
  `/charon visit`, `/charon back` (gamemaster-level)
