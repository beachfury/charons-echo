# Charon's Echo — Design Spec

**A server-side Fabric mod for the CurseForge Minecraft ModJam 2026 — "Echoes of the Past"**

When you die, your items don't scatter on the ground. Charon takes them. A smoking portal
rises at the site of your death, and your ghost must cross into **Charon's Echo** — an endless
misty graveyard dimension — to pay the Ferryman and reclaim what you lost. Every death leaves
a headstone. The graveyard *is* the history of your server.

- **Category:** Java Mods (Fabric, server-side only — vanilla clients, Bedrock crossplay via Geyser)
- **MC target:** 26.2 primary; 26.1.2 backport if time allows (jam requires 26.1+)
- **Jam deadline:** submissions close Sept 1, 2026. Mid-contest reward rounds Aug 4 & Aug 18 —
  core loop must be live on CurseForge before Aug 4.
- **Repo:** github.com/beachfury/charons-echo (required for judging)

---

## 1. The Death Loop (core state machine)

```
ALIVE
  └─ death event → intercept drops + XP → store as Grave record
DEAD (death screen suppressed, death sgui shown)
  ├─ body lies at death site (visual only)
  ├─ sgui: flavor text + [Respawn] button
  ├─ teammates may drop an Echo Shard onto the body (donation window)
  └─ [Respawn] clicked OR 60s timeout
GHOST (at death site, death portal placed nearby)
  ├─ invulnerable, invisible + soul-particle silhouette, flight, no interaction
  ├─ cannot pass through walls (no noclip / no spectator)
  └─ enters death portal
CROSSING (toll checkpoint)
  ├─ has Echo Shard → shard consumed, free passage
  └─ no shard → Charon's Toll (see §5)
IN CHARON'S ECHO (ghost, near own grave)
  ├─ soul wisp particle trail leads to the grave
  ├─ interact with headstone → items + XP restored (still ghost, items in inventory)
  ├─ optional: write epitaph (anvil-input sgui, filtered)
  └─ enters return portal (appears beside grave after reclaim)
RETURNED
  └─ alive at death site (safe-adjusted position), loop closed
```

Key decisions (locked):

- **Ghosts cannot pass through walls.** No spectator mode, no noclip. Adventure-style
  no-interaction + flight + invisibility.
- **Return portal goes to the death site** — not spawn, not bed. Beds no longer set respawn
  (they still skip night; decoration otherwise). Config toggle to restore vanilla bed behavior
  for servers that want it.
- **Death position is the anchor** for both the death portal and the return point, with a
  nearest-safe-block scan (see §12).

## 2. The Charon's Echo Dimension

A single runtime-created dimension (same approach as FabricPlots plot worlds) shared by the
whole server. Not per-player.

- **Terrain:** NOT flat — a custom chunk generator produces gentle rolling hills and shallow
  vales (simple layered noise, our code, no vanilla biome gen). Grave fields sit on flattened
  terraces cut into hillsides; the church crowns a rise at origin. A still, dark **River Styx**
  winds through the vales (optional flourish — thematically free).
- **No caves, no underground:** solid deepslate/tuff below the surface — no carvers, no
  ores, no getting lost. **Hydrology:** rivers are fine, small ponds are fine (local noise
  depressions, capped size); no oceans or large lakes — noise amplitude keeps water minor.
- **Sky:** dimension type uses `effects: minecraft:the_end` — the End's void skybox, which
  vanilla clients render on custom dimensions — plus `fixed_time` for unchanging gloom.
  No sun, no moon, no day cycle.
- **Palette (monochromatic — Pale Garden + Deep Dark only, plus soul-blue accents):**
  pale oak wood/planks/logs, pale moss blocks + carpet, pale hanging moss, deepslate variants,
  tuff, basalt, blackstone, calcite, bone blocks, cobwebs, gray/white candles, soul lanterns,
  soul fire, sculk + sculk veins as sparing dark accents. A custom biome definition tints
  grass/foliage/water/fog/sky to grays (biome colors are data-synced and client-rendered —
  works on vanilla Java clients; Bedrock falls back to nearest vanilla biome colors).
- **The Church:** generated at dimension origin on first creation. Gothic chapel — deepslate,
  blackstone, dark oak, candles, tinted/stained panes. Contains:
  - **Charon's Altar** — buy shards, pay ransom (§5), block-built hooded statue with skull
  - **Book of the Dead** — lectern, opens the death-ledger sgui (§8)
  - **Death of the Week plinth** — outside the entrance (§9)
  - **Charon's Vault** — item-frame/display wall behind the altar showing tolled items (§5)
