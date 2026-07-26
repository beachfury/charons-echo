# Charon's Echo

**Death, reimagined.** When you die, your items don't scatter — Charon takes them.
A soul-fire portal rises where you fell, and your ghost must cross into
*Charon's Echo*, an endless dusk-lit graveyard dimension, to stand at your own
grave and settle with the Ferryman. Every death digs a headstone. The graveyard
**is** the history of your server.

Built for the CurseForge **Minecraft ModJam 2026 — "Echoes of the Past."**

## The death loop

1. **You die.** Your body lies in state for a minute — friends can kneel and lay
   a **Charon's Obol** on you to pay your fare.
2. **You rise as a ghost** — invisible but for a wisp of soul-fire, tethered to
   where you fell, until you walk into the portal.
3. **You arrive at your own grave** in Charon's Echo, and touch the stone.
   Charon offers three ways back:
   - **Pay the fare** — one Charon's Obol (craft: echo shard + gold + soul sand,
     soul-bound through death; or buy from the Broker's orchard, below).
   - **Pay the toll** — a cut of your XP.
   - **Take the oath** — enlist in the War Below the Moon and pay with time.
4. **Resurrection at the stone** — items, XP, and a portal home to where you fell.
   Beds never mattered.

## The world of the dead

- **Server-side only** — vanilla Java clients and Bedrock players (via Geyser)
  join with no client mod and no resource pack.
- **A unique graveyard per world** — monochrome hills seeded from your world:
  pale moss, gravel scree, sculk vales, a winding river, hand-built withered
  trees, and grave fields that spiral outward as the dead accumulate, each
  behind its own lych gate with a living ledger sign.
- **The church** on the plateau: the **Broker** sells Stygian Seeds, the
  **Scrivener** hands out free books so the dead can write their stories, and
  the **Book of the Dead** rests on the lectern.
- **The crypt grows with the dead** — beneath the church, seven day-shelves
  hold a rolling week of the fallen, and the first death of every month breaks
  the seal and carves that month's hall. A year-old server has a corridor
  twelve halls deep.
- **Grave books and flower tributes** — the Scrivener hands out free books;
  write your death story into your stone. The living shift-click flowers onto
  graves (one per mourner — the only color in this world), and the most-mourned
  soul is crowned **Death of the Week**.
- **Memorials, not lists** — everywhere the dead are recorded, they appear as
  their own head, with their date, cause, grave location, and flowers. Click a
  memorial to read their story, or (from within the graveyard) walk straight
  to their stone.

## The Stygian Orchard

Buy a **Stygian Seed** from the Broker and plant it in the overworld. It grows
(in its own time) into a withered tree that hangs **Tollfruit** from its
chains — sculk seals each fruit, then it ripens into glowing amber. Four
Tollfruit craft one obol. The tree drops nothing else, ever: felling it
(netherite axe, a slow ritual) returns only its seed. Wild elders in the
graveyard bear fruit too — the one thing the living may take from the dead's
world. And some trees are more than they seem.

## The War Below the Moon

An eternal three-sided war, fought at the newest grave field: iron golem and
creaking **Keepers** hold the yards, the risen **Restless** tear at them, and
the **Hollow Wind** raids both. The war cannot see the living — walk through
it untouched. Only the enlisted dead are real to it: take the oath, receive a
loaned kit, fight (enemies downed shave your sentence), and serve out your
clock for a free resurrection. Enlisted players of opposing banners can fight
each other — defeat costs service time, never items. Filled fields become
settled ground; the front marches on.

## Requirements

- Minecraft **26.2** (Fabric)
- [Fabric API](https://modrinth.com/mod/fabric-api) and
  [sgui](https://maven.nucleoid.xyz) in the mods folder

Every timer, price, cap, and war knob lives in
`config/charons-echo.properties`, written as a documented manual.

## For builders: the Studio

All landmark builds (headstones, trees, gates, the church, crypt pieces) are
hand-built structure templates, authored in the Studio — a flat builders-only
world:

- `/charon studio` — enter (gamemasters and rostered gravekeepers only; add
  builders with `/charon builder add <player>`)
- Build inside a plot outline; your build's front faces its label sign. You may
  dig below grade — coffins, roots, floors, and foundations ship.
- `/charon plot new <category> <name>` stakes a plot,
  `/charon export [name]` captures it, `/charon place <name>` reviews it.
- Sets (style families): `/charon set new <name> [size]`, with a
  trust/approve/reopen lifecycle. Each graveyard region draws from ONE set.

**EXPORT = SAVE.** Studio builds are just blocks; templates are forever. The
Studio self-restores every exported build at server start.

## License

MIT
