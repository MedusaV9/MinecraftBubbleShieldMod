package com.bubbleshield.shield;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.bubbleshield.advancements.ModCriteria;
import com.bubbleshield.block.BubbleShieldBlockEntity;
import com.bubbleshield.effect.ContextModifier;
import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.ContextProfile;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.effect.behaviors.BehaviorSupport;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

/**
 * Server-authoritative shield behaviour: fuel drain, projectile interception,
 * player barrier and shield break/cooldown handling.
 */
public final class ShieldLogic {
	public static final float MIN_RADIUS = 4.0F;
	/** Absorb damage for arrows (and unclassified projectiles). */
	public static final float PROJECTILE_DAMAGE = 3.0F;
	/** Tridents are too heavy to absorb; they are reverse-deflected instead. */
	public static final float TRIDENT_DAMAGE = 4.0F;
	/** Fireballs/wither skulls/wind charges are reverse-deflected (no explosion inside). */
	public static final float HURTING_PROJECTILE_DAMAGE = 10.0F;
	/** Thrown items (snowballs, potions, ender pearls) and shulker bullets fizzle out. */
	public static final float THROWN_DAMAGE = 1.0F;
	/** Tier-0 break cooldown: 15 minutes. See {@link #breakCooldownTicks} for the full table. */
	public static final long BREAK_COOLDOWN_TICKS = 18000L;
	/** Break cooldown by tier 0..3: 15 min, 10 min, 6 min, 3 min. */
	private static final long[] BREAK_COOLDOWN_TICKS_BY_TIER = {18000L, 12000L, 7200L, 3600L};
	/**
	 * Base max health by tier 0..3 for {@link #maxHealthFor}; the effective value
	 * additionally scales with the bubble diameter and the strength gamerule.
	 */
	public static final float[] BASE_HP_BY_TIER = {200.0F, 400.0F, 700.0F, 1200.0F};
	/** {@link #maxHealthFor} clamps its result into [{@code MIN_MAX_HEALTH}, {@code MAX_MAX_HEALTH}]. */
	public static final float MIN_MAX_HEALTH = 50.0F;
	public static final float MAX_MAX_HEALTH = 8000.0F;
	/** Tier damage resistance (fraction of raw damage negated) by tier 0..3; see {@link #appliedDamage}. */
	public static final float[] TIER_DR_BY_TIER = {0.0F, 0.25F, 0.40F, 0.50F};
	/** Combined (tier x plating) damage resistance never exceeds 70%. */
	public static final float MAX_COMBINED_DR = 0.70F;
	public static final int TICKS_PER_FUEL_SECOND = 20;
	/** Upper bound on the combined (ECO x capacitor) passive-drain interval; see {@link #drainIntervalTicks}. */
	public static final int MAX_DRAIN_INTERVAL_TICKS = 80;
	public static final double PUSHBACK_MARGIN = 0.75;
	/** A fueled shield regenerates once every 2 seconds of active runtime (combat-gated for tier 0). */
	public static final int REGEN_PERIOD_TICKS = 40;
	/** Heal per regen pulse by tier 0..3; see {@link #regenPerPulse}. */
	private static final float[] REGEN_PER_PULSE_BY_TIER = {1.0F, 3.0F, 6.0F, 12.0F};
	/**
	 * "In combat" = the last shield hit was less than this many ticks ago. Tier 0
	 * shields do not regenerate at all in combat; tiers 1-3 pulse at their base rate
	 * in combat and {@link #OUT_OF_COMBAT_REGEN_MULTIPLIER}x out of combat.
	 */
	public static final long COMBAT_GATE_TICKS = 200L;
	/** Out-of-combat regen multiplier for tiers 1-3 (tier 0's 1.0/pulse is already its OOC rate). */
	public static final float OUT_OF_COMBAT_REGEN_MULTIPLIER = 3.0F;
	/** Below this health fraction the bubble starts shrinking; at or above it stays at full radius. */
	public static final float SHRINK_PLATEAU_FRACTION = 0.60F;
	/** Regen pulses heal this much more while the shield has at least one resonance-linked partner. */
	public static final float LINKED_REGEN_MULTIPLIER = 1.25F;
	/** PULSE mode zaps hostile mobs inside the bubble once per this many ticks. */
	public static final int PULSE_PERIOD_TICKS = 60;
	/** Magic damage dealt to each monster inside the bubble by a PULSE zap. */
	public static final float PULSE_DAMAGE = 2.0F;
	/** ECO mode caps the current radius at this fraction of the normal value. */
	public static final float ECO_RADIUS_FACTOR = 0.75F;
	/** An active shield with cycleEffect enabled re-rolls its effect once per this many ticks. */
	public static final int EFFECT_CYCLE_PERIOD_TICKS = 600;
	/**
	 * A7 emergency revive: an owner may skip a running break cooldown by paying this
	 * many stored fuel-seconds; the shield reactivates at
	 * {@link #REVIVE_HEALTH_FRACTION} of its max health. Shared with the client GUI
	 * so the Activate button can flip to its "Revive (-400 fuel)" face exactly when
	 * the server would accept the request.
	 */
	public static final int REVIVE_FUEL_COST = 400;
	/** Health fraction a revived shield restarts at (see {@link #REVIVE_FUEL_COST}). */
	public static final float REVIVE_HEALTH_FRACTION = 0.5F;
	/** C3 patch kit: HP restored per kit used on an ACTIVE projector (capped at max health). */
	public static final float PATCH_KIT_HEAL = 150.0F;

