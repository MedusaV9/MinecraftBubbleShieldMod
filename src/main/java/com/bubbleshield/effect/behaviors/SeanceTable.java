package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A central séance table: a secondary-dust tabletop ring bearing small-flame
 * candles on palette dust holders. The candles flicker out one by one in a
 * hash-shuffled order (re-shuffled every sitting), and once the last one dies
 * the table relights them all in a soul flash. Snuff order and stage timing
 * derive from gameTime and {@link BehaviorSupport#hash01}, so one shared
 * instance runs every shield with no fields and no cleanup.
 *
 * <p>Worst-case budget (v6 in the dark stage, countMult 3): table ring 16 +
 * 9 candles x (holder 1 + snuffed smoke 1) + flash soul 12 + flash glint 2 =
 * 48 particles/pulse; the busiest lit stage is v2's 16 + 7 x (holder 1 +
 * flame 1 + wax-on 1 + glyph 1) = 44 (&lt;= 128).
 *
 * <ul>
 * <li>v0: the classic sitting (seven candles, unhurried snuffing)</li>
 * <li>v1: a hurried séance (stages pass twice as fast)</li>
 * <li>v2: the medium's trance (enchant glyphs drawn to the dying flames)</li>
 * <li>v3: the widdershins rite (a turning table, candles die in ring order)</li>
 * <li>v4: the cold reading (soul-fire candles, white-smoke death threads)</li>
 * <li>v5: restless spirits (snuffed candles pop with sculk charge)</li>
 * <li>v6: the grand séance (nine candles, a wider table, a doubled flash)</li>
 * </ul>
 */
public final class SeanceTable implements InsideEffectBehavior {
	public static final String ID = "seance_table";
	/** Worst case v6 dark stage: table 16 + 9 x (holder 1 + smoke 1) + soul 12 + glint 2 = 48 particles/pulse. */
	private static final int MAX_TABLE_POINTS = 16;
	private static final int MAX_CANDLES = 9;
	private static final int MAX_FLASH_SOULS = 12;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		int candles = variant == 6 ? 9 : 7;
		long stageTicks = variant == 1 ? 20L : 40L;
		// At stage s the s lowest-ranked candles are out (stage 0 is fully lit,
		// each stage transition snuffs one more); stage `candles` is the dark
		// stage that ends the sitting with the relighting soul flash.
		long totalStages = candles + 1L;
		long sitting = gameTime / (stageTicks * totalStages);
		int stage = (int) (gameTime / stageTicks % totalStages);
		boolean darkStage = stage == candles;
		double tableRadius = radius * (variant == 6 ? 0.3 : 0.25) * Mth.clamp(def.behaviorStrength(), 0.7F, 1.3F);
		double tableY = center.y + 0.9;
		double turn = variant == 3 ? -gameTime * 0.006 : 0.0;
		ParticleOptions tabletop = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 0.8F);
		ParticleOptions holder = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.6F);

		int tablePoints = ctx.scaleCount(10, MAX_TABLE_POINTS);
		for (int p = 0; p < Math.max(1, tablePoints); p++) {
			double angle = turn + Math.PI * 2.0 * p / Math.max(1, tablePoints);
			BehaviorSupport.sendContained(level, tabletop, shape, center, radius,
					center.x + Math.cos(angle) * tableRadius, tableY, center.z + Math.sin(angle) * tableRadius,
					1, 0.05, 0.02, 0.05, 0.0);
		}

		for (int c = 0; c < candles; c++) {
			double angle = turn + Math.PI * 2.0 * c / candles;
			double x = center.x + Math.cos(angle) * tableRadius * 0.8;
			double z = center.z + Math.sin(angle) * tableRadius * 0.8;
			double flameY = tableY + 0.45;
			BehaviorSupport.sendContained(level, holder, shape, center, radius, x, tableY + 0.2, z, 1, 0.03, 0.08, 0.03, 0.0);
			boolean lit = !darkStage && snuffRank(sitting, variant, c, candles) >= stage;
			if (lit) {
				ParticleOptions flame = variant == 4 ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.SMALL_FLAME;
				BehaviorSupport.sendContained(level, flame, shape, center, radius, x, flameY, z, 1, 0.02, 0.04, 0.02, 0.0);
				if ((gameTime / 10L + c) % 4L == 0L) {
					BehaviorSupport.sendContained(level, ParticleTypes.WAX_ON, shape, center, radius,
							x, flameY + 0.15, z, 1, 0.04, 0.04, 0.04, 0.0);
				}

				if (variant == 2) {
					// The trance draws a glyph to each flame still burning
					// (ENCHANT routes through the checked fly-toward path).
					BehaviorSupport.sendContained(level, ParticleTypes.ENCHANT, shape, center, radius,
							x, flameY + 1.4, z, 0, 0.0, 1.5, 0.0, 1.0);
				}
			} else {
				// The death thread rising off a snuffed wick.
				ParticleOptions thread = variant == 4 ? ParticleTypes.WHITE_SMOKE : ParticleTypes.SMOKE;
				BehaviorSupport.sendContained(level, thread, shape, center, radius, x, flameY, z, 1, 0.02, 0.08, 0.02, 0.01);
				if (variant == 5 && (gameTime / 10L + c) % 4L == 0L) {
					BehaviorSupport.sendContained(level, ParticleTypes.SCULK_CHARGE_POP, shape, center, radius,
							x, flameY + 0.1, z, 1, 0.04, 0.04, 0.04, 0.0);
				}
			}
		}

		if (darkStage) {
			// The relighting flash gathers souls over the tabletop all stage.
			int souls = ctx.scaleCount(variant == 6 ? 6 : 4, variant == 6 ? MAX_FLASH_SOULS : 8);
			BehaviorSupport.sendContained(level, ParticleTypes.SOUL, shape, center, radius,
					center.x, tableY + 0.5, center.z, Math.max(1, souls), tableRadius * 0.3, 0.3, tableRadius * 0.3, 0.03);
			BehaviorSupport.sendContained(level, ParticleTypes.WAX_OFF, shape, center, radius,
					center.x, tableY + 0.9, center.z, variant == 6 ? 2 : 1, 0.15, 0.15, 0.15, 0.0);
		}

		// One quiet snuff sound as each stage claims its candle.
		if (gameTime % stageTicks == 0L && stage > 0 && !darkStage) {
			level.playSound(null, center.x, tableY, center.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.AMBIENT, 0.15F, 1.6F);
		} else if (gameTime % stageTicks == 0L && darkStage) {
			level.playSound(null, center.x, tableY, center.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.AMBIENT, 0.35F, 0.8F);
		}
	}

	/**
	 * The candle's place in this sitting's snuff order (0 dies first): the rank
	 * of its per-sitting hash among all candles, ties broken by index — a
	 * stateless shuffle re-rolled each sitting. v3 dies in plain ring order.
	 */
	private static int snuffRank(long sitting, int variant, int candle, int candles) {
		if (variant == 3) {
			return candle;
		}

		double own = BehaviorSupport.hash01(BehaviorSupport.mix(sitting * 607L + candle));
		int rank = 0;
		for (int d = 0; d < candles; d++) {
			if (d == candle) {
				continue;
			}

			double other = BehaviorSupport.hash01(BehaviorSupport.mix(sitting * 607L + d));
			if (other < own || (other == own && d < candle)) {
				rank++;
			}
		}

		return rank;
	}
}
