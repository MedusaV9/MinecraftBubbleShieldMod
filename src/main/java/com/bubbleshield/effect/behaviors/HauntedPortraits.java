package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Haunted portraits parked near the wall at eye level: each frame is a
 * palette-dust rectangle drawn in the plane tangent to the wall, with a
 * secondary-dust canvas smudge and a pair of GLOW eyes inside that drift and
 * blink deterministically. Frames re-hang on hash anchors every two minutes.
 * The outline itself is palette dust, so the owner color override recolors
 * the whole gallery.
 *
 * <ul>
 * <li>v0: three portraits spaced around the wall</li>
 * <li>v1: a crowded gallery (six frames)</li>
 * <li>v2: one grand portrait (double size, a dense 24-point outline, two eye pairs)</li>
 * <li>v3: crooked frames (each canted by a hash-picked roll)</li>
 * <li>v4: watchful eyes (never blink; a second pair glares from below)</li>
 * <li>v5: a flickering gallery (frames wink in and out; the first never fades)</li>
 * <li>v6: a double-hung salon (odd frames hang a row above the others)</li>
 * </ul>
 */
public final class HauntedPortraits implements InsideEffectBehavior {
	public static final String ID = "haunted_portraits";
	/** Worst case v4: 7 frames x (outline 12 + canvas dust 1 + 2 eye pairs = 4 glow) = 119 particles/pulse (v2: 3 x (24 + 1 + 4) = 87). */
	private static final int MAX_FRAMES = 7;
	/** Dust points on a standard frame outline (v2's grand portrait doubles this). */
	private static final int OUTLINE_POINTS = 12;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		long pulse = gameTime / 10L;
		// The gallery re-hangs on new hash anchors every two minutes.
		long epoch = gameTime / 2400L;
		int base = switch (variant) {
			case 1 -> 6;
			case 2 -> 1;
			case 5 -> 5;
			default -> variant == 6 ? 4 : 3;
		};
		int frames = ctx.scaleCount(base, MAX_FRAMES);
		double halfWidth = Mth.clamp(radius * 0.1F * def.behaviorStrength(), 0.6F, 4.0F) * (variant == 2 ? 1.8 : 1.0);
		double halfHeight = halfWidth * 1.3;
		// Eye level, lifted so the frame bottom stays above the dome floor.
		double eyeLevel = Math.max(Math.min(1.6, radius * 0.3), halfHeight + 0.2);
		ParticleOptions outline = new DustParticleOptions(ctx.pickColor(def.argbPrimary(), def.argbSecondary()) & 0xFFFFFF, 0.9F);
		ParticleOptions canvas = new DustParticleOptions(ctx.secondaryColor(def.argbSecondary()) & 0xFFFFFF, 1.2F);
		for (int f = 0; f < frames; f++) {
			if (variant == 5 && f != 0 && BehaviorSupport.hash01(BehaviorSupport.mix(pulse * 379L + f * 31L)) < 0.3) {
				continue;
			}

			long seed = BehaviorSupport.mix(epoch * 449L + f * 17L + variant);
			double angle = Math.PI * 2.0 * f / frames + BehaviorSupport.hash01(seed) * 0.5;
			double rowLift = variant == 6 && f % 2 == 1 ? halfHeight * 2.6 : 0.0;
			double yOff = eyeLevel + rowLift;
			// Parked near the wall: as far out as the 0.85r shell allows at this height.
			double wallDist = Math.min(radius * 0.8, Math.sqrt(Math.max(0.0, radius * radius * 0.7225 - yOff * yOff)));
			Vec3 frameCenter = new Vec3(
					center.x + Math.cos(angle) * wallDist,
					center.y + yOff,
					center.z + Math.sin(angle) * wallDist);
			// The tangent along the wall; the frame spans u horizontally, y vertically.
			Vec3 u = new Vec3(-Math.sin(angle), 0.0, Math.cos(angle));
			double roll = variant == 3 ? (BehaviorSupport.hash01(seed + 3L) - 0.5) * 0.7 : 0.0;
			emitOutline(level, shape, center, radius, variant, frameCenter, u, halfWidth, halfHeight, roll, outline);
			BehaviorSupport.sendContained(level, canvas, shape, center, radius,
					frameCenter.x, frameCenter.y, frameCenter.z, 1, halfWidth * 0.25, halfHeight * 0.25, halfWidth * 0.25, 0.0);
			emitEyes(level, shape, center, radius, variant, frameCenter, u, halfWidth, halfHeight, seed, pulse, f, gameTime);
		}
	}

	private static void emitOutline(ServerLevel level, ShieldShape shape, Vec3 center, float radius,
			int variant, Vec3 frameCenter, Vec3 u, double halfWidth, double halfHeight, double roll, ParticleOptions outline) {
		int points = variant == 2 ? OUTLINE_POINTS * 2 : OUTLINE_POINTS;
		for (int p = 0; p < points; p++) {
			// Walk the rectangle perimeter: bottom, right, top, left.
			double tt = p * 4.0 / points;
			int edge = (int) tt;
			double frac = tt - edge;
			double a = switch (edge) {
				case 0 -> -halfWidth + 2.0 * halfWidth * frac;
				case 1 -> halfWidth;
				case 2 -> halfWidth - 2.0 * halfWidth * frac;
				default -> -halfWidth;
			};
			double b = switch (edge) {
				case 0 -> -halfHeight;
				case 1 -> -halfHeight + 2.0 * halfHeight * frac;
				case 2 -> halfHeight;
				default -> halfHeight - 2.0 * halfHeight * frac;
			};
			// v3's cant: roll the frame plane coordinates before mapping to world.
			double ra = a * Math.cos(roll) - b * Math.sin(roll);
			double rb = a * Math.sin(roll) + b * Math.cos(roll);
			BehaviorSupport.sendContained(level, outline, shape, center, radius,
					frameCenter.x + u.x * ra, frameCenter.y + rb, frameCenter.z + u.z * ra,
					1, 0.02, 0.02, 0.02, 0.0);
		}
	}

	private static void emitEyes(ServerLevel level, ShieldShape shape, Vec3 center, float radius,
			int variant, Vec3 frameCenter, Vec3 u, double halfWidth, double halfHeight, long seed, long pulse, int frame, long gameTime) {
		// A deterministic blink every three pulses or so; v4 never blinks.
		if (variant != 4 && BehaviorSupport.hash01(BehaviorSupport.mix(pulse / 3L * 601L + frame * 29L + variant)) < 0.25) {
			return;
		}

		double gazeX = Math.sin(gameTime * 0.011 + BehaviorSupport.hash01(seed + 4L) * Math.PI * 2.0) * halfWidth * 0.35;
		double gazeY = Math.sin(gameTime * 0.017 + BehaviorSupport.hash01(seed + 5L) * Math.PI * 2.0) * halfHeight * 0.3;
		double separation = Math.max(0.18, halfWidth * 0.25);
		emitEyePair(level, shape, center, radius, frameCenter, u, gazeX, gazeY, separation);
		if (variant == 4 || variant == 2) {
			// The second pair glares from lower in the canvas, drifting opposite.
			emitEyePair(level, shape, center, radius, frameCenter, u, -gazeX, gazeY - halfHeight * 0.45, separation);
		}
	}

	private static void emitEyePair(ServerLevel level, ShieldShape shape, Vec3 center, float radius,
			Vec3 frameCenter, Vec3 u, double gazeX, double gazeY, double separation) {
		for (int e = -1; e <= 1; e += 2) {
			double a = gazeX + e * separation * 0.5;
			BehaviorSupport.sendContained(level, ParticleTypes.GLOW, shape, center, radius,
					frameCenter.x + u.x * a, frameCenter.y + gazeY, frameCenter.z + u.z * a,
					1, 0.01, 0.01, 0.01, 0.0);
		}
	}
}