	private ShieldLogic() {
	}

	/**
	 * C3 patch kit on a broken (cooling-down) projector: each kit cuts the REMAINING
	 * cooldown by 20% of the tier's FULL break cooldown (so repeated uses stack
	 * linearly). All table values are divisible by 5, so the integer division is exact.
	 */
	public static long patchKitCooldownReduction(int tier) {
		return breakCooldownTicks(tier) / 5L;
	}

	/**
	 * The canonical max-health model:
	 * {@code clamp(round(BASE_HP[tier] * (0.5 + diameter/64) * strengthPercent/100), 50, 8000)}
	 * with {@code diameter = 2 * targetRadius}. Pure; the block entity recomputes it
	 * whenever tier, diameter or the strength gamerule changes (and on the first tick
	 * after load), always preserving the health FRACTION across the recompute.
	 * Examples: T0 D32 = 200, T0 D8 = 125, T2 D32 = 700, T3 D200 = 4350.
	 */
	public static float maxHealthFor(int tier, float targetRadius, int strengthPercent) {
		float base = BASE_HP_BY_TIER[Math.clamp(tier, 0, BASE_HP_BY_TIER.length - 1)];
		double diameter = targetRadius * 2.0;
		double raw = base * (0.5 + diameter / 64.0) * (strengthPercent / 100.0);
		return Math.clamp(Math.round(raw), (long) MIN_MAX_HEALTH, (long) MAX_MAX_HEALTH);
	}

	/**
	 * The single damage pipeline every shield hit goes through: the receiving
	 * shield's tier DR and (future) plating DR stack multiplicatively, capped at
	 * {@link #MAX_COMBINED_DR} (70%), and a (future) last-stand state halves what
	 * remains. Linked-split hits split the RAW damage first, then each receiving
	 * shield applies its own DR to its share.
	 *
	 * @param platingDr additional plating damage resistance in [0, 1); always 0 until a later WP.
	 * @param lastStand halves the applied damage when true; always false until a later WP.
	 */
	public static float appliedDamage(float raw, int tier, float platingDr, boolean lastStand) {
		float tierDr = TIER_DR_BY_TIER[Math.clamp(tier, 0, TIER_DR_BY_TIER.length - 1)];
		float combinedDr = Math.min(MAX_COMBINED_DR, 1.0F - (1.0F - tierDr) * (1.0F - platingDr));
		return raw * (1.0F - combinedDr) * (lastStand ? 0.5F : 1.0F);
	}

	/** Heal per regen pulse for the given tier (before the linked/out-of-combat multipliers). */
	public static float regenPerPulse(int tier) {
		return REGEN_PER_PULSE_BY_TIER[Math.clamp(tier, 0, REGEN_PER_PULSE_BY_TIER.length - 1)];
	}

	/** @return true when the shield was hit less than {@link #COMBAT_GATE_TICKS} ago. */
	public static boolean inCombat(ShieldState state, long gameTime) {
		return gameTime - state.lastHitGameTime < COMBAT_GATE_TICKS;
	}

	/**
	 * Fuel-seconds burned per passive-drain event, scaling with the bubble size:
	 * {@code max(1, round(diameter / 50))} — 1 at diameter &le; 74, 2 at 75-124,
	 * 3 at 125-174, 4 at &ge; 175. The drain INTERVAL ({@link #drainIntervalTicks})
	 * is unchanged; only the per-event cost grows with the diameter.
	 */
	public static int drainUnits(float targetRadius) {
		return Math.max(1, Math.round(targetRadius * 2.0F / 50.0F));
	}

