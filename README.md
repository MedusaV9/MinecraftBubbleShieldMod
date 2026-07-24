# Bubble Shield

A [Fabric](https://fabricmc.net/) mod for Minecraft 26.2 that adds deployable **bubble shields**: translucent
force-field bubbles in ten shapes (spheres, domes, cylinders, cubes, diamonds, rings, pyramids, lenses,
hourglasses and stars) projected from a furnace-like block. Every shield intercepts hostile projectiles;
depending on the mode it also keeps hostile players and mobs out — **Defense** blocks and expels both,
**Pulse** blocks entry (and zaps mobs already inside), **Eco** blocks players only — while always letting
your friends walk right through.

## What is a Bubble Shield?

Craft and place a **Bubble Shield Projector** (a furnace-like machine block), fuel it up and activate it to
raise a shield around it:

- **Fuel**: the projector burns coal (80 s), charcoal (80 s), coal blocks (800 s), blaze rods (120 s) or lava
  buckets (1000 s). An active shield drains stored fuel-seconds while running and collapses when fuel runs
  out (bigger bubbles burn more; Eco mode and the Flux Capacitor slow the drain — see the exact rule below).
- **Size**: the shield diameter is configurable in the projector GUI from 8 up to a maximum of **200 blocks**.
- **Shape**: a GUI cycle button picks one of **ten shapes** — the classic full **sphere**, a **dome** (upper
  hemisphere only — open below the projector's center plane, so anything at or below that height passes
  underneath freely), **cylinder**, **cube**, **diamond**, **ring** (open through the hole), **pyramid**,
  **lens**, **hourglass** (open beside the pinched waist) and **star**.
- **Beam style**: a GUI selector picks the central energy column rising from the projector — **None**,
  **Auto** (each effect resolves to its own coherent preset) or one of the **8 rendered styles**: storm,
  pulse, helix, prism, void, ember, runic and frost (each with its own hand-written beam shader).
- **Whitelist**: the owner and any whitelisted players (added by name in the GUI, matched case-insensitively
  by name or UUID, up to **64 combined name/UUID entries**) pass through freely; everyone else is pushed
  back at the boundary.
  The shield surface dissolves in a bubble around approaching whitelisted players.
- **Health**: max health scales with the bubble's **size and tier** — from 125 for a tiny uncored shield up
  to thousands of HP for a huge Aegis-cored one (see the tier table below). Projectile hits damage the
  shield; at **60% health or above the bubble holds its full radius**, below that it shrinks proportionally
  (never under a 4-block radius). Below **25% health** the shield enters *last stand*: incoming damage is
  halved, fuel drain doubles and a heartbeat thumps at the projector. When health is depleted the shield
  breaks with a **shockwave nova** (8 magic damage plus strong knockback to every hostile mob inside) and
  the projector enters a break cooldown of **15 / 10 / 6 / 3 minutes** by tier; a bell ping announces when
  it is ready again.
- **Mode**: a GUI cycle button switches between **Defense** (blocks players *and* hostile mobs, expelling
  any already inside), **Pulse** (every 3 seconds, hostile mobs inside the bubble take 2 magic damage and a
  small outward knockback; each pulse that hits something burns one extra fuel-second; mobs are blocked at
  the boundary but not expelled — they are the zap's prey) and **Eco** (passive drain halved, radius capped
  at 0.75x, tier regeneration suppressed, no mob barrier). The Wither and the Ender Dragon are exempt from
  the mob barrier (they still take pulse and nova damage).
- **Combined drain rule** (Eco x Flux Capacitor): the active shield pays a drain event every
  `20 x (Eco ? 2 : 1) x (Capacitor ? 2 : 1)` ticks, **capped at 80 ticks** — i.e. every second plain, every
  2 seconds with either Eco mode or a Flux Capacitor, and every 4 seconds (the cap) with both. Each event
  burns `max(1, round(diameter / 50))` fuel-seconds (1 up to diameter 74, scaling to 4 at 175+), doubled
  while in last stand.
- **Strength gamerule**: `/gamerule bubbleshield:strength <percent>` (10–500, default 100) scales the max
  health of every shield on the server.
- **Effect cycle**: an optional toggle (in the effect picker) that re-rolls the active shield's effect to a
  random different one every 30 seconds.
- **Custom name**: the owner can name the shield in the GUI (*Name...*, up to 32 characters); the name shows
  on the boss bar and in-bubble HUD, and clearing it falls back to the effect name.
- **Boss bar**: everyone standing inside an active shield sees a boss bar tracking the shield's health,
  named after the shield (custom name or effect name) with a health readout in 5% steps ("· NN%"), colored
  to match the effect palette (or the owner's dye override). Below 25% health the bar switches to a notched
  style, and while the shield is under attack the name carries an "UNDER ATTACK!" suffix.
- **GUI readout**: the projector GUI shows the live numbers — HP current/max, regeneration and fuel drain
  per minute, tier with combined damage resistance, threat count and a mm:ss cooldown timer; hovering the
  Activate button previews max HP, resistance and fuel cost (or the remaining cooldown).
- **Dye color override**: a GUI color picker (*Color...*) recolors the bubble surface, HUD bar, particles
  and boss bar with any of the 16 dye colors, or resets to the effect's authored palette. Caveat: the
  in-bubble **screen post-effect keeps the effect's own authored colors** (they are baked into the static
  post_effect JSON uniforms), so only the world-side visuals recolor.

### Upgrade cores, tiers and regeneration

The projector has a second slot for an **upgrade core**, which sets the shield's tier:

| Tier | Core | Base HP | Damage resistance | Regen (HP per 2 s) | Break cooldown |
|------|------|---------|-------------------|--------------------|----------------|
| 0 | — | 200 | 0% | 1 (out of combat only) | 15 min |
| 1 | **Resonant Core** (amethyst/iron/diamond) | 400 | 25% | 3 | 10 min |
| 2 | **Prismatic Core** (diamonds/amethyst blocks around a Resonant Core) | 700 | 40% | 6 | 6 min |
| 3 | **Aegis Core** (echo shards/netherite ingots around a Prismatic Core) | 1200 | 50% | 12 | 3 min |

The effective max health is `base HP x (0.5 + diameter / 64) x strength%`, clamped to 50–8000 — at the
default 32-block diameter the base values apply unchanged, and a 200-block Aegis shield reaches 4350 HP.
Tier damage resistance reduces every projectile hit before it reaches the health bar.

Regeneration pulses once per 2 seconds of active runtime while fueled: **out of combat** (not hit for
10 seconds) tiers 1–3 heal at **3x** their rate, while tier 0 only regenerates out of combat at all. Eco
mode suppresses regeneration, resonance-linked shields heal **1.25x** per pulse, and each pulse burns one
extra fuel-second on top of the normal drain (unless a Flux Capacitor is installed). Removing the core
drops the tier immediately; across any max-health change (core swap, resize, strength gamerule) the
current HP is preserved as an **absolute value** — clamped into the new maximum, never proportionally
rescaled.

Besides crafting, structure loot carries the shield gear: End City treasure and Ancient City chests each
have an extra 1-in-10 chance to contain a **Resonant Core**, End City treasure a 1-in-20 chance for an
**Aegis Core**, and Ancient City chests a 1-in-8 chance for 1–2 **Patch Kits**.

### Flux Capacitor

A third slot takes a **Flux Capacitor** (crafted from copper ingots, redstone blocks and quartz). While
installed it **halves the passive fuel drain** (see the combined drain rule above) and makes tier
regeneration pulses **fuel-free** (they no longer burn the extra fuel-second).

### Augment slot: Reinforced Plating or Blast Ward

A fourth slot takes exactly **one** defense module — a strategic either/or choice:

- **Reinforced Plating** (iron ingots around obsidian): **-30% damage from every projectile hit**, stacking
  multiplicatively with the tier resistance; the combined resistance is capped at **70%**.
- **Blast Ward** (obsidian/bricks/netherite scrap): **explosive** projectiles (fireballs, wither skulls,
  wind charges) deal **60% less** shield damage, applied before the tier/plating resistance.

### Field repairs: Patch Kit and emergency revive

- **Patch Kit** (2 amethyst shards + slime ball + copper ingot, crafts 2, stacks to 16): right-click an
  **active** projector to restore **150 shield HP** (owner or whitelisted players only; the kit is consumed
  only when at least 1 HP is missing), or a **broken** (cooling-down) projector to cut the remaining
  cooldown by **20% of the full break cooldown** — repeated kits stack.
- **Emergency revive**: while the projector is on break cooldown (with at least 10 seconds remaining) and
  the tier-scaled fee of **400 + 200 x tier fuel-seconds** (400/600/800/1000 for tiers 0–3) is stored, the
  owner's Activate button becomes *Revive (-N fuel)* — pressing it consumes the fee and restarts the shield
  at **50% health**. Only **one revive per break cooldown**: the cooldown clock keeps running quietly in
  the background rather than being cleared.

### The shield fights back

- **Arrow riposte** (tier 1+): intercepted arrows with a known shooter are **reflected straight back** at
  them (re-owned to the shield's owner, no pickup) instead of absorbed.
- **Siege alarm**: a projectile interception, or threats appearing near an active shield, rings a bell at
  the projector, pins the comparator output to 15 for 5 seconds and marks the boss bar "UNDER ATTACK!" (at
  most one alarm per 15 seconds). Threats — non-whitelisted players plus hostile mobs inside the bubble or
  within 8 blocks beyond it — are counted once per second and shown in the GUI.
- **Threat log**: the last 8 intercepted attackers (name, damage, time) are remembered per projector and
  readable via `/bubbleshield log`.
- Last stand, the break shockwave nova and the hostile-mob barrier are described under *Health* and *Mode*
  above.

### Resonance linking

Two or more **active shields with the same owner whose spheres overlap** form a resonance link: intercepted
projectile damage is **split evenly** across all linked shields (each then applies its own damage
resistance to its share), linked shields **regenerate 1.25x** per pulse, and an END_ROD particle tether
connects the projectors. Linking is not transitive (only shields directly overlapping the hit shield share
its damage), uses the current health-shrunk radii, and ignores shape (domes link by their full sphere
radius).

### Comparator, redstone and sculk

- **Comparator output**: while active, the signal is the shield's health fraction on a 1–15 scale; while
  inactive, it reports stored fuel (one signal step per 200 fuel-seconds, capped at 15). A siege alarm
  overrides the output to 15 for 5 seconds, so a comparator can trigger base defenses.
- **Redstone control**: a rising redstone edge activates a fueled projector, a falling edge deactivates it.
  Edge-triggered, so GUI toggling still works independently while powered.
- **Sculk game events**: a real activation emits `BLOCK_ACTIVATE` and a real deactivation emits
  `BLOCK_DEACTIVATE` (no-op re-toggles emit nothing), so sculk sensors can hear the shield switching.

### The /bubbleshield command

- `/bubbleshield list [page]` — browse the effect catalogue, 10 "id: Name" entries per page.
- `/bubbleshield info <id>` — print an effect's surface, inside behavior, guard style and context profile.
- `/bubbleshield set <id>` — retune the nearest projector within 16 blocks to the given effect. Owner-gated
  with the same claim rule as the GUI; the id is clamped into the catalogue range.
- `/bubbleshield status` — the nearest owned projector's full stat sheet: HP, tier and combined damage
  resistance, regen and drain per minute (with a time-to-empty projection), cooldown, strength gamerule and
  live threat count.
- `/bubbleshield log` — the nearest owned projector's threat log (the last 8 intercepted attackers).

### Projectile interactions

Projectiles from non-whitelisted shooters are intercepted at the surface, by type:

- **Arrows**: **riposted** straight back at the shooter at tier 1+ when the shooter is resolvable;
  absorbed (removed) at tier 0 or when ownerless (e.g. dispenser-fired) — 3 shield damage either way.
- **Any other unclassified projectile**: always absorbed, 3 damage.
- **Tridents**: too heavy to absorb — **reverse-deflected** back out, 4 damage.
- **Fireballs, wither skulls, wind charges**: reverse-deflected (no explosion inside), 10 damage (x0.4 with
  a Blast Ward).
- **Thrown items** (snowballs, potions, **ender pearls** — no teleporting through!) and shulker bullets:
  fizzle out, 1 damage.

These are raw values: the tier and plating damage resistance (and last stand) reduce what the health bar
actually loses.

## The 840 effects

Every shield has one of **840 selectable effects** (ids 0–839, organized as 168 color families x 5 effects),
chosen in the projector GUI; hovering an effect button shows a tooltip describing its axes. Every effect is
an individually authored row in a flat catalogue (`EffectRegistry`) that combines **seven axes**:

1. **Palette**: a unique primary/secondary color pair.
2. **Surface layer** (client): **its own dedicated procedural surface shader**
   (`assets/bubbleshield/shaders/bubble/fx_000.fsh` … `fx_839.fsh`, one render pipeline per effect).
   Each shader is generated from a library of ShaderToy-/iq-derived techniques (domain-warped FBM,
   exact-border voronoi, voronoise, caustics, curl-flow advection, thin-film iridescence, truchet/hex/tri
   lattices, kaleidoscopic folds, cosine palettes, silhouette-rim estimation, Kaliset fractal folds,
   volumetric transmittance marches, liquid-chrome environment reflections, refractive crystal panes …)
   composed into at least three distinct depth layers (parallax deep field + signature mid structure +
   rim/sparkle highlights), grouped into **60** technique families (the `SurfaceTemplate` metadata
   shown in tooltips).
3. **Inside layer** (server): one of **120 behaviors** x 7 variants — particle domes/spirals/orbits, regen /
   speed / haste / resistance / night-vision auras, slowness for hostile mobs, ember rain, snowfall, fireflies,
   mist, heartbeat and music pulses, rising souls, falling petals, bubble veils, static fields, meteor bursts,
   spore drift, enchantment streams, fire wards, intruder-freezing frost, purge pulses, leap/tide auras,
   ember guards, lucky charms, echo pulses, prismatic rays, void tendrils, honey drip, waxen glow, storm
   cages, gravity wells, aurora ribbons, sand devils, glass shards, moth swarms, rune orbits, dripping
   stalactites, geyser vents, static orbs, shadow veils, prism beams, pollen haze, tide pools, ember
   spirals, comet tails, the ghost suite (vex wisps, soul processions, phantom flocks, sonic
   ghosts, ender watchers, wandering spirits, graveyard mist, spectral shoals, wraith orbs, seance
   circles), plus the 60 v5 additions (abyssal jellies, alchemy circles, banshee wails, clockwork
   gears, comet orreries, eclipse discs, firework regattas, lantern festivals, mirror mazes,
   skeleton armies, valkyrie patrols, zodiac beams and many more).
4. **Guard style**: what happens to intruders expelled at the boundary — nothing, gust pushback, slowness,
   blindness, darkness, a glowing mark, or stinging magic damage.
5. **Context profile**: how the effect reacts to the world — steady, blooming at night, charged by storms,
   scaling with the crowd inside, frenzying at low shield health, or hue-shifting with health.
6. **Ambient sound**: a vanilla sound event with per-effect pitch and period, played from the projector.
7. **Screen layer** (client): **its own dedicated full-screen post-processing shader** applied while you
   stand inside the bubble (`assets/bubbleshield/shaders/screenfx/sfx_000.fsh` … `sfx_839.fsh`, wired
   through `assets/bubbleshield/post_effect/effect_00.json` … `effect_839.json`), drawn from **28** screen
   technique families — tint, wobble, vignette, chroma, pixelate, desat, bloomglow, ripple, scanlines,
   edgeglow, frostlens, heathaze, posterize, radialblur, glitch, duotone, kaleido refraction, hue drift,
   dream blur, moiré interference, spectral, aberration, underwater, thermal, sketch, starburst, vhs
   and gloom.

**Uniqueness guarantee** (machine-enforced by `EffectRegistry.validate()` and the gametests): all 840 palette
pairs are pairwise distinct, every behavior is used exactly 7 times covering variants {0 … 6}, no color
family repeats a surface family, screen family or behavior, no (surface family, screen family) pair appears
more than 3 times across the whole catalogue, every (sound, pitch, period) triple is unique, and no two
generated shaders are byte-identical (each has its own structural technique stack, recorded in
`tools/surface_manifest.json` / `tools/screen_manifest.json`). No two effects look, sound or behave the same.

### Generated shaders: workflow

All 1680 per-effect shaders (840 `fx_*.fsh` surface + 840 `sfx_*.fsh` screen) and the 840 post-effect JSONs
are emitted by deterministic, byte-stable generators — **never hand-edit generated `fx_*`/`sfx_*` files or
`effect_*.json`**; edits there are overwritten by the next regeneration. The workflow is:

1. Edit the generators: `tools/gen_surface_shaders.py`, `tools/gen_screen_shaders.py`,
   `tools/gen_post_effects.py`.
2. Regenerate: run all three scripts (each rewrites its full output set plus its manifest).
3. Validate: `python3 tools/validate_shaders.py` must exit 0 (glslangValidator compile check +
   byte-uniqueness + manifest/layer-marker + FxConfig-order invariants).
4. `./gradlew build` and `./gradlew runGameTest` must both pass.

## Living surface

The membrane is CPU-deformed geometry that reacts to the world, not a static ball:

- **Impact waves**: every hit wobbles the bubble — traveling damped waves (12 blocks/s along the
  surface) radiate from the hit point with a crest glow riding each wavefront. Wave amplitude grows as
  the shield weakens (up to 2.5x at low health), so a battered bubble visibly shudders.
- **Last-stand tremble**: below 25% health the whole surface shivers (~1.8 Hz, spatially de-synced), on
  top of the heartbeat.
- **Whitelisted aperture**: the wall *parts* for the owner and whitelisted players — a hole eases open as
  you approach (within 5.5 blocks of the wall) and seals when you leave (beyond 6.5 blocks; opening is
  snappy, closing deliberately slower), with the displaced mass bulging into a lip around the hole, a
  glowing rim ring, the surface pattern streaming aside (UV flow) and soft open/close chimes.
- **Passage ripple**: actually crossing the wall rings a whoosh and sends a ripple out from the crossing
  point — predicted instantly on the client, confirmed by the server's event batch a tick later.

## Volumetric membrane

The surfaces render as thick refractive matter rather than a screen-thin decal. Every generated fx
shader bakes a per-family optical treatment from five material groups (energy / crystalline / organic /
geo / celestial): a relative shell thickness that *chord-thickens toward the silhouette* (the limb
visibly deepens at grazing angles), Beer–Lambert absorption tinting the refracted scene behind the
membrane, parallax texture depth (two extra atlas taps), and a per-family **inner material** composited
on the bubble's back face — energy currents, crystalline scaffolds, organic fog banks or celestial
star depths.

## Interior specials

Every effect fills the bubble's interior with themed floating elements — billboarded sprites from two
generated sheets (crisp pixel-art + soft tinted glows), animated (orbit, bob, drift, fall, swim, blink…),
distance-LOD'd and budget-capped. Each of the 60 surface families maps to an interior treatment (embers,
star fields, film petals, glyphs, ghost veils, cage rings, void shells…), and ten signature effects carry
**novelty interiors**:

- **Aquarium (442)** — swimming fish and rising bubbles.
- **Rubber Duck Pond (526)** — rubber ducks bobbing on a waterline amid ripples.
- **Matrix Rain (575)** — falling glyph code with bright streaks.
- **Whispering Library (612)** — orbiting books and drifting runes.
- **Taco Fiesta (633)** — tacos orbiting and bobbing through the dome.
- **Lava Lamp (717)** — glowing blobs that rise and sink lazily.
- **Cat Cloud (728)** — perched, blinking cats while smoke wisps drift nearby.
- **Donut Drift (756)** — a slow ring-orbit of donuts with sprinkle motes.
- **Disco Dome (809)** — a top-center disco ball, sweeping light shafts and blinking sparkles.
- **Void Absolute (839)** — the maxed-out void: a dark inner dome shell, sparse stars and spiraling
  tendrils.

Interiors are visible from outside too, seen through the translucent membrane.

## Contact feedback

A blocked player pressing the barrier gets an instant full-screen edge flash in the shield's tint, its
style matched to the effect's screen family (color grade, chromatic offset, expanding rings, scanlines
or glow rims) and thickened on the screen side facing the wall, plus a personal slime squelch at the
player's post-expulsion position (the barrier pushes the player back out before the sound fires). The
flash is client-predicted (zero perceived latency) and reconciled against the
server's confirmed CONTACT events. Photosensitivity guards: it is a pure 2D overlay (no scene
distortion), peaks at 35% opacity, hard re-triggers are capped at 2 per second, and the
`flashIntensity` client config scales it down or off entirely.

