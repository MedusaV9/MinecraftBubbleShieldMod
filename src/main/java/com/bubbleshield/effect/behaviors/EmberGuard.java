package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldGeometry;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Grants Fire Resistance to every player standing inside the shield.
 *
 * <ul>
 * <li>v0: Fire Resistance</li>
 * <li>v1: Fire Resistance plus a small-flame ring circling the projector</li>
 * <li>v2: Fire Resistance plus dripping-lava accents drifting down mid-bubble</li>
 * <li>v3: Fire Resistance plus a counter-rotating copper-fire ring</li>
 * <li>v4: Fire Resistance plus a soul-fire ring of cold flames</li>
 * <li>v5: Fire Resistance plus sputtering lava-pop jets on the floor</li>
 * <li>v6: Fire Resistance plus a campfire smoke column above the projector</li>
 * </ul>
 */
public final class EmberGuard implements InsideEffectBehavior {
	public static final String ID = "ember_guard";
	private static final int DURATION_TICKS = 60;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		AABB box = AABB.ofSize(center, radius * 2.0, radius * 2.0, radius * 2.0);
		for (Player player : level.getEntitiesOfClass(Player.class, box)) {
			if (!ShieldGeometry.isInside(shape, center, radius, player.position())) {
				continue;
			}

			player.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, DURATION_TICKS, 0));
		}

		if (variant == 1 || variant == 3 || variant == 4) {
			// A rotating ring of flames around the projector at knee height; v3 runs a
			// wider counter-rotating copper ring, v4 a slow ring of cold soul flames.
			double ringRadius = radius * (variant == 3 ? 0.6 : 0.45);
			int points = ctx.scaleCount(Mth.clamp((int) Math.round(Math.PI * 2.0 * ringRadius / 1.5), 8, 48), 48);
			double phase = gameTime / 10.0 * switch (variant) {
				case 3 -> -0.45;
				case 4 -> 0.15;
				default -> 0.3;
			};
			SimpleParticleType flame = switch (variant) {
				case 3 -> ParticleTypes.COPPER_FIRE_FLAME;
				case 4 -> ParticleTypes.SOUL_FIRE_FLAME;
				default -> ParticleTypes.SMALL_FLAME;
			};
			for (int i = 0; i < points; i++) {
				double angle = phase + Math.PI * 2.0 * i / points;
				double x = center.x + Math.cos(angle) * ringRadius;
				double z = center.z + Math.sin(angle) * ringRadius;
				level.sendParticles(flame, true, false, x, center.y + 0.5, z, 1, 0.05, 0.05, 0.05, 0.0);
			}
		} else if (variant == 5) {
			// Sputtering floor jets: a few random lava pops erupting at ground level.
			RandomSource random = level.getRandom();
			int jets = ctx.scaleCount(4, 8);
			for (int i = 0; i < jets; i++) {
				double angle = random.nextDouble() * Math.PI * 2.0;
				double dist = Math.sqrt(random.nextDouble()) * radius * 0.7;
				level.sendParticles(ParticleTypes.LAVA, true, false,
						center.x + Math.cos(angle) * dist, center.y + 0.2, center.z + Math.sin(angle) * dist, 4, 0.15, 0.1, 0.15, 0.0);
			}
		} else if (variant == 6) {
			// A cosy smoke column above the projector, capped inside the shell.
			int puffs = ctx.scaleCount(Mth.clamp((int) (radius * 0.8F * def.behaviorStrength()), 4, 24), 24);
			level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, false, center.x, center.y + 1.0, center.z, puffs, 0.4, radius * 0.3, 0.4, 0.004);
		} else if (variant == 2) {
			// Sparse lava droplets drifting down from mid-bubble height. The gaussian
			// spread of a single count>0 sendParticles call is unbounded (offsets are
			// nextGaussian() * dist), so sample the spread here instead and contain any
			// point past 0.98r (or under a dome's base plane) back inside the shell.
			int count = ctx.scaleCount(Mth.clamp((int) (radius * 1.2F * def.behaviorStrength()), 6, 32), 32);
			RandomSource random = level.getRandom();
			for (int i = 0; i < count; i++) {
				double dx = random.nextGaussian() * radius * 0.45;
				double dy = radius * 0.5 + random.nextGaussian() * radius * 0.2;
				double dz = random.nextGaussian() * radius * 0.45;
				BehaviorSupport.sendContained(level, ParticleTypes.DRIPPING_LAVA, shape, center, radius,
						center.x + dx, center.y + dy, center.z + dz, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}
}
