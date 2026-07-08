# Bubble Shield

A [Fabric](https://fabricmc.net/) mod for Minecraft 26.2 that adds deployable **bubble shields**: translucent
force-field spheres (or domes) projected from a furnace-like block that keep hostile players and their
projectiles out while letting your friends walk right through.

## What is a Bubble Shield?

Craft and place a **Bubble Shield Projector** (a furnace-like machine block), fuel it up and activate it to
raise a shield around it:

- **Fuel**: the projector burns coal (80 s), charcoal (80 s), coal blocks (800 s), blaze rods (120 s) or lava
  buckets (1000 s). An active shield drains one fuel-second per second and collapses when fuel runs out.
- **Size**: the shield diameter is configurable in the projector GUI from 8 up to a maximum of **200 blocks**.
- **Shape**: a GUI toggle switches between the classic full **sphere** and a **dome** (upper hemisphere only —
  open below the projector's center plane, so anything at or below that height passes underneath freely).
- **Whitelist**: the owner and any whitelisted players (added by name in the GUI, matched case-insensitively
  by name or UUID) pass through freely; everyone else is pushed back at the boundary. The shield surface
  dissolves in a bubble around approaching whitelisted players.
- **Health**: projectile hits damage the shield, and the shield **shrinks** as its health drops (never below a
  4-block radius). When health is depleted the shield breaks and the projector enters a **30-minute cooldown**
  (halved at tier 2) before it can be activated again.

### Upgrade cores, tiers and regeneration

The projector has a second slot for an **upgrade core**:

- **Resonant Core** (tier 1, crafted from amethyst/iron/diamond): max health 200, and the shield
  **regenerates** 1.0 health every 2 seconds while active and fueled.
- **Prismatic Core** (tier 2, crafted from diamonds/amethyst blocks around a Resonant Core): max health 300,
  regenerates 2.5 health per pulse, and the break cooldown is **halved** to 15 minutes.

Each regeneration pulse burns one extra fuel-second on top of the normal drain. Removing the core drops the
tier (and clamps health) immediately.

### Comparator and redstone

- **Comparator output**: while active, the signal is the shield's health fraction on a 1–15 scale; while
  inactive, it reports stored fuel (one signal step per 200 fuel-seconds, capped at 15).
- **Redstone control**: a rising redstone edge activates a fueled projector, a falling edge deactivates it.
  Edge-triggered, so GUI toggling still works independently while powered.

### Projectile interactions

Projectiles from non-whitelisted shooters are intercepted at the surface, by type:

- **Arrows** (and anything unclassified): absorbed (removed), 5 shield damage.
- **Tridents**: too heavy to absorb — **reverse-deflected** back out, 4 damage.
- **Fireballs, wither skulls, wind charges**: reverse-deflected (no explosion inside), 8 damage.
- **Thrown items** (snowballs, potions, **ender pearls** — no teleporting through!) and shulker bullets:
  fizzle out, 2 damage.

## The 105 effects

Every shield has one of **105 selectable effects** (ids 0–104, organized as 21 color families x 5 effects),
chosen in the projector GUI; hovering an effect button shows a tooltip describing its axes. Every effect is
an individually authored row in a flat catalogue (`EffectRegistry`) that combines **seven axes**:

1. **Palette**: a unique primary/secondary color pair.
2. **Surface layer** (client): one of **16** procedural animated shader templates — plasma, hex lattice,
   waves, aurora, sparkle, rings, voronoi, arcs, scales, starfield, vortex, interference, kaleidoscope,
   circuit traces, rose petals or branching lightning.
3. **Inside layer** (server): one of **35 behaviors** x 3 variants — particle domes/spirals/orbits, regen /
   speed / haste / resistance / night-vision auras, slowness for hostile mobs, ember rain, snowfall, fireflies,
   mist, heartbeat and music pulses, rising souls, falling petals, bubble veils, static fields, meteor bursts,
   spore drift, enchantment streams, fire wards, intruder-freezing frost, purge pulses, leap/tide auras,
   ember guards, lucky charms, echo pulses, prismatic rays, void tendrils, honey drip, waxen glow and
   storm cages.
4. **Guard style**: what happens to intruders expelled at the boundary — nothing, gust pushback, slowness,
   blindness, darkness, a glowing mark, or stinging magic damage.
5. **Context profile**: how the effect reacts to the world — steady, blooming at night, charged by storms,
   scaling with the crowd inside, frenzying at low shield health, or hue-shifting with health.
6. **Ambient sound**: a vanilla sound event with per-effect pitch and period, played from the projector.
7. **Screen layer** (client): one of **16** full-screen post-processing templates applied while you stand
   inside the bubble — tint, wobble, vignette, chroma, pixelate, desat, bloomglow, ripple, scanlines,
   edgeglow, frostlens, heathaze, posterize, radialblur, glitch or duotone
   (`assets/bubbleshield/post_effect/effect_00.json` … `effect_104.json`,
   regenerated by `tools/gen_post_effects.py`).

**Uniqueness guarantee** (machine-enforced by `EffectRegistry.validate()` and the gametests): all 105 palette
pairs are pairwise distinct, every behavior is used exactly 3 times covering variants {0, 1, 2}, no color
family repeats a surface, screen template or behavior, no (surface, screen template) pair appears more than
3 times across the whole catalogue, and every (sound, pitch, period) triple is unique. No two effects look,
sound or behave the same.

## HUD, ambient sounds and advancements

- **In-bubble HUD**: while you stand inside an active shield, a top-center HUD element shows the effect
  name, a 100px health bar tinted with the effect's primary color, and the shield tier (when cored).
- **Advancements**: five advancements with custom criteria — obtaining a projector, raising a shield,
  raising a 200-block-diameter shield ("Maximalist"), having your own shield shatter ("Bubble Burst") and
  whitelisting a friend ("Friend Zone").
- Full **English + German** localization for everything, enforced by a key-parity gametest.

## Building and running

Requires Java 25. All commands run from the repository root:

```bash
# Build the mod (also runs datagen-free resource processing); the jar lands in build/libs/
./gradlew build

# Run a headless dev server (EULA is pre-accepted in run/eula.txt)
./gradlew runServer

# Run the automated game tests (shield lifecycle, projectile interactions, whitelist,
# menu, tiers/regen, comparator/redstone, dome geometry, guard/context, advancements,
# effect catalogue invariants, lang parity, post-effect assets, persistence)
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

Note for worlds from the 50-effect alpha: effect ids are clamped into the new 0–74 range and missing NBT
fields default sensibly (shape = sphere, no core), so old saves load fine, though a previously selected
effect may map to a different look.

## License

This mod is available under the CC0 license.