	/**
	 * @return the current shield radius. Shrink plateau: at a health fraction of
	 * {@link #SHRINK_PLATEAU_FRACTION} (60%) or above the bubble holds its FULL target
	 * radius; below that it shrinks proportionally ({@code targetRadius * frac/0.60}),
	 * floored at {@link #MIN_RADIUS} while active. In {@link ShieldMode#ECO} the result
	 * is capped at {@link #ECO_RADIUS_FACTOR} times the normal value; applying the cap
	 * here keeps the barrier, renderer sync and projectile interception in agreement
	 * about the eco bubble's size.
	 */
	public static float currentRadius(ShieldState state) {
		if (!state.active || state.maxHealth <= 0.0F) {
			return 0.0F;
		}

		float healthFrac = state.health / state.maxHealth;
		float radius = healthFrac >= SHRINK_PLATEAU_FRACTION
				? state.targetRadius
				: Math.max(MIN_RADIUS, state.targetRadius * (healthFrac / SHRINK_PLATEAU_FRACTION));
		if (state.mode == ShieldMode.ECO) {
			radius *= ECO_RADIUS_FACTOR;
		}

		return radius;
	}

	public static boolean canActivate(ShieldState state, long gameTime) {
		return state.fuelSeconds > 0 && gameTime >= state.cooldownUntil;
	}

	/**
	 * Pure barrier decision: should this shield block the given player/projectile owner?
	 *
	 * @return true when the subject is neither the owner nor whitelisted by name (case-insensitive) or UUID.
	 */
	public static boolean shouldBlock(ShieldState state, @Nullable String name, @Nullable UUID uuid, boolean isOwner) {
		if (isOwner) {
			return false;
		}

		if (uuid != null && (uuid.equals(state.ownerUuid) || state.whitelistUuids.contains(uuid))) {
			return false;
		}

		if (name != null) {
			for (String whitelisted : state.whitelistNames) {
				if (whitelisted.equalsIgnoreCase(name)) {
					return false;
				}
			}
		}

		return true;
	}

	/**
	 * Applies damage to the shield using the default break cooldown (tier 0/1).
	 *
	 * @return true if the shield broke.
	 */
	public static boolean applyDamage(ShieldState state, float amount, long gameTime) {
		return applyDamage(state, amount, gameTime, BREAK_COOLDOWN_TICKS);
	}

	/**
	 * Applies damage to the shield. When health is depleted the shield breaks: it deactivates,
	 * health is restored for the next activation and the given cooldown starts.
	 *
	 * @param cooldownTicks break cooldown to apply; see {@link #breakCooldownTicks(int)}.
	 * @return true if the shield broke.
	 */
	public static boolean applyDamage(ShieldState state, float amount, long gameTime, long cooldownTicks) {
		state.lastHitGameTime = gameTime;
		state.absorbedTotal += Math.max(0.0F, amount);
		state.health -= amount;
		if (state.health <= 0.0F) {
			state.active = false;
			state.health = state.maxHealth;
			state.cooldownUntil = gameTime + cooldownTicks;
			// Arms the one-time "shield ready again" ping for when this cooldown elapses.
			state.readyAnnounced = false;
			return true;
		}

		return false;
	}

	/** @return the break cooldown for the given shield tier (see {@link #BREAK_COOLDOWN_TICKS_BY_TIER}). */
	public static long breakCooldownTicks(int tier) {
		return BREAK_COOLDOWN_TICKS_BY_TIER[Math.clamp(tier, 0, BREAK_COOLDOWN_TICKS_BY_TIER.length - 1)];
	}

	/**
	 * Combined passive-drain rule (V4 ECO + V7 flux capacitor): the effective drain
	 * interval in ticks is {@code TICKS_PER_FUEL_SECOND * (eco ? 2 : 1) * (capacitor ? 2 : 1)},
	 * capped at {@link #MAX_DRAIN_INTERVAL_TICKS} (80). So: 20 plain, 40 with either
	 * ECO or a capacitor, and 80 (the cap) with both. Pure; the drain in
	 * {@link #serverTick} fires whenever {@link ShieldState#drainAccum} (active
	 * ticks since the last drain) reaches this interval.
	 */
	public static int drainIntervalTicks(boolean eco, boolean capacitor) {
		return Math.min(MAX_DRAIN_INTERVAL_TICKS, TICKS_PER_FUEL_SECOND * (eco ? 2 : 1) * (capacitor ? 2 : 1));
	}