- **The Graveyard — how graves are added:** the yard is built from **grave fields**: a
  40×40 terraced enclosure holding a 6×8 grid of 5×5 plots (48 graves), fenced, with a
  lych-gate, pale trees, and a path linking it to the church spine. Fields are allocated on
  a deterministic spiral around the church; within a field, plots fill row by row. Each
  death takes the next plot index → `(field, row, col)` → world coords; the terrace is cut,
  the headstone template pasted, the sign written. When a field's 48th grave is dug, the
  next field on the spiral is prepared (fence + gate + trees appear — the yard visibly
  grows).
- **Expanding world border:** vanilla per-dimension world border, centered on the church.
  Starts tight (~192 blocks, church + first two fields) and expands with a slow animation
  whenever a new field opens, sized to outermost-field + margin. The border's shimmer wall
  is the mist at the edge of the world; the graveyard literally grows as the server's dead
  accumulate. Living visitors and ghosts can never outrun the yard.
- **World rules:** no build, no break, no damage, no hunger, no natural mob spawns (only
  Gravekeepers, below), no explosions, no aggro of any kind. Living players may visit via
  the spawn portal (buy shards, pay respects, flower-vote, ransom items). Ghosts of other
  players are visible here — the social hub of the dead. **Admin bypass:** players with
  gamemaster permission are exempt from build/break restrictions and may use creative mode
  freely (checked via `permissions().hasPermission(...)` — the isOp() path silently fails
  for the single-player host).
- **Ambience:** custom biome carries Pale-Garden-style sound — near-silence, muffled mood,
  occasional creaking-wood and eerie ambient cues (biome ambience/music fields are
  data-synced to Java clients; Bedrock gets nearest-fallback). The mod layers positional
  sounds on top: distant bell tolls from the church, wind, whispers near old graves.
- **The Gravekeepers (passive mobs):** a small controlled population — **Creakings**
  drifting among the pale trees and **Wardens** slowly patrolling the field paths as blind
  caretakers. All fully passive: targeting/anger is cleared continuously, they never attack,
  never emit the Warden's darkness pulse to visitors (config to allow it for atmosphere),
  and deal no damage (world rule backstop). Spawn budget, not natural spawning: ~2 Creakings
  per field near trees, 1 Warden per 2–3 fields; persistent (no despawn), re-seeded on field
  creation. The Creaking's freeze-when-watched behavior is server-side AI and works untouched
  — statues that move when you look away, in a graveyard. Free.

## 3. Portals

Three portal types, all server-faked visuals (particle curtain + sound + block frame), entry
detected by position — same pattern as FabricPlots portals. No custom blocks needed.

| Portal | Where | Appears | Leads to |
|---|---|---|---|
| **Death portal** | near death site (safe-adjusted) | on death, for that ghost only | Charon's Echo, near your grave |
| **Return portal** | beside your grave | after you reclaim your items | your death site (safe-adjusted) |
| **Spawn portal** | world spawn, small shrine structure | permanent, generated on first server start | church entrance in Charon's Echo (and back) |

- Death/return portals are **one-shot and private**: only the owning ghost may use them, and
  they vanish the moment the ghost passes through. The death portal persists across
  chunk-unload/logout/restart until crossed (state is saved; visuals rebroadcast on login).
- **Visibility:** death/return portals place **no blocks in the living world** — they are
  per-player particle/sound broadcasts. Full portal visuals are sent only to the owning
  ghost. Other players see a faint soul-ember flicker at the site (config:
  `portal_trace_for_living: on/off`) — enough to say "someone died here" without clutter.
- Spawn portal is public, two-way, block-built (shrine template), usable by the living.
  Config: auto-generate shrine at spawn on/off + `/charon shrine place` command for manual
  placement on existing servers.

## 3b. The Studio — structure authoring workflow

No client-side custom assets exist in a server-side mod, so all landmark builds are
hand-built **structure templates** (.nbt) that the mod ships and pastes. The mod includes a
dev-only authoring mode so builds happen in parallel with coding:

- `/charon studio` — generates a creative authoring world: superflat pale-moss ground, a
  grid of labeled plots with sign markers and glass outlines showing exact footprint,
  orientation arrows, and anchor corners.
- `/charon export <name>` — saves the plot's build volume to
  `src/main/resources/data/charons_echo/structure/<name>.nbt`.
