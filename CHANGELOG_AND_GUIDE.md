# Projection Sorcery Unlimited - Changelog & Guide (1.0.2 BETA)

NeoForge 1.21.1 | Java 21 | Inspired by Arlo's/Orlan's Projection Sorcery (Fabric), written from scratch.
**Neither version has been compiled or tested** (the build sandbox could not reach the NeoForge Maven). Expect to fix a few compile errors on first build.

---

## Conversation summary

1. You asked for a NeoForge 1.21.1 Projection Sorcery mod like the Fabric one: plan a route, replay it - but with unlimited frames and CE, free flight in every direction while planning, insta-break blocks, damage mobs, and a quick-move key.
2. You uploaded the Fabric jar; it was only used to understand the idea (phases IDLE / PLANNING / REPLAY, attack frames). No code was copied (it is All Rights Reserved).
3. -> **1.0.0 RELEASE** was delivered.
4. You then asked for a physics-based rewrite with cursed-energy costs, anime-accurate limits, multiplayer rules, targeting, touch, afterimages and a settings GUI.
5. -> **1.0.1 BETA**.
6. You then asked for server-side path validation (3 blocks off the course, 5 while jumping) plus corrections: tick rate 1 instead of freeze, no opOnly, red victim blocks, and Naoya/Naobito in-world looks and screen overlays.
7. -> **1.0.2 BETA** (this version).

---

## 1.0.0 RELEASE (first version)

- R: start planning / execute. X: cancel. V: quick move (server-side blink in the look direction).
- Planning = a detached free camera (noclip, any direction, Space/Shift = up/down).
- Unlimited frames (plans streamed to the server in chunks). CE shown as infinity, no CE system.
- Replay teleports the player along the recorded path; attack frames damage mobs on a ray and break blocks at the ray hit (any block except bedrock).
- Player invulnerable while planning/replaying.

## 1.0.1 BETA - what changed

**Planning**
- The free camera is replaced by a **temp body** that copies your stats (speed, jump, gravity, jump boost) and uses real Minecraft collision. It has: infinite **air jumps** (each costs `jumpHungerCost` = 0.5 hunger, configurable), **3 block step height** (slabs/stairs/full blocks), **walks on water**, and is **not slowed** by powder snow, water or other block slowdowns.
- The world is **/tick frozen** for the whole planning session (everything except players).
- LMB on a block = **insta-mine mark**: the block disappears locally so you can plan through it, and is really broken when the plan runs. **No tool durability is used.** LMB on a mob = attack frame.
- Insta-mine respects the anime limits: unbreakable blocks and blast resistance above `maxBlastResistance` (32) cannot be broken.

**Cursed energy (all in config)**
- `infinite` (default true). If false: max/regen configurable, 15 CE per frame, +45 per attack frame, +150 per broken block. Running out mid-replay = **freeze**.
- **Freeze** = no movement/camera for 1 s, 2 glass blocks around you (feet + head), glass-break sound when placed and removed.

**Multiplayer**
- Two players starting planning in the same tick: **both cancelled**. Otherwise first come, first served.
- With more than 2 players online: 5 s cooldown and 3 s max planning time (auto-executes at 0). All configurable.

**Targets**
- Z = target what you look at (entity or block). **Client only** - nobody else is told. Shown as a red box.
- In planning mode, up to 4 routes to the target's **back** (or to the block) are drawn. Aim at one (it lights up) and **right click** to run it instantly.
- B = **quick move to target**: same routes, shortest one runs immediately.

**Quick move (V)** now uses the same path pipeline as everything else (see below) instead of a server blink: straight path where you look, 1 block per frame, costs CE per frame.

**Touch (C)**
- While planning, within 3 blocks of a mob/player: planning pauses, you see through the victim's body and plan up to 10 blocks for them. Press C again (or hit the limit) to return and continue.
- If your plan's path brings you within 3 blocks of the victim, the touch lands. Player victims get 2 s to notice, then 1 s per planned block (timer resets on every block); timer hits 0 = freeze. Mobs are simply forced along the path.

**Afterimages**: your planned frames and the victim's planned frames are drawn as translucent silhouettes with a highlight box, colored by theme.

**Settings GUI (K)**: color theme Naoya / Naobito (more later: add an entry to `Theme.java`).

## 1.0.2 BETA - what changed

**Server-side path validation** (`PathValidator`)
- While replaying, every frame is checked *before* the player is moved. The server runs its own body toward the claimed position using real block collisions, your speed / jump / gravity, infinite air jumps, 3 block step height and water walking (the same rules as the planning body). It reads the live world, so blocks insta-mined earlier in the replay are really gone for it.
- A frame is accepted if it is **at most 3 blocks** from where that simulated course got to, **5 blocks while airborne/jumping** (config: `pathValidation.tolerance`, `toleranceJumping`, `enabled`).
- On a bad frame the replay stops where the player is and they get "Path rejected: not physically possible."
- Consequence: **quick move is now a walking-pace path** (0.28 blocks/frame). A 1 block/frame dash would be rejected after a few blocks. Raise `replayFramesPerTick` if you want it to feel faster.