	/**
	 * Runs one server tick of shield logic for the projector at {@code pos}.
	 *
	 * @param tier the projector's upgrade-core tier (0..2); drives regeneration and break cooldown.
	 * @param capacitor whether a flux capacitor is installed: halves the passive drain
	 * (see {@link #drainIntervalTicks}) and skips the regen pulses' extra fuel-second.
	 * @param self the ticking projector's block entity ({@code state} is its shield state);
	 * used to resolve resonance-linked partners for the projectile damage split.
	 * @return true if the shield state changed and should be saved/synced.
	 */
	public static boolean serverTick(ServerLevel level, BlockPos pos, ShieldState state, int tier, boolean capacitor, BubbleShieldBlockEntity self) {
		if (!state.active) {
			return false;
		}

		boolean changed = false;
		long gameTime = level.getGameTime();

		// Combined passive-drain rule: per effective drain interval of ACTIVE ticks
		// — TICKS_PER_FUEL_SECOND * (eco ? 2 : 1) * (capacitor ? 2 : 1), capped at
		// MAX_DRAIN_INTERVAL_TICKS (80); plain: 20, ECO or capacitor: 40, both: 80 —
		// the shield burns drainUnits(targetRadius) fuel-seconds (1..4, growing with
		// the diameter). The persisted per-shield accumulator (instead of the old
		// gameTime % interval check) closes the dodge exploit where toggling the
		// shield off across the level's payment ticks kept it active for free: only
		// active ticks advance the accumulator, and it survives deactivation, so
		// every interval of active runtime is paid exactly once no matter how the
		// shield is toggled.
		state.drainAccum++;
		if (state.drainAccum >= drainIntervalTicks(state.mode == ShieldMode.ECO, capacitor)) {
			state.drainAccum = 0;
			state.fuelSeconds -= drainUnits(state.targetRadius);
			changed = true;
			if (state.fuelSeconds <= 0) {
				state.fuelSeconds = 0;
				state.active = false;
				return true;
			}
		}

		// Fueled regeneration: one pulse per REGEN_PERIOD_TICKS of active runtime
		// (same accumulator scheme as the drain above), healing regenPerPulse(tier)
		// HP. Combat gate: tier 0 does not pulse at all while in combat (hit less
		// than COMBAT_GATE_TICKS ago); tiers 1-3 pulse at their base rate in combat
		// and x3 out of combat. Each successful pulse burns one extra fuel-second on
		// top of the runtime drain — unless a flux capacitor is installed, which
		// makes regen pulses fuel-free. ECO mode suppresses regeneration entirely as
		// part of its efficiency trade-off. The accumulator always advances while
		// active; the pulse conditions are evaluated when it fires, matching the old
		// boundary-tick semantics.
		state.regenAccum++;
		if (state.regenAccum >= REGEN_PERIOD_TICKS) {
			state.regenAccum = 0;
			boolean inCombat = inCombat(state, gameTime);
			boolean gatedOut = tier == 0 && inCombat;
			if (!gatedOut && state.mode != ShieldMode.ECO && state.health < state.maxHealth && state.fuelSeconds > 0) {
				float perPulse = regenPerPulse(tier);
				if (tier >= 1 && !inCombat) {
					perPulse *= OUT_OF_COMBAT_REGEN_MULTIPLIER;
				}

				// Resonance bonus: a shield with at least one linked partner (same
				// owner, active, overlapping — resolved through the per-tick link
				// cache, see BubbleShieldBlockEntity#linkedShields) heals x1.25 per
				// pulse, stacking multiplicatively with the out-of-combat x3.
				if (self.linkedShields(level).size() > 1) {
					perPulse *= LINKED_REGEN_MULTIPLIER;
				}

				state.health = Math.min(state.maxHealth, state.health + perPulse);
				changed = true;
				if (!capacitor) {
					state.fuelSeconds = Math.max(0, state.fuelSeconds - 1);
					if (state.fuelSeconds <= 0) {
						state.active = false;
						return true;
					}
				}
			}
		}

		// Effect cycle: while active with the toggle on, periodically re-roll the effect.
		if (state.cycleEffect && gameTime % EFFECT_CYCLE_PERIOD_TICKS == 0L) {
			cycleEffect(state, level.getRandom());
			changed = true;
		}

		Vec3 center = Vec3.atCenterOf(pos);
		double radius = currentRadius(state);
		double areaSize = 2.0 * radius + 8.0;
		AABB area = AABB.ofSize(center, areaSize, areaSize, areaSize);

		// D7a: ONE entity pass over the search AABB per shield tick, partitioned into
		// the per-consumer lists. This replaces the up-to-four separate scans of the
		// same volume the consumers below used to run (projectile interception, PULSE
		// monsters, the player barrier and the CROWD_SCALE context count). Semantics
		// are unchanged: getEntitiesOfClass(clazz, box) is getEntities(typeTest, box,
		// EntitySelector.NO_SPECTATORS) and getEntities(null, box) applies the exact
		// same NO_SPECTATORS default, so instanceof partitioning yields the same sets
		// (Projectile/Player/Monster are hierarchy-disjoint classes). Entities do not
		// move within this shield's tick, so one snapshot serves every consumer; the
		// per-consumer geometry filters (crossedInto, isInside) still run per use
		// against the CURRENT radius. The CROWD_SCALE count used a radius*2 box, a
		// subset of this area; its isInside filter keeps the result identical.
		List<Projectile> projectiles = new ArrayList<>();
		List<Player> players = new ArrayList<>();
		List<Monster> monsters = new ArrayList<>();
		for (Entity entity : level.getEntities((Entity) null, area)) {
			if (entity instanceof Projectile projectile) {
				projectiles.add(projectile);
			} else if (entity instanceof Player player) {
				players.add(player);
			} else if (entity instanceof Monster monster) {
				monsters.add(monster);
			}
		}

		if (interceptProjectiles(level, pos, center, radius, state, projectiles, tier, self)) {
			changed = true;
		}

		// PULSE mode: periodically zap every monster inside the (possibly shrunk) bubble.
		if (state.active && state.mode == ShieldMode.PULSE && gameTime % PULSE_PERIOD_TICKS == 0L) {
			if (pulseMonsters(level, center, currentRadius(state), state, monsters)) {
				changed = true;
				if (!state.active) {
					return true;
				}
			}
		}

		if (state.active) {
			// Recompute: interceptions may have shrunk the shield.
			double barrierRadius = currentRadius(state);
			for (Player player : players) {
				if (applyPlayerBarrier(center, barrierRadius, state, player)) {
					GuardEnforcer.apply(level, center, state, player);
					changed = true;
				}
			}

			tickInsideEffect(level, center, currentRadius(state), state, gameTime, players);
		}

		return changed;
	}