- `/charon place <name>` — paste any template for in-place review.

**Build list (plot sizes are maximums; anchor = north-west corner, entrances face south):**

| Template | Footprint | Count | Notes |
|---|---|---|---|
| `church` | 32×32×24h | 1 | altar (barrier/lodestone anchor block), lectern spot, vault wall behind altar — marker blocks define interaction points |
| `spawn_shrine` | 7×7×7h | 1 | overworld portal shrine; must look at home in any biome |
| `headstone_1..6` | 4×4×4h | 6 | cross, slab, obelisk, cairn, broken column, statue — each with a sign position marker |
| `plinth` | 5×5×6h | 1 | Death of the Week display, space for gilded headstone copy + candles |
| `lych_gate` | 5×3×5h | 1 | graveyard field entrances |
| `pale_tree_1..6` | 7×7×10h | 6 | dead/pale tree variants scattered on hills |
| `big_tree_1..2` | 11×11×14h | 2 | large pale trees for ridgelines |
| `styx_dock` | 9×5×6h | 1 | optional: Charon's ferry dock + boat on the river |
| `clutter_1..8` | 3×3×3h | ~8 | benches, urns, candle clusters, statues — sprinkled deterministically |
| `ruin_cottage` | 12×12×9h | 1 | decayed cottage — echoes of the folk who came before |
| `ruin_tower` | 7×7×12h | 1 | crumbling watchtower |
| `ruin_wall_a/b` | 7×3×4h | 2 | collapsed wall segments |
| `ruin_well` | 5×5×6h | 1 | abandoned village well |
| `ruin_arch` | 7×3×7h | 1 | free-standing broken archway |

Marker-block convention inside templates: structure voids / barrier blocks with agreed
positions mark sign placement, altar interaction point, plinth center, etc. The paste code
reads markers and replaces them with air + registers the interaction volume.

## 4. Echo Shards

The obol — Charon's fare, prepared while alive.

- **Soul-bound:** the only item that stays in your inventory through death (capped: 1 consumed
  per death; extras also persist — they're soul-bound as a class).
- **Crafting:** survival recipe, moderately cheap (working proposal: 1 amethyst shard +
  2 copper ingots + 1 soul sand → 1 Echo Shard; tune in playtesting). Recipe can be disabled
  in config for buy-only economy servers.
- **Buying:** at Charon's Altar. Default price in vanilla currency (config: item + count,
  default 4 gold ingots). API hook so economy mods/plugins can override the transaction.
- **Donation:** while a body lies in state (death sgui open), any player may drop an Echo
  Shard onto the body — it binds to the dead player and grants free passage. The kneel-and-pay
  co-op ritual.
- **Item identity:** a renamed/model-data vanilla item (server-side constraint). Working
  choice: amethyst shard with custom name/lore + glint. Must survive Geyser translation.

## 5. Charon's Toll (dying without a shard)

Charon always ferries you — but he takes his due. On portal entry without a shard, a weighted
roll decides the toll:

- **XP toll:** lose a percentage of stored XP (default 50% — "you had 400, now you have 200").
  Weighted more likely when the player has meaningful XP.