## Impact audio

A projectile hit layers a four-sound stack at the actual hit point — a heavy-core thump whose pitch
scales with the damage, the shield-block ring whose pitch rises as health falls, and the effect
family's TWO surface-material layers from five sound groups (energy crackle, crystal chime, organic
squelch, tech click, void resonance) — rate-limited to one stack per shield per tick. The shockwave
then "travels through" the bubble: a warden-sonic tail rings out at the antipode (the far side of the
bubble) `2 + floor(radius/8)` ticks later, so bigger bubbles take audibly longer to traverse. Standing close to an
active wall also plays a soft proximity hum, portal-flavored for the void families.

## Client config

Client-only visual options live in `config/bubbleshield-client.json` (written with defaults on first
run; malformed values fall back per-field and are canonicalized):

- `interiorDensity` (0..1, default 1) — global multiplier on the interior element budget; 0 disables
  interiors entirely.
- `volumetricMode` (`OFF` / `LOW` / `FULL`, default `FULL`) — thins the fog-flagged interior layers
  (x0 / x0.5 / x1).
- `flashIntensity` (0..1, default 1) — contact-flash overlay and interior blink-envelope multiplier;
  0 disables the flashes.

## HUD, ambient sounds and advancements

- **In-bubble HUD**: while you stand inside an active shield, a top-center HUD element shows the shield
  name (custom or effect name), a 100px health bar with an absolute HP readout, tinted with the effect's
  primary color (or the dye override), and the shield tier (when cored).