	/**
	 * One PULSE-mode zap: every {@link net.minecraft.world.entity.monster.Monster} inside the
	 * bubble (shape-aware) takes {@link #PULSE_DAMAGE} magic damage plus a small outward
	 * knockback. A pulse that hit at least one monster burns one extra fuel-second on top of
	 * the passive drain; running dry deactivates the shield.
	 *
	 * @return true if at least one monster was hit (state changed).
	 */
	private static boolean pulseMonsters(ServerLevel level, Vec3 center, double radius, ShieldState state, List<Monster> monsters) {
		boolean hitAny = false;
		for (Monster mob : monsters) {
			if (!ShieldGeometry.isInside(state.shape, center, radius, mob.position())) {
				continue;
			}

			if (!mob.hurtServer(level, level.damageSources().magic(), PULSE_DAMAGE)) {
				continue;
			}

			// Small outward knockback, mostly horizontal (same direction convention as
			// the player barrier). hurtMarked forces the velocity to replicate to clients.
			Vec3 direction = mob.position().subtract(center);
			Vec3 horizontal = new Vec3(direction.x, 0.0, direction.z);
			horizontal = horizontal.lengthSqr() < 1.0E-6 ? new Vec3(1.0, 0.0, 0.0) : horizontal.normalize();
			mob.setDeltaMovement(horizontal.scale(0.4).add(0.0, 0.1, 0.0));
			mob.hurtMarked = true;

			// overrideLimiter=true lifts the 32-block send limit on large bubbles.
			level.sendParticles(ParticleTypes.CRIT, true, false, mob.getX(), mob.getY(0.5), mob.getZ(), 12, 0.3, 0.3, 0.3, 0.1);
			hitAny = true;
		}

		if (hitAny) {
			state.fuelSeconds = Math.max(0, state.fuelSeconds - 1);
			if (state.fuelSeconds <= 0) {
				state.active = false;
			}
		}

		return hitAny;
	}

	/**
	 * Re-rolls the shield's effect to a uniformly random id in [0, {@link EffectRegistry#COUNT})
	 * that differs from the current one. Pure: mutates only the given state using the given
	 * random source.
	 */
	public static void cycleEffect(ShieldState state, RandomSource random) {
		if (EffectRegistry.COUNT <= 1) {
			return;
		}

		// Draw from COUNT-1 slots and shift past the current id: uniform over all others.
		int next = random.nextInt(EffectRegistry.COUNT - 1);
		if (next >= state.effectId) {
			next++;
		}

		state.effectId = next;
	}

