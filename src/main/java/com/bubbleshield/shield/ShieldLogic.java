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
import com.bubbleshield.effect.SurfaceSoundGroup;
import com.bubbleshield.effect.behaviors.BehaviorSupport;
import com.bubbleshield.net.ShieldPayloads;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
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
	/** Tier-0 (and default) break cooldown: 15 minutes. See {@link #breakCooldownTicks} for the full table. */
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
	/**
	 * Reinforced plating (augment slot): extra damage resistance on EVERY shield hit,
	 * stacking multiplicatively with the tier DR under {@link #MAX_COMBINED_DR}
	 * (see {@link #appliedDamage}).
	 */
	public static final float PLATING_DR = 0.30F;
	/**
	 * Blast ward (augment slot): EXPLOSIVE projectile shield-damage — the
	 * fireball/wither-skull/wind-charge class ({@link #HURTING_PROJECTILE_DAMAGE}) —
	 * is reduced 60% (x0.4). Canonical DR stacking order:
	 * {@code effective = raw x (blastWard && explosive ? 0.4 : 1)} FIRST (see
	 * {@link #blastWardedDamage}), THEN {@link #appliedDamage} (tier/plating DR,
	 * combined cap unchanged at 70%, last-stand halving last).
	 */
	public static final float BLAST_WARD_MULTIPLIER = 0.4F;
	public static final int TICKS_PER_FUEL_SECOND = 20;
	/** Upper bound on the combined (ECO x capacitor) passive-drain interval; see {@link #drainIntervalTicks}. */
	public static final int MAX_DRAIN_INTERVAL_TICKS = 80;
	/**
	 * Fix 4: fixed-point scale of {@link ShieldState#drainDebtMicros} — one fuel-second
	 * of drain debt is this many micro-units. 1e6 divides evenly by every possible
	 * drain interval (20/40/80 ticks), so the per-tick accrual
	 * {@code units * 1_000_000 / interval} is EXACT and the steady-state rate can
	 * never drift from the old "units per interval" formula.
	 */
	public static final long DRAIN_DEBT_MICROS_PER_FUEL_SECOND = 1_000_000L;
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
	/**
	 * B3 last stand: below this health fraction the shield fights harder — applied
	 * damage is halved AFTER the DR cap ({@link #appliedDamage}) and the passive
	 * drain burns double units ({@link #drainUnits(float, boolean)}). The shrink
	 * behaviour is unchanged (the {@link #SHRINK_PLATEAU_FRACTION} plateau already
	 * governs the radius).
	 */
	public static final float LAST_STAND_FRACTION = 0.25F;
	/** B3: while in last stand, a heartbeat cue plays at the projector once per this many ticks. */
	public static final int LAST_STAND_HEARTBEAT_PERIOD_TICKS = 40;
	/** B2 arrow riposte: speed (blocks/tick) of the reflected arrow aimed back at its shooter. */
	public static final double RIPOSTE_SPEED = 0.9;
	/** B5 break shockwave: magic damage dealt to every hostile monster inside the pre-break radius. */
	public static final float NOVA_DAMAGE = 8.0F;
	/** B5 nova knockback: strong outward horizontal scale (cf. the pulse zap's 0.4). */
	private static final double NOVA_KNOCKBACK_SCALE = 1.2;
	/** B5 nova knockback: upward component (cf. the pulse zap's 0.1). */
	private static final double NOVA_KNOCKBACK_UP = 0.5;
	/** B6 siege alarm: the comparator override / boss-bar suffix window after an alarm event. */
	public static final int ALARM_WINDOW_TICKS = 100;
	/** B6 siege alarm: at most one alarm EVENT per this many ticks (15 s). */
	public static final int ALARM_REARM_TICKS = 300;
	/** B6: the threat census (DATA_THREAT_COUNT + the 0-to-positive alarm edge) runs once per second. */
	public static final int THREAT_CENSUS_PERIOD_TICKS = 20;
	/** B6: threats are counted within {@code currentRadius + THREAT_RING_MARGIN} of the projector. */
	public static final double THREAT_RING_MARGIN = 8.0;
	/** ECO mode caps the current radius at this fraction of the normal value. */
	public static final float ECO_RADIUS_FACTOR = 0.75F;
	/** An active shield with cycleEffect enabled re-rolls its effect once per this many ticks. */
	public static final int EFFECT_CYCLE_PERIOD_TICKS = 600;
	/**
	 * A7 emergency revive, base fee: an owner may skip a running break cooldown by
	 * paying {@link #reviveFuelCost} stored fuel-seconds ({@code 400 + 200 * tier}:
	 * 400/600/800/1000 for tiers 0..3); the shield reactivates at
	 * {@link #REVIVE_HEALTH_FRACTION} of its max health. {@link #reviveFuelCost} is
	 * shared with the client GUI so the Activate button can flip to its
	 * "Revive (-N fuel)" face exactly when the server would accept the request.
	 */
	public static final int REVIVE_FUEL_COST_BASE = 400;
	/** Fix 3b: extra revive fee per shield tier on top of {@link #REVIVE_FUEL_COST_BASE}. */
	public static final int REVIVE_FUEL_COST_PER_TIER = 200;
	/**
	 * Fix 3c: a revive is refused while FEWER than this many cooldown ticks remain —
	 * paying hundreds of fuel-seconds to skip under 10 s is never worth it, and the
	 * guard closes the floor-rounding trap where the GUI displayed 0 s while the
	 * server still refused a plain activation (see also the fix-8 ceiling division).
	 */
	public static final long MIN_REVIVE_COOLDOWN_TICKS = 200L;
	/** Health fraction a revived shield restarts at (see {@link #reviveFuelCost}). */
	public static final float REVIVE_HEALTH_FRACTION = 0.5F;
	/** C3 patch kit: HP restored per kit used on an ACTIVE projector (capped at max health). */
	public static final float PATCH_KIT_HEAL = 150.0F;
	/**
	 * WP-Evt: strength of a CONTACT batch entry (a barrier press), byte-encoded
	 * x10 to 25 — a gentle shimmer, far below a real hit's damage scale.
	 */
	public static final float CONTACT_IMPACT_STRENGTH = 2.5F;
	/** WP-Evt: strength of a PASSAGE_IN/OUT batch entry (byte 25, like CONTACT). */
	public static final float PASSAGE_IMPACT_STRENGTH = 2.5F;

	private ShieldLogic() {
	}

	/**
	 * C3 patch kit on a broken (cooling-down) projector: each kit cuts the REMAINING
	 * cooldown by 20% of the FULL break cooldown (so repeated uses stack linearly).
	 * All table values are divisible by 5, so the integer division is exact.
	 *
	 * <p>Fix 2: "the full cooldown" is the duration SNAPSHOTTED when this cooldown
	 * started ({@link ShieldState#breakCooldownTotalTicks}), NOT the current tier's
	 * table value — swapping the upgrade core after a break must not change the
	 * per-kit reduction (a T0 break used to be clearable at T3's 720/kit rate, and
	 * pulling the core mid-cooldown used to punish legitimately shorter cooldowns).
	 * Legacy saves without a snapshot (0) fall back to the current tier's table value.
	 */
	public static long patchKitCooldownReduction(ShieldState state, int tier) {
		long total = state.breakCooldownTotalTicks > 0L ? state.breakCooldownTotalTicks : breakCooldownTicks(tier);
		return total / 5L;
	}

	/** Fix 3b: the tier-scaled emergency-revive fee, {@code 400 + 200 * tier} (400/600/800/1000). */
	public static int reviveFuelCost(int tier) {
		return REVIVE_FUEL_COST_BASE + REVIVE_FUEL_COST_PER_TIER * Math.clamp(tier, 0, BASE_HP_BY_TIER.length - 1);
	}

	/**
	 * The canonical max-health model:
	 * {@code clamp(round(BASE_HP[tier] * (0.5 + diameter/64) * strengthPercent/100), 50, 8000)}
	 * with {@code diameter = 2 * targetRadius}. Pure; the block entity recomputes it
	 * whenever tier, diameter or the strength gamerule changes (and on the first tick
	 * after load), keeping the ABSOLUTE current health clamped into [0, newMax]
	 * across the recompute (fix 1; see {@code BubbleShieldBlockEntity#refreshMaxHealth}).
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
	 * shield's tier DR and plating DR ({@link #PLATING_DR} while reinforced plating
	 * is socketed) stack multiplicatively, capped at {@link #MAX_COMBINED_DR} (70%),
	 * and a (future) last-stand state halves what remains. Linked-split hits split
	 * the RAW damage first, then each receiving shield applies its own DR to its
	 * share.
	 *
	 * <p>Canonical stacking order with the blast ward:
	 * {@code effective = raw x (blastWard && explosive ? 0.4 : 1)}
	 * ({@link #blastWardedDamage}, applied BEFORE this pipeline) {@code ->
	 * appliedDamage(effective, tier, platingDr, lastStand)}.
	 *
	 * @param platingDr additional plating damage resistance in [0, 1); {@link #PLATING_DR} or 0.
	 * @param lastStand halves the applied damage when true; wired from
	 * {@link #isLastStand} (evaluated against the health BEFORE the hit) at every
	 * call site (B3).
	 */
	public static float appliedDamage(float raw, int tier, float platingDr, boolean lastStand) {
		return raw * (1.0F - combinedDr(tier, platingDr)) * (lastStand ? 0.5F : 1.0F);
	}

	/**
	 * The combined tier-x-plating damage resistance in [0, {@link #MAX_COMBINED_DR}]:
	 * the exact fraction {@link #appliedDamage} negates (before the last-stand
	 * halving). Pure; shared by the damage pipeline, the GUI's DR readout and the
	 * {@code /bubbleshield status} line so the displayed percent can never drift
	 * from the math.
	 */
	public static float combinedDr(int tier, float platingDr) {
		float tierDr = TIER_DR_BY_TIER[Math.clamp(tier, 0, TIER_DR_BY_TIER.length - 1)];
		return Math.min(MAX_COMBINED_DR, 1.0F - (1.0F - tierDr) * (1.0F - platingDr));
	}

	/**
	 * Formats a duration in whole seconds as "m:ss" (e.g. 605 -&gt; "10:05");
	 * negative inputs clamp to "0:00". Shared by the GUI cooldown label, the
	 * Activate button's "Ready in ..." tooltip and the {@code /bubbleshield status}
	 * time readouts.
	 */
	public static String formatMinutesSeconds(long totalSeconds) {
		long clamped = Math.max(0L, totalSeconds);
		long seconds = clamped % 60L;
		return (clamped / 60L) + ":" + (seconds < 10L ? "0" : "") + seconds;
	}

	/**
	 * B3 last stand: true while the shield's health fraction is strictly below
	 * {@link #LAST_STAND_FRACTION} (25%). Damage call sites evaluate this BEFORE
	 * applying the hit, so the hit that drops the shield below the threshold is
	 * itself still full-priced.
	 */
	public static boolean isLastStand(ShieldState state) {
		return state.maxHealth > 0.0F && state.health / state.maxHealth < LAST_STAND_FRACTION;
	}

	/**
	 * The blast ward's pre-pipeline reduction: EXPLOSIVE projectile raw damage
	 * (the {@link #HURTING_PROJECTILE_DAMAGE} class) is multiplied by
	 * {@link #BLAST_WARD_MULTIPLIER} (x0.4, i.e. -60%) while a blast ward is
	 * socketed — BEFORE {@link #appliedDamage} (tier/plating DR, cap unchanged).
	 * Non-explosive damage (arrows, tridents, thrown items) is never warded.
	 */
	public static float blastWardedDamage(float raw, boolean blastWard) {
		return blastWard ? raw * BLAST_WARD_MULTIPLIER : raw;
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
	 * B3 last-stand variant of {@link #drainUnits(float)}: while the shield is in
	 * last stand ({@link #isLastStand}) every passive-drain event burns DOUBLE the
	 * diameter-scaled units. The drain interval is unchanged; only the per-event
	 * cost doubles. The menu's baseline drain readout intentionally keeps the
	 * un-doubled rate (it shows the steady, healthy-shield baseline).
	 */
	public static int drainUnits(float targetRadius, boolean lastStand) {
		return drainUnits(targetRadius) * (lastStand ? 2 : 1);
	}

	/**
	 * @return the current shield radius. Shrink plateau: at a health fraction of
	 * {@link #SHRINK_PLATEAU_FRACTION} (60%) or above the bubble holds its FULL target
	 * radius; below that it shrinks proportionally ({@code targetRadius * frac/0.60}).
	 * In {@link ShieldMode#ECO} the result is capped at {@link #ECO_RADIUS_FACTOR}
	 * times the normal value; applying the cap here keeps the barrier, renderer sync
	 * and projectile interception in agreement about the eco bubble's size. Fix 10:
	 * the {@link #MIN_RADIUS} floor is applied LAST, after every multiplier, so an
	 * active bubble is never smaller than 4 blocks — the old order let ECO's x0.75
	 * undercut the floor to an effective 3.
	 */
	public static float currentRadius(ShieldState state) {
		if (!state.active || state.maxHealth <= 0.0F) {
			return 0.0F;
		}

		float healthFrac = state.health / state.maxHealth;
		float radius = healthFrac >= SHRINK_PLATEAU_FRACTION
				? state.targetRadius
				: state.targetRadius * (healthFrac / SHRINK_PLATEAU_FRACTION);
		if (state.mode == ShieldMode.ECO) {
			radius *= ECO_RADIUS_FACTOR;
		}

		return Math.max(MIN_RADIUS, radius);
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
	 * Applies damage to the shield using the default (tier-0) break cooldown.
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
			// Fix 2: snapshot the FULL cooldown this break started, so the patch
			// kit's 20% reduction stays pinned to it across later core swaps.
			state.breakCooldownTotalTicks = cooldownTicks;
			// Fix 3a: a NEW break-cooldown window grants a fresh (single) revive.
			state.revivedThisCooldown = false;
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
	 * {@link #serverTick} accrues {@code units * 1e6 / thisInterval} micro-debt per
	 * active tick ({@link ShieldState#drainDebtMicros}), sampling this interval
	 * fresh EVERY tick.
	 */
	public static int drainIntervalTicks(boolean eco, boolean capacitor) {
		return Math.min(MAX_DRAIN_INTERVAL_TICKS, TICKS_PER_FUEL_SECOND * (eco ? 2 : 1) * (capacitor ? 2 : 1));
	}

	/**
	 * Runs one server tick of shield logic for the projector at {@code pos}.
	 *
	 * @param tier the projector's upgrade-core tier (0..3); drives regeneration and break cooldown.
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

		// Fix 4, normalized fixed-point passive drain: every ACTIVE tick accrues
		// units(diameter, lastStand) * 1e6 / interval(eco, capacitor) micro-debt,
		// sampling the CURRENT config each tick; whole fuel-seconds are paid off
		// whenever the debt reaches 1e6 micros, keeping the remainder. Steady-state
		// rates are IDENTICAL to the old "units per interval of active ticks"
		// scheme (1e6 divides evenly by 20/40/80, and units <= 8 keeps the accrual
		// far from overflow), but a config flip mid-interval is now priced
		// per-tick — the old accumulator sampled diameter/ECO/capacitor only on
		// the payment tick, so swapping to a big diameter right before it (or a
		// cheap config right on it) cheated the rate. Only active ticks accrue and
		// the debt survives deactivation, so toggling still never dodges the
		// drain. B3: a last-stand shield accrues at double units per tick.
		int interval = drainIntervalTicks(state.mode == ShieldMode.ECO, capacitor);
		state.drainDebtMicros += drainUnits(state.targetRadius, isLastStand(state))
				* DRAIN_DEBT_MICROS_PER_FUEL_SECOND / interval;
		if (state.drainDebtMicros >= DRAIN_DEBT_MICROS_PER_FUEL_SECOND) {
			int owedFuelSeconds = (int) (state.drainDebtMicros / DRAIN_DEBT_MICROS_PER_FUEL_SECOND);
			state.drainDebtMicros %= DRAIN_DEBT_MICROS_PER_FUEL_SECOND;
			state.fuelSeconds -= owedFuelSeconds;
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

		// B3 heartbeat cue: a last-stand shield thumps at the projector every
		// 40 ticks of active runtime, radius-scaled like the ambient sounds
		// (volume > 1 extends the audible range ~16 * volume blocks).
		if (isLastStand(state) && gameTime % LAST_STAND_HEARTBEAT_PERIOD_TICKS == 0L) {
			float volume = Mth.clamp(currentRadius(state) / 12.0F, 0.6F, 4.0F);
			level.playSound(null, pos, SoundEvents.WARDEN_HEARTBEAT, SoundSource.BLOCKS, volume, 1.0F);
		}

		Vec3 center = Vec3.atCenterOf(pos);
		double radius = currentRadius(state);
		// Half-extent radius + THREAT_RING_MARGIN: the ONE combined scan must also
		// cover the B6 threat census ring (radius + 8), which is wider than the
		// legacy 2r + 8 box (half-extent r + 4). Every geometry-filtered consumer
		// (crossedInto, isInside) is unaffected by the larger superset box.
		double areaSize = 2.0 * (radius + THREAT_RING_MARGIN);
		AABB area = AABB.ofSize(center, areaSize, areaSize, areaSize);

		// D7a: ONE entity pass over the search AABB per shield tick, partitioned into
		// the per-consumer lists. This replaces the up-to-four separate scans of the
		// same volume the consumers below used to run (projectile interception, PULSE
		// monsters, the player/mob barrier, the CROWD_SCALE context count and the B6
		// threat census). Semantics: getEntitiesOfClass(clazz, box) is
		// getEntities(typeTest, box, EntitySelector.NO_SPECTATORS) and
		// getEntities(null, box) applies the exact same NO_SPECTATORS default, so
		// instanceof partitioning yields the same sets. Entities do not move within
		// this shield's tick, so one snapshot serves every consumer; the
		// per-consumer geometry filters (crossedInto, isInside) still run per use
		// against the CURRENT radius. The CROWD_SCALE count used a radius*2 box, a
		// subset of this area; its isInside filter keeps the result identical.
		// Fix 6: the hostile partition is "Mob that implements Enemy" (alive), not
		// Monster — the Monster class misses hostile Enemy implementations on other
		// branches of the Mob hierarchy (Slime, Phantom, Shulker, Ghast, the
		// dragon...), which walked straight through the barrier and dodged the
		// pulse/census/nova. Bosses keep their barrier exemption downstream
		// (isBarrierExemptBoss); the alive filter keeps dying-but-not-yet-removed
		// mobs out of the census and the zap/nova target lists.
		List<Projectile> projectiles = new ArrayList<>();
		List<Player> players = new ArrayList<>();
		List<Mob> hostiles = new ArrayList<>();
		for (Entity entity : level.getEntities((Entity) null, area)) {
			if (entity instanceof Projectile projectile) {
				projectiles.add(projectile);
			} else if (entity instanceof Player player) {
				players.add(player);
			} else if (entity instanceof Mob mob && entity instanceof Enemy && mob.isAlive()) {
				hostiles.add(mob);
			}
		}

		// B6 threat census (once per second, fed from the combined scan — no extra
		// scan): non-whitelisted players plus hostile monsters within radius + 8 of
		// the center (plain distance, deliberately NOT shape-aware: a sapper digging
		// under a dome is still a threat). Exposed via DATA_THREAT_COUNT; a
		// 0-to-positive edge fires the siege alarm (rate-limited in triggerAlarm).
		if (gameTime % THREAT_CENSUS_PERIOD_TICKS == 0L) {
			int threats = countThreats(center, radius + THREAT_RING_MARGIN, state, players, hostiles);
			int previous = self.threatCount();
			self.setThreatCount(threats);
			if (previous == 0 && threats > 0 && triggerAlarm(level, pos, state, gameTime, radius)) {
				changed = true;
			}
		}

		if (interceptProjectiles(level, pos, center, radius, state, projectiles, tier, self)) {
			changed = true;
		}

		// PULSE mode: periodically zap every hostile mob inside the (possibly shrunk) bubble.
		if (state.active && state.mode == ShieldMode.PULSE && gameTime % PULSE_PERIOD_TICKS == 0L) {
			if (pulseMonsters(level, center, currentRadius(state), state, hostiles)) {
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
					// WP-Evt CONTACT: a blocked player pressing the barrier flashes
					// the wall and hears a personal squelch, at most once per
					// CONTACT_RATE_TICKS per player. Only this steady-state loop
					// emits — the activation-time expelBlockedPlayers pass stays
					// silent by design (the raise cue already covers it).
					if (self.tryContact(player.getUUID(), gameTime)) {
						self.queueImpact(ShieldPayloads.ImpactEntry.KIND_CONTACT,
								safeNormalize(player.position().subtract(center)), CONTACT_IMPACT_STRENGTH);
						sendContactSound(level, player);
					}
				} else if (!player.isSpectator()) {
					// WP-Evt PASSAGE: whitelisted (non-blocked) players shimmer the
					// wall when they cross it, detected as an inside/outside flip
					// between this tick and the last observation. The first
					// observation only seeds the map (no flip, no entry).
					UUID uuid = player.getUUID();
					if (!shouldBlock(state, player.getGameProfile().name(), uuid, uuid.equals(state.ownerUuid))) {
						boolean inside = ShieldGeometry.isInside(state.shape, center, barrierRadius, player.position());
						Boolean was = self.swapWasInside(uuid, inside);
						if (was != null && was != inside) {
							self.queueImpact(
									inside ? ShieldPayloads.ImpactEntry.KIND_PASSAGE_IN : ShieldPayloads.ImpactEntry.KIND_PASSAGE_OUT,
									safeNormalize(player.position().subtract(center)), PASSAGE_IMPACT_STRENGTH);
						}
					}
				}
			}

			// A5 hostile-mob barrier, from the same combined-scan partition (no
			// extra scan; the push itself allocates no more than the player path).
			// Mode matrix: DEFENSE expels any hostile inside (like the player
			// barrier); PULSE only blocks NEW entry at the boundary — hostiles
			// already inside are the pulse zap's prey, not expelled; ECO repels
			// nothing (efficiency trade-off). Bosses are exempt (see
			// isBarrierExemptBoss). No 'changed': the barrier moves mobs, never
			// shield state.
			if (mobBarrierBlocksEntry(state.mode)) {
				boolean expelInside = mobBarrierExpelsInside(state.mode);
				for (Mob hostile : hostiles) {
					applyMobBarrier(center, barrierRadius, state, hostile, expelInside);
				}
			}

			tickInsideEffect(level, center, currentRadius(state), state, gameTime, players);
		}

		return changed;
	}

	/**
	 * B6: counts the threats currently engaging the shield — non-whitelisted,
	 * non-owner players ({@link #shouldBlock}) plus ALL hostile mobs
	 * ({@code Mob && Enemy}; bosses included — the barrier's teleport exemption
	 * does not make a Wither less of a threat) — within {@code ringRadius} of the
	 * center. Plain center distance, not shape-aware. Pure over the given scan
	 * partitions.
	 */
	public static int countThreats(Vec3 center, double ringRadius, ShieldState state, List<Player> players, List<? extends Mob> hostiles) {
		int threats = 0;
		for (Player player : players) {
			if (player.position().distanceTo(center) > ringRadius) {
				continue;
			}

			UUID uuid = player.getUUID();
			if (shouldBlock(state, player.getGameProfile().name(), uuid, uuid.equals(state.ownerUuid))) {
				threats++;
			}
		}

		for (Mob hostile : hostiles) {
			if (hostile.position().distanceTo(center) <= ringRadius) {
				threats++;
			}
		}

		return threats;
	}

	/**
	 * B6 siege alarm event: fires when no alarm happened within the last
	 * {@link #ALARM_REARM_TICKS} (15 s) — plays {@code BELL_RESONATE} at the
	 * projector (radius-scaled volume like the heartbeat) and opens the
	 * {@link #ALARM_WINDOW_TICKS} window during which the comparator reads 15 and
	 * the boss bar carries the UNDER ATTACK suffix. Triggered ONLY by a real
	 * projectile interception or by the threat count's 0-to-positive edge —
	 * deliberately NOT by the direct {@code applyShieldDamage} path (linked-split
	 * shares, commands, tests), whose comparator behaviour stays purely
	 * health-based.
	 *
	 * @return true if the alarm actually fired (state changed).
	 */
	public static boolean triggerAlarm(ServerLevel level, BlockPos pos, ShieldState state, long gameTime, double radius) {
		// The last alarm EVENT was at (alarmUntilGameTime - window); 0 = never.
		if (state.alarmUntilGameTime != 0L
				&& gameTime < state.alarmUntilGameTime - ALARM_WINDOW_TICKS + ALARM_REARM_TICKS) {
			return false;
		}

		state.alarmUntilGameTime = gameTime + ALARM_WINDOW_TICKS;
		float volume = Mth.clamp((float) radius / 12.0F, 0.6F, 4.0F);
		level.playSound(null, pos, SoundEvents.BELL_RESONATE, SoundSource.BLOCKS, volume, 1.0F);
		return true;
	}

	/**
	 * One PULSE-mode zap: every hostile mob ({@code Mob && Enemy}, from the
	 * combined-scan partition) inside the bubble (shape-aware) takes
	 * {@link #PULSE_DAMAGE} magic damage plus a small outward knockback. A pulse
	 * that hit at least one hostile burns one extra fuel-second on top of the
	 * passive drain; running dry deactivates the shield.
	 *
	 * @return true if at least one hostile was hit (state changed).
	 */
	private static boolean pulseMonsters(ServerLevel level, Vec3 center, double radius, ShieldState state, List<? extends Mob> hostiles) {
		boolean hitAny = false;
		for (Mob mob : hostiles) {
			if (!ShieldGeometry.isInside(state.shape, center, radius, mob.position())) {
				continue;
			}

			if (!mob.hurtServer(level, level.damageSources().magic(), PULSE_DAMAGE)) {
				continue;
			}

			// Small outward knockback, mostly horizontal (same direction convention as
			// the player barrier).
			knockbackOutward(mob, center, 0.4, 0.1);

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
	 * Mostly-horizontal outward knockback away from the shield center (same
	 * direction convention as the player barrier), shared by the PULSE zap (0.4 /
	 * 0.1) and the B5 break nova (strong: 1.2 / 0.5). {@code hurtMarked} forces the
	 * velocity to replicate to clients.
	 */
	private static void knockbackOutward(Mob mob, Vec3 center, double horizontalScale, double up) {
		Vec3 direction = mob.position().subtract(center);
		Vec3 horizontal = new Vec3(direction.x, 0.0, direction.z);
		horizontal = horizontal.lengthSqr() < 1.0E-6 ? new Vec3(1.0, 0.0, 0.0) : horizontal.normalize();
		mob.setDeltaMovement(horizontal.scale(horizontalScale).add(0.0, up, 0.0));
		mob.hurtMarked = true;
	}

	/**
	 * B5: the ONE shared shield-break routine — both break paths (projectile
	 * interception inside {@link #serverTick} and the direct
	 * {@code BubbleShieldBlockEntity.applyShieldDamage} path, which linked-split
	 * partner shares also take) route through here, so the break sound, the
	 * {@code shield_broken} criterion and the shockwave nova can never drift apart.
	 *
	 * <p>The nova: every hostile mob ({@code Mob && Enemy}, alive) inside the
	 * PRE-break current radius (shape-aware, like the pulse zap) takes
	 * {@link #NOVA_DAMAGE} magic damage plus a strong outward knockback, and
	 * {@code RESPAWN_ANCHOR_DEPLETE} (1.2, 0.7) marks the collapse. Players and
	 * pets are unaffected by construction (only hostiles are targeted); bosses
	 * are hit too — damage, unlike the barrier's teleport, is boss-safe. Fix 6:
	 * CUSTOM-NAMED hostiles are skipped by the NOVA ONLY (a named "pet" hostile —
	 * a nametagged zombie in a display pen, say — should not be executed by its
	 * owner's own collapsing shield; the barrier and the pulse zap deliberately
	 * still treat named hostiles as hostile). Plain {@code magic()} damage
	 * (unattributed) keeps the nova deterministic whether or not the owner is
	 * online. The one-shot entity scan here is fine: a break happens at most once
	 * per activation, not per tick.
	 */
	/**
	 * WP-Evt: the block-entity-aware break overload BOTH break paths call (the
	 * interception loop and {@code BubbleShieldBlockEntity.applyShieldDamage}):
	 * runs the shared break routine, sweeps the transient visual-event state
	 * (queued flashes/contact maps/delayed sounds for a bubble that just ceased
	 * to exist), then queues the one entry that must survive everything — the
	 * directionless BREAK at the saturated strength byte 255, which
	 * {@code flushImpacts}' cap never drops.
	 */
	public static void onShieldBreak(ServerLevel level, BlockPos pos, ShieldState state, double preBreakRadius, BubbleShieldBlockEntity be) {
		onShieldBreak(level, pos, state, preBreakRadius);
		be.clearImpactState();
		be.queueImpact(ShieldPayloads.ImpactEntry.KIND_BREAK, Vec3.ZERO, ShieldPayloads.ImpactEntry.MAX_STRENGTH);
	}

	public static void onShieldBreak(ServerLevel level, BlockPos pos, ShieldState state, double preBreakRadius) {
		level.playSound(null, pos, SoundEvents.SHIELD_BREAK.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
		ModCriteria.fireShieldBroken(level, state.ownerUuid);
		if (preBreakRadius <= 0.0) {
			return;
		}

		Vec3 center = Vec3.atCenterOf(pos);
		AABB area = AABB.ofSize(center, 2.0 * preBreakRadius, 2.0 * preBreakRadius, 2.0 * preBreakRadius);
		for (Mob mob : level.getEntitiesOfClass(Mob.class, area)) {
			if (!(mob instanceof Enemy) || !mob.isAlive() || mob.hasCustomName()) {
				continue;
			}

			if (!ShieldGeometry.isInside(state.shape, center, preBreakRadius, mob.position())) {
				continue;
			}

			mob.hurtServer(level, level.damageSources().magic(), NOVA_DAMAGE);
			knockbackOutward(mob, center, NOVA_KNOCKBACK_SCALE, NOVA_KNOCKBACK_UP);
		}

		level.playSound(null, pos, SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), SoundSource.BLOCKS, 1.2F, 0.7F);
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
		long gameTime = level.getGameTime();

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
			boolean explosive = false;
			if (projectile instanceof ThrownTrident) {
				if (!projectile.deflect(ProjectileDeflection.REVERSE, null, EntityReference.of(owner), false)) {
					projectile.discard();
				}
				damage = TRIDENT_DAMAGE;
			} else if (projectile instanceof AbstractHurtingProjectile) {
				if (!projectile.deflect(ProjectileDeflection.REVERSE, null, EntityReference.of(owner), false)) {
					projectile.discard();
				}
				// Fix 5: the explosive class keeps its RAW damage here; the blast
				// ward is applied PER RECEIVER at damage time below (after the
				// linked split), so each linked shield's OWN ward discounts only
				// its own share. The interception itself (deflect-or-discard, so
				// nothing explodes inside the bubble) is unchanged.
				damage = HURTING_PROJECTILE_DAMAGE;
				explosive = true;
			} else if (projectile instanceof ThrowableItemProjectile || projectile instanceof ShulkerBullet) {
				projectile.discard();
				damage = THROWN_DAMAGE;
			} else if (projectile instanceof AbstractArrow arrow && tier >= 1 && owner != null) {
				// B2 riposte (tier >= 1, resolvable shooter): thrown back at the
				// shooter instead of absorbed. Ownerless arrows (dispensers) and
				// tier 0 keep the plain absorb below.
				riposteArrow(arrow, owner, state);
				damage = PROJECTILE_DAMAGE;
			} else {
				// AbstractArrow at tier 0 / without a shooter, and any other
				// projectile: absorb.
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

			// Resonance link (fix 5): same-owner active shields overlapping this one
			// split the RAW damage evenly; each receiving shield then applies ITS OWN
			// full pipeline to its share — blast ward (explosive hits, that receiver's
			// ward), appliedDamage (that receiver's tier/plating DR) and the B3
			// last-stand halving (that receiver's health). Discarded projectiles are
			// gone; deflected ones had their crossing neutralized above, so a linked
			// partner's own tick can never re-intercept the same projectile for a
			// second damage split. Partners take their raw share through
			// applyShieldDamage (their own ward/DR/last-stand, break cooldown, break
			// sound and criteria); the hit shield keeps the local applyDamage path so
			// this loop's state/broke handling stays authoritative.
			// D7b: linkedShields memoizes the findLinked resolution per shield tick,
			// so a same-tick volley resolves the partner set ONCE (first hit) instead
			// of re-running findLinked + a LOADED_SHIELDS copy per projectile. The
			// cached set is re-FILTERED to ACTIVE receivers per projectile: a partner
			// that broke earlier in this same volley must not receive further shares
			// (its applyShieldDamage would also no-op, but the split DENOMINATOR must
			// shrink too, so the survivors take the full raw damage between them).
			// B3: last stand is evaluated against the health BEFORE this hit, and B5
			// needs the PRE-break radius for the nova, so both snapshot here.
			List<BubbleShieldBlockEntity> receivers = self.linkedShields(level).stream()
					.filter(shield -> shield.getShieldState().active)
					.toList();
			boolean lastStand = isLastStand(state);
			double preHitRadius = boundary;
			boolean broke;
			float applied;
			if (receivers.size() > 1) {
				// A real damage split across resonance-linked shields awards shields_linked
				// to the (online) owner; the criterion is idempotent, so re-firing on later
				// splits is harmless.
				ModCriteria.fireShieldsLinked(level, state.ownerUuid);

				float split = damage / receivers.size();
				applied = appliedDamage(blastWardedDamage(split, explosive && self.hasBlastWard()), tier, self.platingDr(), lastStand);
				broke = applyDamage(state, applied, gameTime, breakCooldownTicks);
				for (BubbleShieldBlockEntity partner : receivers) {
					if (partner != self) {
						partner.applyShieldDamage(split, explosive);
					}
				}
			} else {
				applied = appliedDamage(blastWardedDamage(damage, explosive && self.hasBlastWard()), tier, self.platingDr(), lastStand);
				broke = applyDamage(state, applied, gameTime, breakCooldownTicks);
			}

			// WP-Evt IMPACT: one batch entry per interception on the INTERCEPTING
			// shield only (linked partners took damage but have no local hit point
			// to flash) — outward unit direction from the center to the hit point,
			// strength = this shield's own post-DR share. Sitting right after the
			// applied/broke computation covers the riposte/ward/linked branches
			// uniformly; a breaking hit's entry is swept by onShieldBreak below,
			// whose BREAK entry supersedes it.
			self.queueImpact(ShieldPayloads.ImpactEntry.KIND_IMPACT,
					safeNormalize(current.subtract(center)), applied);

			// B6: every interception with a resolvable shooter lands in the threat
			// log (this shield's own post-DR share), and every interception is an
			// alarm event (rate-limited inside triggerAlarm).
			if (owner != null) {
				state.recordThreat(owner.getName().getString(), applied, gameTime);
			}

			triggerAlarm(level, pos, state, gameTime, preHitRadius);
			// C7 "unbroken": lifetime absorbed damage feeds the damage_absorbed
			// criterion for the (online) owner; idempotent once awarded.
			ModCriteria.fireDamageAbsorbed(level, state.ownerUuid, state.absorbedTotal);

			changed = true;
			// The hit shrank the shield: later projectiles in this same volley must
			// be tested against the new, smaller boundary (0 once broken).
			boundary = currentRadius(state);

			// overrideLimiter=true lifts the 32-block send limit so the hit burst is
			// visible to players far from the projector on large bubbles.
			level.sendParticles(ParticleTypes.CRIT, true, false, current.x, current.y, current.z, 20, 0.3, 0.3, 0.3, 0.1);
			// WP-Evt/S2 hit sounds, AT THE HIT POINT (the old site played the lone
			// SHIELD_BLOCK at the projector, which on a big bubble was audibly
			// nowhere near the hit) — rate-limited to ONE trio per shield per tick
			// (the first impact of a same-tick volley wins):
			//   1. HEAVY_CORE_HIT thump, pitch scaling with the RAW damage class;
			//   2. the E2 SHIELD_BLOCK ring, keeping its health-scaled pitch —
			//      0.8 + 0.6 x (1 - healthFrac) on the POST-hit health (a breaking
			//      hit counts as fraction 0: applyDamage already restored the
			//      health for the next activation, so the pre-reset fraction is
			//      gone);
			//   3. the effect family's surface-material layer (two sounds).
			// Plus the delayed antipode wave tail on the far side of the bubble.
			if (self.tryImpactSounds(gameTime)) {
				level.playSound(null, current.x, current.y, current.z, SoundEvents.HEAVY_CORE_HIT,
						SoundSource.BLOCKS, 1.2F, 0.6F + 0.3F * Math.min(damage, 10.0F) / 10.0F);
				float postHitFrac = broke || state.maxHealth <= 0.0F
						? 0.0F
						: Mth.clamp(state.health / state.maxHealth, 0.0F, 1.0F);
				level.playSound(null, current.x, current.y, current.z, SoundEvents.SHIELD_BLOCK.value(),
						SoundSource.BLOCKS, 1.0F, 0.8F + 0.6F * (1.0F - postHitFrac));
				playFamilyLayer(level, current, SurfaceSoundGroup.of(EffectRegistry.get(state.effectId).surface()));
				self.queueAntipodeWaveTail(current, preHitRadius);
			}

			if (broke) {
				onShieldBreak(level, pos, state, preHitRadius, self);
			}
		}

		return changed;
	}

	/**
	 * S2: the per-family surface-material layer of the impact trio, played at the
	 * hit point on top of the HEAVY_CORE_HIT thump and the SHIELD_BLOCK ring —
	 * each {@link SurfaceSoundGroup} contributes its own characteristic pair.
	 */
	private static void playFamilyLayer(ServerLevel level, Vec3 hit, SurfaceSoundGroup group) {
		switch (group) {
			case ENERGY -> {
				level.playSound(null, hit.x, hit.y, hit.z, SoundEvents.SCULK_BLOCK_CHARGE, SoundSource.BLOCKS, 0.8F, 1.3F);
				level.playSound(null, hit.x, hit.y, hit.z, SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.BLOCKS, 0.4F, 1.6F);
			}
			case CRYSTAL -> {
				level.playSound(null, hit.x, hit.y, hit.z, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.BLOCKS, 0.9F, 0.7F);
				level.playSound(null, hit.x, hit.y, hit.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.5F, 0.6F);
			}
			case ORGANIC -> {
				level.playSound(null, hit.x, hit.y, hit.z, SoundEvents.SLIME_BLOCK_HIT, SoundSource.BLOCKS, 1.0F, 0.6F);
				level.playSound(null, hit.x, hit.y, hit.z, SoundEvents.HONEY_BLOCK_SLIDE, SoundSource.BLOCKS, 0.4F, 0.8F);
			}
			case TECH -> {
				level.playSound(null, hit.x, hit.y, hit.z, SoundEvents.NETHERITE_BLOCK_HIT, SoundSource.BLOCKS, 1.0F, 0.9F);
				level.playSound(null, hit.x, hit.y, hit.z, SoundEvents.COPPER_BULB_TURN_ON, SoundSource.BLOCKS, 0.5F, 0.7F);
			}
			case VOID -> {
				level.playSound(null, hit.x, hit.y, hit.z, SoundEvents.ENDER_CHEST_CLOSE, SoundSource.BLOCKS, 0.7F, 0.5F);
				level.playSound(null, hit.x, hit.y, hit.z, SoundEvents.PORTAL_TRIGGER, SoundSource.BLOCKS, 0.3F, 0.4F);
			}
		}
	}

	/**
	 * S2 CONTACT personal sound: the pressing player alone hears a slime squelch
	 * at their own position (the payload-driven flash is theirs alone too).
	 * 26.2 removed {@code Player.playNotifySound}, so this sends the
	 * {@link ClientboundSoundPacket} directly, exactly like the vanilla raid horn.
	 */
	private static void sendContactSound(ServerLevel level, Player player) {
		if (player instanceof ServerPlayer serverPlayer) {
			serverPlayer.connection.send(new ClientboundSoundPacket(
					BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.SLIME_BLOCK_HIT), SoundSource.BLOCKS,
					player.getX(), player.getY(), player.getZ(), 0.8F, 0.5F, level.getRandom().nextLong()));
		}
	}

	/**
	 * WP-Evt: a safe unit vector for impact-entry directions — degenerate inputs
	 * (an event exactly at the center) quantize to the zero direction, which the
	 * client already treats as "directionless" (the BREAK convention).
	 */
	private static Vec3 safeNormalize(Vec3 direction) {
		return direction.lengthSqr() < 1.0E-6 ? Vec3.ZERO : direction.normalize();
	}

	/**
	 * B2 arrow riposte (tier &ge; 1): the intercepted arrow is thrown back at its
	 * shooter instead of absorbed. Ownership decision: vanilla arrows can never
	 * hurt their own owner while inside its collision range, and keeping the
	 * shooter as owner would make the riposte's damage attribution self-inflicted
	 * anyway — so the reflected arrow is RE-OWNED to the SHIELD's owner (a plain
	 * UUID {@link EntityReference}, resolvable even while the owner is offline):
	 * the original shooter becomes a fully valid target and any kill credit goes
	 * to the shield owner. Pickup is disallowed so the riposte is not a free
	 * arrow fountain at the shooter's feet. A refused deflect falls back to plain
	 * absorption (discard), like the trident path; the shield takes the same
	 * post-DR damage either way.
	 */
	private static void riposteArrow(AbstractArrow arrow, Entity shooter, ShieldState state) {
		EntityReference<Entity> shieldOwner = state.ownerUuid != null ? EntityReference.of(state.ownerUuid) : null;
		if (!arrow.deflect(ProjectileDeflection.REVERSE, null, shieldOwner, false)) {
			arrow.discard();
			return;
		}

		arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
		// REVERSE only flips the momentum (with jitter); re-aim straight at the
		// shooter with a modest, deterministic speed.
		Vec3 toShooter = shooter.getEyePosition().subtract(arrow.position());
		if (toShooter.lengthSqr() > 1.0E-6) {
			arrow.setDeltaMovement(toShooter.normalize().scale(RIPOSTE_SPEED));
			// Forces the velocity to replicate to clients (same as the pulse knockback).
			arrow.hurtMarked = true;
		}
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

	/**
	 * A5 mob-barrier mode matrix, entry half: DEFENSE and PULSE block hostile
	 * monsters at the boundary; ECO deliberately repels nothing (its efficiency
	 * trade-off, mirroring the suppressed regen and the capped radius).
	 */
	public static boolean mobBarrierBlocksEntry(ShieldMode mode) {
		return mode != ShieldMode.ECO;
	}

	/**
	 * A5 mob-barrier mode matrix, inside half: only DEFENSE expels monsters
	 * already INSIDE (every tick, plus once on activation via
	 * {@link #expelBlockedMonsters}) — the full player-barrier treatment. PULSE
	 * deliberately leaves inside monsters where they are: they are the pulse
	 * zap's prey, and expelling them would make the zap unreachable; its barrier
	 * only blocks NEW entry (outside-to-inside crossings).
	 */
	public static boolean mobBarrierExpelsInside(ShieldMode mode) {
		return mode == ShieldMode.DEFENSE;
	}

	/**
	 * A5: bosses are exempt from the barrier's teleport-expulsion — the wither's
	 * phase logic and the dragon's multi-part body/flight controller react badly
	 * to being {@code teleportTo}'d by external code (teleport-fragile), and a
	 * bubble should not trivially cheese a boss fight anyway. They still take
	 * pulse-zap and break-nova DAMAGE; only the teleport is skipped. Since the
	 * fix-6 {@code Mob && Enemy} partition, the dragon DOES reach the barrier
	 * through the combined scan (it is both), so this exemption is load-bearing
	 * for it, not merely defensive.
	 */
	public static boolean isBarrierExemptBoss(Entity entity) {
		return entity instanceof WitherBoss || entity instanceof EnderDragon;
	}

	/**
	 * A5: blocks one hostile mob ({@code Mob && Enemy}) at the barrier, reusing
	 * the player barrier's geometry (mostly-horizontal push to
	 * {@code radius + PUSHBACK_MARGIN}), its collision-safe placement
	 * ({@link #findExpulsionSpot}) and its root-vehicle handling — and allocating
	 * nothing beyond what the player path does. With {@code expelInside}
	 * (DEFENSE) any hostile inside is expelled, however it got there; without it
	 * (PULSE) only an outside-to-inside CROSSING since the previous tick is
	 * pushed back, so hostiles already inside stay. Mobs have no whitelist
	 * identity (no owner), so unlike players there is no {@link #shouldBlock}
	 * exemption — only the boss exemption applies (custom-named hostiles are
	 * still barred; only the break NOVA spares them).
	 *
	 * @return true if the hostile was pushed back.
	 */
	public static boolean applyMobBarrier(Vec3 center, double radius, ShieldState state, Mob hostile, boolean expelInside) {
		if (isBarrierExemptBoss(hostile)) {
			return false;
		}

		Vec3 current = hostile.position();
		if (!ShieldGeometry.isInside(state.shape, center, radius, current)) {
			return false;
		}

		if (!expelInside) {
			// PULSE: block NEW entry only — a mob whose previous position was
			// already inside (spawned there, or parked no-AI) is left alone.
			Vec3 prev = new Vec3(hostile.xo, hostile.yo, hostile.zo);
			if (ShieldGeometry.isInside(state.shape, center, radius, prev)) {
				return false;
			}
		}

		Vec3 direction = current.subtract(center);
		Vec3 horizontal = new Vec3(direction.x, 0.0, direction.z);
		horizontal = horizontal.lengthSqr() < 1.0E-6 ? new Vec3(1.0, 0.0, 0.0) : horizontal.normalize();
		Vec3 target = center.add(horizontal.scale(radius + PUSHBACK_MARGIN));

		Entity mover = hostile.getRootVehicle();
		double targetY = Mth.clamp(mover.getY(), hostile.level().getMinY(), hostile.level().getMaxY());
		Vec3 spot = findExpulsionSpot(hostile.level(), mover, new Vec3(target.x, targetY, target.z), horizontal);
		mover.teleportTo(spot.x, spot.y, spot.z);
		mover.setDeltaMovement(Vec3.ZERO);
		hostile.setDeltaMovement(Vec3.ZERO);
		return true;
	}

	/**
	 * A5: one expulsion pass over all hostile mobs ({@code Mob && Enemy}) near
	 * the projector, used right after activation (the hostile counterpart of
	 * {@link #expelBlockedPlayers}). Only DEFENSE expels hostiles already inside
	 * when the shield rises ({@link #mobBarrierExpelsInside}); PULSE and ECO
	 * leave them.
	 *
	 * @return true if at least one hostile was pushed out.
	 */
	public static boolean expelBlockedMonsters(ServerLevel level, BlockPos pos, ShieldState state) {
		if (!mobBarrierExpelsInside(state.mode)) {
			return false;
		}

		Vec3 center = Vec3.atCenterOf(pos);
		double radius = currentRadius(state);
		double areaSize = 2.0 * radius + 8.0;
		AABB area = AABB.ofSize(center, areaSize, areaSize, areaSize);

		boolean pushed = false;
		for (Mob hostile : level.getEntitiesOfClass(Mob.class, area)) {
			if (hostile instanceof Enemy && hostile.isAlive() && applyMobBarrier(center, radius, state, hostile, true)) {
				pushed = true;
			}
		}

		return pushed;
	}

	private static @Nullable String ownerName(Entity owner) {
		return owner instanceof Player player ? player.getGameProfile().name() : null;
	}
}
