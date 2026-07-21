package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.PowerParticleOption;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * A séance ring: candle points evenly spaced on a 0.6r circle, enchant glyphs
 * streaming toward the middle (count=0 fly-towards form) and a periodic
 * dragon-breath swirl over the "table". Candle bases carry palette dust, so
 * the ritual recolors with the owner override. DRAGON_BREATH takes a
 * {@code PowerParticleOption} in 26.2 (not a simple type).
 *
 * <ul>
 * <li>v0: five candles</li>
 * <li>v1: nine candles with dense glyphs</li>
 * <li>v2: a breathless rite (note chimes instead of dragon breath)</li>
 * <li>v3: an inverted rite (glyphs stream outward)</li>
 * <li>v4: a midnight rite (dark dust circle, souls at the center)</li>
 * <li>v5: a flicker séance (candles blink in hash patterns)</li>
 * <li>v6: a grand rite (two concentric counter-rotating circles)</li>
 * </ul>
 */
public final class SeanceCircle implements InsideEffectBehavior {
	public static final String ID = "seance_circle";
	/** Worst case v6: 13 candles x 3 + glyphs 13 + swirl 4 = 56 particles/pulse. */
	private static final int MAX_CANDLES = 13;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		long pulse = gameTime / 10L;
		int candles = switch (variant) {
			case 0 -> 5;
			case 1 -> 9;
			case 6 -> 13; // 5 inner + 8 outer
			default -> 7;
		};
		candles = ctx.scaleCount(candles, MAX_CANDLES);
		ParticleOptions base = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.7F);
		ParticleOptions darkCircle = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.0F);
		double y = center.y + 1.0;
		for (int c = 0; c < candles; c++) {
			// v6 splits the ring: the first five stand inside, the rest outside.
			boolean inner = variant == 6 && c < 5;
			int ringSize = variant == 6 ? (inner ? Math.min(candles, 5) : Math.max(candles - 5, 1)) : candles;
			int ringIndex = inner || variant != 6 ? c : c - 5;
			double ringRadius = radius * (variant == 6 ? (inner ? 0.45 : 0.65) : 0.6);
			double angle = Math.PI * 2.0 * ringIndex / ringSize;
			double x = center.x + Math.cos(angle) * ringRadius;
			double z = center.z + Math.sin(angle) * ringRadius;
			if (variant == 5 && BehaviorSupport.hash01(BehaviorSupport.mix(pulse * 89L + c)) < 0.35) {
				// The blinked-out candle leaves only a smoke thread.
				BehaviorSupport.sendContained(level, ParticleTypes.SMOKE, shape, center, radius,
						x, y + 0.1, z, 1, 0.02, 0.06, 0.02, 0.01);
				continue;
			}

			if (variant == 4) {
				// The midnight rite draws the circle in dark dust instead of flames.
				BehaviorSupport.sendContained(level, darkCircle, shape, center, radius, x, y, z, 1, 0.05, 0.05, 0.05, 0.0);
			} else {
				BehaviorSupport.sendContained(level, ParticleTypes.SMALL_FLAME, shape, center, radius,
						x, y, z, 1, 0.02, 0.04, 0.02, 0.0);
				if ((pulse + c) % 3 == 0L) {
					BehaviorSupport.sendContained(level, ParticleTypes.WAX_ON, shape, center, radius,
							x, y + 0.15, z, 1, 0.05, 0.05, 0.05, 0.0);
				}
			}

			BehaviorSupport.sendContained(level, base, shape, center, radius, x, y - 0.3, z, 1, 0.04, 0.04, 0.04, 0.0);
		}

		// The glyph stream: one enchant glyph per candle slot, orbiting as it flows.
		int glyphs = variant == 1 ? candles * 2 : candles;
		double flow = pulse * 0.4;
		Vec3 middle = BehaviorSupport.containPoint(shape, center, radius, new Vec3(center.x, y, center.z));
		for (int g = 0; g < glyphs; g++) {
			double dir = variant == 6 && g % 2 == 1 ? -1.0 : 1.0;
			double angle = flow * dir + Math.PI * 2.0 * g / glyphs;
			Vec3 rim = BehaviorSupport.containPoint(shape, center, radius, new Vec3(
					center.x + Math.cos(angle) * radius * 0.6, y + 0.2, center.z + Math.sin(angle) * radius * 0.6));
			if (variant == 3) {
				// Inverted: glyphs fly from the middle out to the rim.
				BehaviorSupport.sendContained(level, ParticleTypes.ENCHANT, shape, center, radius, rim.x, rim.y, rim.z, 0,
						middle.x - rim.x, middle.y - rim.y, middle.z - rim.z, 1.0);
			} else {
				BehaviorSupport.sendContained(level, ParticleTypes.ENCHANT, shape, center, radius, middle.x, middle.y, middle.z, 0,
						rim.x - middle.x, rim.y - middle.y, rim.z - middle.z, 1.0);
			}
		}

		// The centerpiece, every fourth pulse.
		if (pulse % 4L != 0L) {
			return;
		}

		switch (variant) {
			case 2 -> {
				BehaviorSupport.sendContained(level, ParticleTypes.NOTE, shape, center, radius,
						center.x, y + 0.5, center.z, 2, 0.3, 0.3, 0.3, 0.0);
				level.playSound(null, center.x, y, center.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.AMBIENT, 0.4F, 1.2F);
			}
			case 4 -> BehaviorSupport.sendContained(level, ParticleTypes.SOUL, shape, center, radius,
					center.x, y - 0.5, center.z, 2, 0.15, 0.4, 0.15, 0.02);
			default -> BehaviorSupport.sendContained(level, PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1.0F),
					shape, center, radius, center.x, y - 0.2, center.z, 4, 0.3, 0.15, 0.3, 0.02);
		}
	}
}