	/**
	 * Runs the selected effect's ambient inside behaviour (particles, auras, sounds)
	 * and its looping ambient sound, modulated by the effect's context profile.
	 *
	 * @param nearbyPlayers players found by the tick's combined area scan (D7a); only
	 * consulted by the CROWD_SCALE context count.
	 */
	private static void tickInsideEffect(ServerLevel level, Vec3 center, float radius, ShieldState state, long gameTime, List<Player> nearbyPlayers) {
		EffectDefinition def = EffectRegistry.get(state.effectId);
		if (def == null) {
			return;
		}

		ContextState ctx = computeContext(level, center, radius, state, def, nearbyPlayers);
		InsideEffectBehavior behavior = InsideEffectBehavior.get(def.insideBehaviorId());
		if (behavior != null) {
			behavior.tick(level, center, radius, state.shape, def, gameTime, ctx);
		}

		if (ctx.extraSparks() && gameTime % 10L == 0L) {
			// STORM_CHARGED while raining: a small electric sprinkle across the interior.
			// Routed shape-aware: for SPHERE/DOME the point (+0.4r above center) is
			// always contained, so containPoint's identity path keeps the legacy
			// emission byte-identical; for the RING the center column is in the hole
			// and the emission is pulled onto the tube instead.
			BehaviorSupport.sendContained(level, ParticleTypes.ELECTRIC_SPARK, state.shape, center, radius,
					center.x, center.y + radius * 0.4, center.z, 8, radius * 0.45, radius * 0.3, radius * 0.45, 0.02);
		}

		if (def.context() == ContextProfile.HEALTH_HUE && ctx.useSecondaryColor() && gameTime % 20L == 0L) {
			// HEALTH_HUE below half health: a small secondary-color dust accent at the core,
			// so the hue shift is observable no matter which behavior the effect uses.
			// The owner's color override (routed through pickColor, which honours
			// useSecondaryColor) recolors the accent like every other dust behavior.
			// overrideLimiter=true lifts the 32-block send limit for large bubbles.
			DustParticleOptions accent = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 1.2F);
			// Shape-aware like the sprinkle above: identity for SPHERE/DOME (+1.0
			// above center is always inside at the >= 3-block active radius),
			// projected onto the tube for the holey RING.
			BehaviorSupport.sendContained(level, accent, state.shape, center, radius,
					center.x, center.y + 1.0, center.z, 6, radius * 0.15, radius * 0.15, radius * 0.15, 0.0);
		}