- **Shield sounds**: beacon-style activation/deactivation cues, a hit sound whose pitch rises as the shield
  weakens, the last-stand heartbeat, the siege-alarm bell and a one-shot bell ping when a break cooldown
  naturally expires.
- **Advancements**: twelve advancements with custom criteria — obtaining a projector, raising a shield
  ("Shields Up!"), raising a 200-block-diameter shield ("Maximalist"), having your own shield shatter
  ("Bubble Burst"), whitelisting a friend ("Friend Zone"), naming a shield ("Christened"), dye-recoloring a
  shield ("Full Spectrum"), having two linked shields split damage ("Linked Up"), plus the Bulwark chain:
  activating a tier-1/2/3 shield ("Reinforced" / "Bastion" / "Aegis Bearer") and absorbing 500 damage on a
  single projector ("Unbroken").
- Full **English + German** localization for everything, enforced by a key-parity gametest.

## Building and running

Requires Java 25. All commands run from the repository root:

```bash
# Build the mod (also runs datagen-free resource processing); the jar lands in build/libs/
./gradlew build

# Run a headless dev server (EULA is pre-accepted in run/eula.txt)
./gradlew runServer

# Run the automated game tests (shield lifecycle, projectile interactions, whitelist,
# menu, tiers/strength/regen/damage-resistance, augments, patch kit/revive, combat
# behaviors (mob barrier, riposte, last stand, nova, siege alarm), flux capacitor
# economy, comparator/redstone, dome geometry, guard/context, advancements, boss
# bar/naming, shield modes/effect cycle, color override, resonance linking,
# command/sculk/loot integration, effect catalogue invariants, lang parity,
# post-effect assets, persistence, visual-event batches + impact audio, surface
# dynamics math (waves/aperture/tremble), interior themes + sprite sheets)
./gradlew runGameTest

# Compile-validate all bubble + screen-fx GLSL shaders with glslangValidator
python3 tools/validate_shaders.py
```

To try the mod in-game, run a dev client **on a machine with a GPU** (the surface and screen-effect shaders
need one; a headless VM will not render them):

```bash
./gradlew runClient
```

Then create a world, place a Bubble Shield Projector, right-click it, add fuel, pick a diameter, shape and
effect, and press *Activate*.

Note for worlds from older alphas with smaller catalogues: effect ids are clamped into the current 0–839
range and missing NBT fields default sensibly (shape = sphere, beam style = none, no core, no custom
name/color), so old saves load fine, though a previously selected effect may map to a different look.

## License

This mod is available under the CC0 license.
