# Bubble Shield

A [Fabric](https://fabricmc.net/) mod for Minecraft 26.2 that adds deployable **bubble shields**: translucent
force-field bubbles in ten shapes (spheres, domes, cylinders, cubes, diamonds, rings, pyramids, lenses,
hourglasses and stars) projected from a furnace-like block that keep hostile players and their
projectiles out while letting your friends walk right through.

## What is a Bubble Shield?

Craft and place a **Bubble Shield Projector** (a furnace-like machine block), fuel it up and activate it to
raise a shield around it:

- **Fuel**: the projector burns coal (80 s), charcoal (80 s), coal blocks (800 s), blaze rods (120 s) or lava
  buckets (1000 s). An active shield drains one fuel-second per second and collapses when fuel runs out
  (Eco mode and the Flux Capacitor slow this drain — see the exact rule below).
- **Size**: the shield diameter is configurable in the projector GUI from 8 up to a maximum of **200 blocks**.
- **Shape**: a GUI cycle button picks one of **ten shapes** — the classic full **sphere**, a **dome** (upper
  hemisphere only — open below the projector's center plane, so anything at or below that height passes
  underneath freely), **cylinder**, **cube**, **diamond**, **ring** (open through the hole), **pyramid**,
  **lens**, **hourglass** (open beside the pinched waist) and **star**.
- **Beam style**: a GUI selector picks the central energy column rising from the projector — **None**,
  **Auto** (each effect resolves to its own coherent preset) or one of the **8 rendered styles**: storm,
  pulse, helix, prism, void, ember, runic and frost (each with its own hand-written beam shader).
- **Whitelist**: the owner and any whitelisted players (added by name in the GUI, matched case-insensitively
  by name or UUID) pass through freely; everyone else is pushed back at the boundary. The shield surface
  dissolves in a bubble around approaching whitelisted players.
- **Health**: projectile hits damage the shield, and the shield **shrinks** as its health drops (never below a
  4-block radius). When health is depleted the shield breaks and the projector enters a **30-minute cooldown**
  (halved at tier 2) before it can be activated again.
- **Mode**: a GUI cycle button switches between **Defense** (the classic behavior), **Pulse** (every 3 seconds,
  hostile mobs inside the bubble take 2 magic damage and a small outward knockback; each pulse that hits
  something burns one extra fuel-second) and **Eco** (passive drain halved, radius capped at 0.75x, tier
  regeneration suppressed).
- **Combined drain rule** (Eco x Flux Capacitor): the active shield burns 1 fuel-second every
  `20 x (Eco ? 2 : 1) x (Capacitor ? 2 : 1)` ticks, **capped at 80 ticks** — i.e. every second plain, every
  2 seconds with either Eco mode or a Flux Capacitor, and every 4 seconds (the cap) with both.
- **Effect cycle**: an optional toggle (in the effect picker) that re-rolls the active shield's effect to a
  random different one every 30 seconds.
- **Custom name**: the owner can name the shield in the GUI (*Name...*, up to 32 characters); the name shows
  on the boss bar and in-bubble HUD, and clearing it falls back to the effect name.
- **Boss bar**: everyone standing inside an active shield sees a boss bar tracking the shield's health,
  named after the shield (custom name or effect name) and colored to match the effect palette (or the
  owner's dye override).
- **Dye color override**: a GUI color picker (*Color...*) recolors the bubble surface, HUD bar, particles
  and boss bar with any of the 16 dye colors, or resets to the effect's authored palette. Caveat: the
  in-bubble **screen post-effect keeps the effect's own authored colors** (they are baked into the static
  post_effect JSON uniforms), so only the world-side visuals recolor.

### Upgrade cores, tiers and regeneration

The projector has a second slot for an **upgrade core**:

- **Resonant Core** (tier 1, crafted from amethyst/iron/diamond): max health 200, and the shield
  **regenerates** 1.0 health every 2 seconds while active and fueled.
- **Prismatic Core** (tier 2, crafted from diamonds/amethyst blocks around a Resonant Core): max health 300,
  regenerates 2.5 health per pulse, and the break cooldown is **halved** to 15 minutes.

Each regeneration pulse burns one extra fuel-second on top of the normal drain. Removing the core drops the
tier (and clamps health) immediately.

Besides crafting, **Resonant Cores** also drop from structure loot: End City treasure and Ancient City
chests each have an extra 1-in-10 chance to contain one.

### Flux Capacitor

A third slot takes a **Flux Capacitor** (crafted from copper ingots, redstone blocks and quartz). While
installed it **halves the passive fuel drain** (see the combined drain rule above) and makes tier
regeneration pulses **fuel-free** (they no longer burn the extra fuel-second).

### Resonance linking

Two or more **active shields with the same owner whose spheres overlap** form a resonance link: intercepted
projectile damage is **split evenly** across all linked shields, and an END_ROD particle tether connects the
projectors. Linking is not transitive (only shields directly overlapping the hit shield share its damage),
uses the current health-shrunk radii, and ignores shape (domes link by their full sphere radius).

### Comparator, redstone and sculk

- **Comparator output**: while active, the signal is the shield's health fraction on a 1–15 scale; while
  inactive, it reports stored fuel (one signal step per 200 fuel-seconds, capped at 15).
- **Redstone control**: a rising redstone edge activates a fueled projector, a falling edge deactivates it.
  Edge-triggered, so GUI toggling still works independently while powered.
- **Sculk game events**: a real activation emits `BLOCK_ACTIVATE` and a real deactivation emits
  `BLOCK_DEACTIVATE` (no-op re-toggles emit nothing), so sculk sensors can hear the shield switching.

### The /bubbleshield command

- `/bubbleshield list [page]` — browse the effect catalogue, 10 "id: Name" entries per page.
- `/bubbleshield info <id>` — print an effect's surface, inside behavior, guard style and context profile.
- `/bubbleshield set <id>` — retune the nearest projector within 16 blocks to the given effect. Owner-gated
  with the same claim rule as the GUI; the id is clamped into the catalogue range.

### Projectile interactions

Projectiles from non-whitelisted shooters are intercepted at the surface, by type:

- **Arrows** (and anything unclassified): absorbed (removed), 5 shield damage.
- **Tridents**: too heavy to absorb — **reverse-deflected** back out, 4 damage.
- **Fireballs, wither skulls, wind charges**: reverse-deflected (no explosion inside), 8 damage.
- **Thrown items** (snowballs, potions, **ender pearls** — no teleporting through!) and shulker bullets:
  fizzle out, 2 damage.

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

## HUD, ambient sounds and advancements

- **In-bubble HUD**: while you stand inside an active shield, a top-center HUD element shows the shield
  name (custom or effect name), a 100px health bar tinted with the effect's primary color (or the dye
  override), and the shield tier (when cored).
- **Advancements**: eight advancements with custom criteria — obtaining a projector, raising a shield
  ("Shields Up!"), raising a 200-block-diameter shield ("Maximalist"), having your own shield shatter
  ("Bubble Burst"), whitelisting a friend ("Friend Zone"), naming a shield ("Christened"), dye-recoloring a
  shield ("Full Spectrum") and having two linked shields split damage ("Linked Up").
- Full **English + German** localization for everything, enforced by a key-parity gametest.

## Building and running

Requires Java 25. All commands run from the repository root:

```bash
# Build the mod (also runs datagen-free resource processing); the jar lands in build/libs/
./gradlew build

# Run a headless dev server (EULA is pre-accepted in run/eula.txt)
./gradlew runServer

# Run the automated game tests (shield lifecycle, projectile interactions, whitelist,
# menu, tiers/regen, flux capacitor economy, comparator/redstone, dome geometry,
# guard/context, advancements, boss bar/naming, shield modes/effect cycle, color
# override, resonance linking, command/sculk/loot integration, effect catalogue
# invariants, lang parity, post-effect assets, persistence)
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