- **Item toll:** Charon takes the **most valuable item** in the grave (valuation table:
  netherite > enchanted diamond > diamond > ... ; enchantment count breaks ties; config-
  overridable value list). Weighted more likely when XP is low ("40 XP but fully kitted —
  it rolls, and he eyes the netherite").
- **The Vault (mercy valve):** tolled items are **not destroyed**. They hang in Charon's
  Vault behind the altar, visible to everyone — trophies of the unprepared. The owner may
  **ransom** them back at steep cost (default: 2 Echo Shards or 30 levels or economy price;
  config). Vault entries expire to permanent forfeiture after a config window (default 30 days,
  0 = never).
- **First-death grace:** a player's first death ever is free passage + explanatory flavor
  text (config on/off). Nobody gets tolled before they've had a chance to learn the shard
  exists.

## 6. Ghost State

- Invisible + **soul-particle silhouette**: a wispy column of SOUL/SMOKE particles broadcast
  at the player's position so the living see a drifting ghost with a nametag. Particles are
  plain server packets — crossplays cleanly.
- Flight (mayfly), spectral drift speed, slow-fall, no hunger drain, invulnerable, cannot
  attack / interact / pick up / drop / open containers.
- **Tagged dead:** every ghost carries the scoreboard tag `charon.ghost` (visible to admins
  and other mods/datapacks) and is placed on the `charon_dead` team — gray, italic nametag
  so the living instantly read them as dead.
- **Tethered in the living world:** a ghost may not roam. Movement is leashed to a radius
  around their death portal (default 24 blocks, config). Approaching the edge pushes the
  ghost gently back toward the portal with a darkness-vignette effect (mining-fatigue-style
  soft wall — velocity nudge, hard teleport at 2× radius as backstop). No scouting caves,
  no spying on bases, no ghost couriers.
- **Free movement inside Charon's Echo** — the dimension is no-interaction anyway, and the
  walk from arrival to grave through the misty graveyard IS the experience. The soul wisp
  leads the way.
- Cannot use other dimension portals (nether/end) — only Charon's portals.
- Ambient audio: occasional soul-sand-valley ambience + bell toll on state transitions.
- **Persistence:** ghost state, grave records, vault contents, epitaphs, flower votes all
  persist across logout/restart (flat-file JSON or NBT in world storage, FabricPlots-style).
  Logging out as a ghost logs you back in as the same ghost.

## 7. Graves & Headstones

- One grave record per death — multiple unclaimed graves per player is fine; each gets its
  own headstone and plot. The soul wisp leads to the **oldest unclaimed** grave first.
- Headstone: block-built variant + wall sign (auto line: name / cause of death / in-game day)
  + player head where Geyser renders it acceptably.
- **Epitaphs:** after reclaiming, an anvil-input sgui (crossplay-safe) offers 1–2 lines,
  \~40 chars each. Filter pipeline: lowercase → leet normalization (4→a, 3→e, 0→o, $→s, …) →
  configurable blocklist match (default list shipped) → length cap. Server config:
  `epitaphs: filtered | disabled | op-approval`. Blocked epitaph → plain headstone (auto
  line always shows).
- **Reclaimed graves persist as empty memorials** — the epitaph stays. This is the "echoes"
  payoff: the graveyard only ever grows, and space is infinite in the dimension.
- Grave protection: only the owner may reclaim. Others may inspect (sneak-click → epitaph +
  death info chat/sgui) and leave flowers.

## 8. Book of the Dead

Sgui ledger at the church lectern (VVC Guide tech):

- Every death ever: player, cause, day, epitaph, shard-or-toll, claimed/unclaimed.
- Filter/browse pages: by player, by cause, most recent.
- **Hall of Legends** page: past Death of the Week winners.
- Server stats page: total deaths, most deaths, most flowers received, Charon's total takings.

## 9. Death of the Week

- **Flowers are votes.** Any player (living visitor or ghost—no, ghosts can't interact:
  living visitors only) sneak-uses a grave with a flower in hand → flower consumed, +1 vote,
  small particle burst. One vote per player per grave per week.
- Weekly rollover (config: real-time weekly, default Monday 00:00 server time): most-flowered
  grave of the week is enshrined on the plinth by the church door — gilded headstone copy,
  candles, glow. Previous winner archived to Hall of Legends.
- Zero moderation cost; funny deaths win because friends flower-bomb them.

## 10. Config Surface (initial)

```
core:
  enabled_worlds: [overworld, nether, end]   # where the mod intercepts deaths
  pvp_deaths_vanilla: false                  # true = PvP kills drop loot vanilla-style
  respect_keep_inventory: true               # gamerule keepInventory bypasses the mod
  bed_respawn: false                         # true = restore vanilla bed respawn
death:
  respawn_timeout_seconds: 60
  first_death_free: true
ghost:
  speed_multiplier: 2.0
  particles: true
shards:
  crafting_enabled: true
  buy_price: { item: gold_ingot, count: 4 }
toll:
  xp_percent: 50
  item_valuation: <table>
  roll_weighting: <curve vs stored XP>
  vault_ransom: { shards: 2, levels: 30 }
  vault_expiry_days: 30
graveyard:
  epitaphs: filtered            # filtered | disabled | op-approval
  epitaph_blocklist: [...]
  death_of_week: true
  week_rollover: MON_0000
structures:
  spawn_shrine_auto: true
```

## 11. Crossplay Notes (Geyser/Bedrock)

- All mechanics are server-side: portals are particles + detection volumes, ghosts are
  effects + packets, UI is sgui (chest/anvil GUIs — proven crossplay in VVC Guide).
- Soul particles, sounds, signs, block-built structures: all fine on Bedrock.
- Player heads on headstones: Geyser rendering is inconsistent — headstone variants must
  look complete *without* the head; head is garnish.
- Invisibility + nametag ghosts render fine via Geyser; verify particle silhouette density
  (Bedrock particle budget is lower — config for reduced density).
- Anvil-input epitaph GUI: works via Geyser (it translates to a Bedrock form) — verify in
  testing on 26.x Geyser.
- End skybox + custom biome tints: Java clients render both from synced registries. Geyser
  maps custom dimensions to a vanilla one — verify which skybox Bedrock players get and
  accept nearest-fallback biome colors there. The block palette carries the mood regardless.

## 12. Edge Cases

- **Unsafe death/return spots:** spiral safe-block scan (solid floor, 2 air, no
  lava/fire/void) up to N blocks; fall back upward to surface. Brief resistance + slow-fall
  on return. Void deaths anchor to nearest solid ground above the void at death x/z.
- **Death inside Charon's Echo:** impossible (no damage) — but guard anyway: teleport to
  church, no new grave.
- **Death sgui + server stop:** timeout state persisted; on rejoin, player resumes as ghost.
- **Other mods fighting game mode / abilities:** mods like FabricPlots (creative-in-plots)
  and Dimensional Inventories (pool game modes) set game mode on dimension-change ticks.
  Ghost state must be re-asserted every few ticks / after any dimension change, never
  assumed from a one-time setup. (Found in testing: FabricPlots flipped players to survival
  when crossing plots → Charon's Echo; its `manage-gamemode=false` escape hatch avoids the
  fight on shared servers.)
- **keepInventory on:** mod stands down (config).
- **Totem of Undying:** fires before death — unaffected.
- **Ender dragon / respawn-anchor explosions / /kill:** normal loop; `/kill` while ghost is
  a no-op.
- **Hardcore mode:** out of scope (vanilla spectator takes over); documented.
- **Grave DB growth:** records are tiny (JSON); memorial rows are blocks in an anvil-cheap
  flat dimension. No cap needed; admin command `/charon purge-claimed <days>` provided anyway.
- **Admin commands:** `/charon` root — shrine place, grave tp/list, vault list/return,
  epitaph remove, week rollover force, reload config.

## 13. Tech Stack

- Fabric, MC 26.2 (26.1.2 backport branch if time) — per the 26.x toolchain: unobf, no
  mappings line, Loom 1.17+, Gradle 9.6, JDK 25.
- Deps: **sgui** (death screen, Book of the Dead, epitaph input, altar shop). Runtime world
  creation the FabricPlots way. No Dimensional Inventories needed (ghosts carry nothing;
  shared inventory across the grave world is *required*).
- Persistence: JSON under `world/charons_echo/` (graves.json, vault.json, ledger.json,
  votes.json) with atomic writes.
- License: same as other BeachFury mods. **No AI attribution anywhere** (repo, commits,
  CurseForge page). Git identity: BeachFury <beachfury@spiltinkdesign.com>.

## 14. Build Phases vs Jam Timeline

**Phase 1 — Core loop (target: live on CurseForge by Aug 1, before Aug 4 mid-contest round)**
0. **Studio mode first** (`/charon studio` / `export` / `place`) so structure building runs
   in parallel with everything below; simple generated placeholders stand in until real
   templates land
1. Death interception + grave records + persistence
2. Ghost state (effects, particles, tag/team, tether, persistence)
3. Dimension creation (hill-noise generator, End sky, gray biome) + graveyard terracing +
   plot allocation + church paste
4. Portals (death/return/spawn) + one-shot/owner-only visibility + safe-placement scan
5. Reclaim at headstone + XP restore
6. Echo Shard item + crafting + soul-binding + toll (XP/item roll, no vault yet)
7. Death sgui (respawn button + timeout + shard donation)
8. README, CHANGELOG, GitHub repo, CurseForge page, jam submission form

**Phase 2 — The soul of it (before Aug 18 round)**
9. Epitaphs + filter pipeline
10. Book of the Dead sgui
11. Charon's Vault + ransom
12. Soul wisp grave guidance
13. Altar shard shop + first-death grace

**Phase 3 — Polish (before Sept 1)**
14. Death of the Week + flowers + plinth + Hall of Legends
15. Economy API hook
16. Bedrock/Geyser verification pass + particle budget tuning
17. Trailer/screenshots for the CurseForge page (moonlit graveyard money shot)
18. 26.1.2 backport branch (stretch)

**Testing rule:** build + commit locally; nothing is pushed to GitHub or published to
CurseForge until tested in-game and approved (per standing rule) — with the explicit
exception that the jam *requires* the repo and CurseForge listing to go live for Phase 1;
that publish happens only after in-game approval of the Phase 1 build.

## 15. Open Questions

1. Shard recipe ingredients/cost — placeholder above, tune in playtesting.
2. Item valuation table defaults for the toll roll.
3. Does the spawn shrine auto-build, or command-only by default? (Spec says auto, toggle off.)
4. Exact death sgui copy/flavor text — write during Phase 1.
5. Mod ID: `charons_echo`. CurseForge slug: `charons-echo`.
6. Include the River Styx + ferry dock, or cut for scope? (Pure flourish — costs one
   template and a river carve in the generator.)

## The Church & Crypt Standard (decided 2026-07-23, not yet built)

- **Anchor:** the owner sets the graveyard's spawn/anchor point in-world (command);
  default = origin plateau so the mod works with zero setup. The church ENTRANCE
  generates ~48 blocks NORTH of the anchor (32×32×24 footprint, faces south); the
  walk between anchor and doors is the approach. Anchor locks once the church exists
  (explicit regen command to move it).
- **One church, ever:** the church is a CATEGORY any set may contain (church-only sets
  are legal); config (`church-template=default/church`) selects exactly one as THE
  church. No variants, no mixing.
- **Crypt style follows the church's set** by default (`crypt-template-set` overrides).
  NO random mixing between rooms — each room LOCKS the style current at carve time and
  never retro-changes, so style switches create historical strata (old wings keep
  their look, new months continue in the new style).
- **Marker vocabulary** (code replaces markers with function at paste; decoration free):
  lodestone → crypt stairwell down; lectern → Book of the Dead (opens ledger);
  gilded blackstone + attached sign naming the vendor (`obols`, later `ransom`,
  `flowers`, ...) → typed vendor point; chiseled bookshelf → crypt day-shelf column.
  A legal church = right footprint + faces south + those markers. Unknown sign labels
  are decorative, never errors.
- **The Crypt:** infinite underground month-room library below y≈44 (always beneath
  riverbeds; solid deepslate zone). ONE room template standard (~21×21×8, doorways =
  3-wide arches centered on each wall by geometry, no markers needed) + a door-seal
  panel: arches stay sealed until the neighboring month-room is carved — the crypt
  visibly digs itself as months pass. Room variants allowed via the category/set
  system (hash-picked per room).
- **The MAIN crypt room** (bottom of the church stairwell) is the WEEK room: its 7
  required shelf columns are the rolling current week, one per day, re-targeted daily
  with no rebuilding. Month archive rooms branch off it through the sealed arches.
- **Shelves are indexes, not storage:** clicking a day-shelf opens that day's slice of
  the ledger; book entries open read-only (BookGui). Rooms never fill up.
- **Flower tributes render as PHYSICAL flowers accumulating on the plot** — the only
  color in the monochrome world is what the living leave behind; exact counts in the
  ledger tooltip (feeds Death of the Week later).

## Decisions log

- 2026-07-22: the fare item is **Charon's Obol** (vanilla already has an "Echo Shard" —
  name collision), built on the vanilla echo shard item for its sculk-teal texture.
  All spec references to "Echo Shard" as the fare item read as Charon's Obol.
  Command: `/charon obol [count]`.

- 2026-07-21: dimension is shaped terrain (hills/terraces), monochrome Pale Garden + Deep
  Dark palette, End skybox, gray-tinted custom biome. Landmark builds are hand-authored
  structure templates via Studio mode. Death/return portals are one-shot, place no blocks,
  full visuals owner-only (faint trace for others, config). Ghosts are tagged
  (`charon.ghost` + gray-name team) and tethered to ~24 blocks around their portal in the
  living world; free roam only inside Charon's Echo. Ghosts cannot pass through walls. Beds
  don't set respawn (config-restorable). Return portal → death site, never spawn/bed.
- 2026-07-21 (later): no caves/ores — solid underground. Rivers + small ponds only, no
  oceans/large lakes. Graves allocated as 48-plot spiral fields; vanilla world border
  centered on church expands as fields open. Pale-Garden silence ambience. Only mobs are
  passive Gravekeepers: Creakings among trees, patrolling Wardens; zero aggro, spawn-budget
  controlled, persistent.