		playAmbientSound(level, center, radius, def, gameTime);
	}

	/**
	 * Gathers the context inputs for {@link ContextModifier#compute}: day/night and rain
	 * from the level, the shield health fraction from the state, and (only when the
	 * profile needs it) a count of players currently inside the shield. When the owner
	 * set a color override, the computed state is wrapped with it so every behavior's
	 * {@code pickColor} call resolves to the override pair.
	 */
	private static ContextState computeContext(ServerLevel level, Vec3 center, float radius, ShieldState state, EffectDefinition def, List<Player> nearbyPlayers) {
		float healthFrac = state.maxHealth > 0.0F ? state.health / state.maxHealth : 0.0F;
		int playersInside = 0;
		if (def.context() == ContextProfile.CROWD_SCALE) {
			// D7a: fed from the tick's single combined scan instead of a dedicated
			// radius*2-box scan. That box was a subset of the combined 2r+8 area, and
			// every shape is a subset of the radius-r ball (see ShieldGeometry), so
			// the isInside filter yields the exact same count.
			for (Player player : nearbyPlayers) {
				if (ShieldGeometry.isInside(state.shape, center, radius, player.position())) {
					playersInside++;
				}
			}
		}

		ContextState ctx = ContextModifier.compute(def.context(), level.isDarkOutside(), level.isRaining(), playersInside, healthFrac);
		// Opaque ARGB overrides are negative ints; only the -1 sentinel means "unset".
		return state.colorOverride != ShieldState.NO_COLOR_OVERRIDE ? ctx.withColorOverride(state.colorOverride) : ctx;
	}

	/**
	 * Plays the effect's ambient sound at the projector center every
	 * {@code ambientPeriodTicks}. Volume scales with the radius (volume &gt; 1 extends
	 * the audible range ~16 * volume blocks, same trick as the heartbeat behaviour).
	 */
	private static void playAmbientSound(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime) {
		if (gameTime % def.ambientPeriodTicks() != 0L) {
			return;
		}

		SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getValue(Identifier.parse("minecraft:" + def.ambientSoundId()));
		if (sound == null) {
			return;
		}

		float volume = Mth.clamp(radius / 12.0F, 0.6F, 8.0F);
		level.playSound(null, center.x, center.y, center.z, sound, SoundSource.AMBIENT, volume, def.ambientPitch());
	}

	private static boolean interceptProjectiles(ServerLevel level, BlockPos pos, Vec3 center, double radius, ShieldState state, List<Projectile> projectiles, int tier, BubbleShieldBlockEntity self) {
		boolean changed = false;
		long breakCooldownTicks = breakCooldownTicks(tier);

		// The boundary shrinks as hits land, so it is recomputed after every applied
		// hit below: a later projectile in the same tick's volley must test the
		// POST-shrink radius, not the stale one cached before the loop (a projectile
		// now sitting between the old and the new boundary is no longer crossing in).
		double boundary = radius;
		for (Projectile projectile : projectiles) {
			if (!state.active) {
				break;
			}

			Vec3 prev = new Vec3(projectile.xo, projectile.yo, projectile.zo);
			Vec3 current = projectile.position();
			// Only inbound boundary crossings trigger. Already-intercepted projectiles
			// never re-trigger: their prev-position was snapped onto the current
			// position below, so from this tick on prev is inside (and the deflected
			// outbound flight is inside->outside, which never counts as crossing in).
			if (!ShieldGeometry.crossedInto(state.shape, center, boundary, prev, current)) {
				continue;
			}

			Entity owner = projectile.getOwner();
			if (owner != null && !shouldBlock(state, ownerName(owner), owner.getUUID(), false)) {
				continue;
			}

			// Type-specific interaction. ThrownTrident extends AbstractArrow, so it must
			// be matched first; everything unclassified keeps the legacy absorb behaviour.
			// Deflection re-sets the owner (Projectile.deflect calls setOwner), so the
			// resolved owner is passed back through an EntityReference: loyalty tridents
			// keep returning and reflected projectiles keep their damage attribution.
			// A refusal to deflect (e.g. WindCharge.deflect returns false during its
			// first noDeflectTicks) falls back to absorbing, so nothing keeps flying
			// inward and explodes inside the bubble.
			float damage;
			if (projectile instanceof ThrownTrident) {
				if (!projectile.deflect(ProjectileDeflection.REVERSE, null, EntityReference.of(owner), false)) {
					projectile.discard();
				}
				damage = TRIDENT_DAMAGE;
			} else if (projectile instanceof AbstractHurtingProjectile) {
				if (!projectile.deflect(ProjectileDeflection.REVERSE, null, EntityReference.of(owner), false)) {
					projectile.discard();
				}
				damage = HURTING_PROJECTILE_DAMAGE;
			} else if (projectile instanceof ThrowableItemProjectile || projectile instanceof ShulkerBullet) {
				projectile.discard();
				damage = THROWN_DAMAGE;
			} else {
				// AbstractArrow (non-trident) and any other projectile: absorb.
				projectile.discard();
				damage = PROJECTILE_DAMAGE;
			}

			// Neutralize this projectile's boundary crossing for the rest of the tick:
			// a DEFLECTED projectile keeps existing with its old prev-tick position, so
			// a second overlapping shield ticking later in the same server tick would
			// see the same outside->inside crossing and intercept it AGAIN (double
			// damage split). Snapping the prev-position fields (xo/yo/zo and their
			// xOld/yOld/zOld mirrors) to the current position makes crossedInto false
			// for every other shield this tick, and next tick prev is inside, so the
			// outbound flight never re-triggers either.
			projectile.xo = projectile.getX();
			projectile.yo = projectile.getY();
			projectile.zo = projectile.getZ();
			projectile.xOld = projectile.getX();
			projectile.yOld = projectile.getY();
			projectile.zOld = projectile.getZ();

			// Resonance link: same-owner active shields overlapping this one split the
			// RAW damage evenly; each receiving shield then applies its OWN tier DR to
			// its share (appliedDamage). Discarded projectiles are gone; deflected ones
			// had their crossing neutralized above, so a linked partner's own tick can
			// never re-intercept the same projectile for a second damage split.
			// Partners take their raw share through applyShieldDamage (their own tier's
			// DR, break cooldown, break sound and criteria); the hit shield keeps the
			// local applyDamage path so this loop's state/broke handling stays
			// authoritative.
			// D7b: linkedShields memoizes the findLinked resolution per shield tick, so
			// a same-tick volley resolves the partner set ONCE (first hit) instead of
			// re-running findLinked + a LOADED_SHIELDS copy per intercepted projectile.
			List<BubbleShieldBlockEntity> linked = self.linkedShields(level);
			boolean broke;
			if (linked.size() > 1) {
				// A real damage split across resonance-linked shields awards shields_linked
				// to the (online) owner; the criterion is idempotent, so re-firing on later
				// splits is harmless.
				ModCriteria.fireShieldsLinked(level, state.ownerUuid);

				float split = damage / linked.size();
				broke = applyDamage(state, appliedDamage(split, tier, 0.0F, false), level.getGameTime(), breakCooldownTicks);
				for (BubbleShieldBlockEntity partner : linked) {
					if (partner != self) {
						partner.applyShieldDamage(split);
					}
				}
			} else {
				broke = applyDamage(state, appliedDamage(damage, tier, 0.0F, false), level.getGameTime(), breakCooldownTicks);
			}

			changed = true;
			// The hit shrank the shield: later projectiles in this same volley must
			// be tested against the new, smaller boundary (0 once broken).
			boundary = currentRadius(state);

			// overrideLimiter=true lifts the 32-block send limit so the hit burst is
			// visible to players far from the projector on large bubbles.
			level.sendParticles(ParticleTypes.CRIT, true, false, current.x, current.y, current.z, 20, 0.3, 0.3, 0.3, 0.1);
			level.playSound(null, pos, SoundEvents.SHIELD_BLOCK.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
			if (broke) {
				level.playSound(null, pos, SoundEvents.SHIELD_BREAK.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
				ModCriteria.fireShieldBroken(level, state.ownerUuid);
			}
		}

		return changed;
	}

	/**
	 * Pushes any non-whitelisted, non-owner player currently inside the shield back out,
	 * regardless of how they got in (walking, teleporting, riding, or pre-existing).
	 * Leaving is always allowed. Spectators are ignored.
	 *
	 * @return true if the player was pushed back.
	 */
	public static boolean applyPlayerBarrier(Vec3 center, double radius, ShieldState state, Player player) {
		if (player.isSpectator()) {
			return false;
		}

		UUID uuid = player.getUUID();
		boolean isOwner = uuid.equals(state.ownerUuid);
		if (!shouldBlock(state, player.getGameProfile().name(), uuid, isOwner)) {
			return false;
		}

		Vec3 current = player.position();
		if (!ShieldGeometry.isInside(state.shape, center, radius, current)) {
			return false;
		}

		// Push mostly horizontally so players are not flung into the sky or the ground.
		Vec3 direction = current.subtract(center);
		Vec3 horizontal = new Vec3(direction.x, 0.0, direction.z);
		horizontal = horizontal.lengthSqr() < 1.0E-6 ? new Vec3(1.0, 0.0, 0.0) : horizontal.normalize();
		Vec3 target = center.add(horizontal.scale(radius + PUSHBACK_MARGIN));

		// Riding players are moved via their root vehicle so the whole stack leaves the shield.
		Entity mover = player.getRootVehicle();
		double targetY = Mth.clamp(mover.getY(), player.level().getMinY(), player.level().getMaxY());
		Vec3 spot = findExpulsionSpot(player.level(), mover, new Vec3(target.x, targetY, target.z), horizontal);
		mover.teleportTo(spot.x, spot.y, spot.z);
		mover.setDeltaMovement(Vec3.ZERO);
		player.setDeltaMovement(Vec3.ZERO);
		return true;
	}

	/** Upward probe range for {@link #findExpulsionSpot} (blocks). */
	private static final int EXPULSION_PROBE_UP = 8;
	/** Extra outward probe range for {@link #findExpulsionSpot} (blocks beyond the pushback target). */
	private static final int EXPULSION_PROBE_OUT = 4;

	/**
	 * Picks a collision-free spot for the barrier expulsion teleport. The direct
	 * pushback target is used unchanged when it is free (the common, cheap case:
	 * one {@code noCollision} check). When it would shove the player into blocks
	 * (e.g. a wall hugging the bubble), nearby offsets are probed — per upward step
	 * (0..{@value #EXPULSION_PROBE_UP} blocks), slightly further outward along the
	 * pushback direction (0..{@value #EXPULSION_PROBE_OUT} blocks) — and the first
	 * free spot wins. Falls back to the legacy direct target when everything is
	 * blocked, which still guarantees the player ends up outside the boundary.
	 */
	private static Vec3 findExpulsionSpot(Level level, Entity mover, Vec3 target, Vec3 outward) {
		AABB box = mover.getBoundingBox();
		for (int up = 0; up <= EXPULSION_PROBE_UP; up++) {
			for (int out = 0; out <= EXPULSION_PROBE_OUT; out++) {
				double x = target.x + outward.x * out;
				double y = Math.min(target.y + up, level.getMaxY());
				double z = target.z + outward.z * out;
				AABB probe = box.move(x - mover.getX(), y - mover.getY(), z - mover.getZ());
				if (level.noCollision(mover, probe)) {
					return new Vec3(x, y, z);
				}
			}
		}

		return target;
	}

	/**
	 * Runs one barrier pass over all players near the projector, expelling any
	 * non-whitelisted player standing inside. Used right after activation.
	 *
	 * @return true if at least one player was pushed out.
	 */
	public static boolean expelBlockedPlayers(ServerLevel level, BlockPos pos, ShieldState state) {
		Vec3 center = Vec3.atCenterOf(pos);
		double radius = currentRadius(state);
		double areaSize = 2.0 * radius + 8.0;
		AABB area = AABB.ofSize(center, areaSize, areaSize, areaSize);

		boolean pushed = false;
		for (Player player : level.getEntitiesOfClass(Player.class, area)) {
			if (applyPlayerBarrier(center, radius, state, player)) {
				GuardEnforcer.apply(level, center, state, player);
				pushed = true;
			}
		}

		return pushed;
	}

	private static @Nullable String ownerName(Entity owner) {
		return owner instanceof Player player ? player.getGameProfile().name() : null;
	}
}
