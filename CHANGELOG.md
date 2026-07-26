# Changelog

## 0.1.0 — in development

- THE WAR BELOW THE MOON — an eternal three-sided war in Charon's Echo,
  invisible to the living, joinable by the dead. The KEEPERS (iron golems,
  creakings, allay couriers) hold the yards; the RESTLESS (parched, bogged,
  stray) rise from the active grave field; the HOLLOW WIND (vex, breeze)
  raids both. The war is fought at the FRONT — the field currently receiving
  burials; filled fields are settled ground forever. Living players are
  untouchable and harmless (the war cannot see them); sniffers and allays are
  civilians. **Payment moved to the stone**: the crossing is free, and touching
  your grave offers the choice — pay the fare (obol), pay the toll (XP), or
  TAKE THE OATH: enlist with the Keepers or the Restless, receive a loaned
  phantom kit, and serve `war-service-minutes` (default 15) at the front.
  Each enemy downed shaves `war-kill-credit-seconds` off the clock; serving
  out is a free resurrection. Enlisted dead of opposing sides can fight each
  other — defeat means downed (respawn at muster, `war-downed-penalty-seconds`
  added), never death; your items were in the grave all along. `/charon oath`
  shows your service; `/charon oath quit` pauses the clock.

- THE STYGIAN ORCHARD — plant the Withered Grove in the overworld. **The Broker**,
  a trader who stopped wandering long ago, stands in Charon's Echo selling
  **Stygian Seeds** (config `orchard-seed-price`, default 32 emeralds). Plant one
  on solid ground: it grows in two stages (small tree, then a grown elder) while
  its chunk is loaded. Grown trees hang **Tollfruit** from their chains — a
  mangrove-root bud that sculk slowly seals, then ripens into a glowing froglight.
  Break the ripe fruit (anyone may), and **craft 4 Tollfruit → 1 Charon's Obol**.
  The tree itself drops NOTHING as blocks: breaking any part of it without a
  netherite axe fails; with one, a felling ritual crumbles the whole tree to
  nothing and returns only its seed. Per-player tree cap (`orchard-tree-cap`,
  default 3). Trees only grow into open ground and wait politely when blocked.
  Fruit per cycle is a weighted roll (1–4); the tree rests after a full harvest.
  Wild elders in Charon's Echo bear fruit too — the one thing the living may
  take from the graveyard — and elder growth has its mysteries.
- Big-slot decor (ruins, wild elders) tolerates relief 5 on its footprint —
  flat-ground demands left the wilds with no big pieces at all; wild elders are
  deliberately rare (~1 in 5–6 big slots).
- Fixed: graveyard chunks already loaded at server start (forceloaded areas)
  were dropped from the decoration queue and never decorated.
- Fixed: saved studio plots created under an older layout could overlap the
  widened base rows — default-row plots now re-fit to the current layout on
  every load. `/charon plot new <category> <category>` no longer doubles the
  prefix, and `/charon plot remove <name>` (admin) deletes a builder plot.
- Graves face WEST, the way of the dead; ghosts arrive facing their own epitaph.

- Headstone size classes: small 3×3×3 markers, standard 4×4×4 stones, large 5×5×5
  monuments (new default-set rows headstone_small_1..4, headstone_large_1..2). The
  TERRAIN picks the class per grave — flat ground rolls the full mix (60/25/15),
  slopes drop the monuments, rough spots take only small markers — and only the
  chosen stone's footprint (+1 ring) is terraced, so small stones hug the land.
  Classes fall back to each other until templates exist.

- SETS — style families: `/charon set new <name> [size]` stakes a gold-bordered, owner-sized
  (32–256) area in the Studio; plots stake free-form wherever the builder stands
  (`/charon plot new` inside a set). Lifecycle: open (any gravekeeper builds) → `set trust`
  (steward + `set invite`d builders only) → `set approve` (locked; every exported piece
  enters generation at once) → `set reopen` (additions ship after next approval). Each
  region of Charon's Echo draws from ONE approved set (seed-deterministic), falling back to
  the default set for uncovered categories — styles never mix mid-field. No minimum
  content: a trees-only set is welcome. Set templates are namespaced (`<set>/<piece>`).

- World seed now shapes the graveyard terrain — every server's Charon's Echo is unique
  (regenerate the dimension after updating)
- Gravekeeper builder roster: `/charon builder add|remove <player>` — rostered builders
  may build in Charon's Echo and the Studio and use studio/export/place/plot commands
  without op; both dimensions are otherwise build-protected
- Studio content pipeline: `/charon plot new <category> <name>` stakes a new correctly-sized
  plot at the end of its category row (headstone/tree/big_tree/clutter/ruin/building),
  `/charon plot approve <name>` (admin) promotes an exported build into generation,
  `/charon plot list` shows categories and pending/approved builds
- Graves remember their headstone variant: approved headstone templates are pasted per
  grave (stable choice per grave), placeholder otherwise; `/charon rebuild-graves`
  re-terraces and re-pastes the whole yard from the records to upgrade old graves
- Field gate signs glow and are written on both faces

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
- Obol crafting (runtime-injected so the result keeps its components): 1 echo shard +
  1 gold ingot + 1 soul sand → 1 Charon's Obol
- Charon's Obol item (marked vanilla echo shard with glint + lore, crossplay-safe):
  soul-bound — the one item death cannot take; `/charon obol [count]` gives them
  (crafting/buying later). Renamed from "Echo Shard" — vanilla already has one.
- Commands: `/charon studio`, `/charon export [name]`, `/charon place <name>`,
  `/charon visit`, `/charon back` (gamemaster-level)