**Time**
- Planning now sets the server to **tick rate 1** (set directly through the tick-rate manager, so no "/tick" chat feedback is broadcast) instead of `/tick freeze`. It is set back (to whatever it was, normally 20) **the moment the plan is executed**, cancelled or times out, not when the replay ends.
- Your own client keeps running at 20 TPS while planning (it resets the tick rate it is told about every frame); the server and everything else is at 1 tick/s.
- Session time limit and cooldown now use the wall clock (the server only ticks once a second while planning).

**Other**
- `opOnly` removed. Anyone can use it; the path validation is the safeguard.
- Blocks planned for the victim are drawn **red** whatever the theme (the victim's required blocks, the next one brighter; the planner sees them red too).
- **Naoya theme:** afterimages are translucent cyan-blue (alpha about 0.65); your whole screen gets a blue overlay (alpha about 0.45) while planning.
- **Naobito theme:** afterimages have a fully transparent body with solid white outlines (the model's edges); your screen gets a light black tint (alpha about 0.15).
- Reading of your numbers: I took "transparency" as the *fraction that is transparent* (0.3-0.4 -> alpha 0.6-0.7, 0.5-0.6 -> 0.4-0.5, 0.8-0.9 -> 0.1-0.2). If you meant the opposite, change `Theme.java` (ghostAlpha / overlayAlpha).

## Where the single path function lives
`PathPlanner.fromPoints(...)` turns points into frames; `ClientEvents.sendPlan(...)` sends them. Target routes, quick move to target, right-click routes and quick move all go through these two.

---

## Guide

| Key | Action |
|---|---|
| R | Start planning / execute the plan |
| X | Cancel planning |
| WASD, Space, Ctrl | Move the temp body; Space again in the air = air jump (hunger); Ctrl = sprint |
| LMB (planning) | Attack frame / insta-mine a block |
| RMB (planning) | Run the highlighted target route |
| C | Touch (start / end the victim plan) |
| Z | Set / clear target |
| B | Quick move to target |
| V | Quick move where you look (walking-pace validated path, costs CE per frame) |
| K | Settings GUI |

Typical combo: Z on an enemy, R, hover a route, RMB. Or: R, fly to a wall, LMB the wall, walk through the hole, LMB the enemy, R.

Config files: `projectionsorcery-common.toml` (gameplay, per world/server) and `projectionsorcery-client.toml` (theme).

---

## Decisions I made where your spec was open

- The tick rate is a server-wide setting, so while someone plans the whole server (including other players) runs at 1 tick/s. Their clients are told about it by vanilla and slow down too; I cannot hide that. That is why the multiplayer limits exist.
- "0.5 hunger" is applied as exhaustion (4 exhaustion = 1 hunger point), so saturation drains first.
- Hunger spent on jumps is not refunded when you cancel.
- Touch triggers the first time you get within 3 blocks of the victim during the replay.
- Only blocks you stood on count as the victim's "planned blocks".
- Cooldown starts when any session ends (execute, cancel, timeout).
- Theme colors are my guess (Naoya gold, Naobito violet) - edit `Theme.java`.
- Afterimages are flat-colored silhouettes (no armor/skin) so they work for every mob.

## Expected bugs / limitations

- Not compiled. Most likely trouble spots: vanilla method names in `Sim`/`Render`/`TintConsumer`, `RenderLevelStageEvent` pose handling, the `Screen.render` background.
- Path finder is simple: slabs/stairs count as non-standable ground, max ~6000 nodes, may find no route in complex terrain.
- 3-block step height uses vanilla's step algorithm and can look odd near ceilings.
- Water walking can jitter on flowing water.
- Replay ending inside terrain lifts you up to the first free spot (can be far in caves).
- Path validation is an approximation (simple collision sim, same ~3/5 block tolerance): a valid but unusual path (e.g. unusual blocks like ladders, vines, scaffolding, bubble columns) may be rejected; cheats within the tolerance pass.
- After you execute, the first server tick can take up to 1 second (the server was waiting out a 1 tick/s interval), so the replay may start slightly late.
- Naobito's outline draws every quad edge of the model, so you see edges of hidden faces too.
- I could not look at reference images (image search is not allowed for anime content and the text search had no color info), so the theme colors follow your description only.
- Insta-mine does not ask claim/protection mods for permission (only vanilla spawn protection/game mode).
- Camera lock during freeze relies on the client; the server also pulls you back if you move.
- Other mods that tick-freeze or teleport players may conflict.
