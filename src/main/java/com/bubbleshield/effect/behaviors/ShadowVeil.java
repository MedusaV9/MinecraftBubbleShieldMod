package com.bubbleshield.effect.behaviors;

import com.bubbleshield.effect.ContextModifier.ContextState;
import com.bubbleshield.effect.EffectDefinition;
import com.bubbleshield.effect.InsideEffectBehavior;
import com.bubbleshield.shield.ShieldGeometry;
import com.bubbleshield.shield.ShieldShape;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.ShriekParticleOption;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A creeping shadow layer hugging the floor: slow ink wisps crawl in a spiral
 * carpet around the projector, thickening towards the wall.
 *
 * <ul>
 * <li>v0: squid-ink carpet</li>
 * <li>v1: large-smoke carpet with a smoky central column</li>
 * <li>v2: ink carpet that grants sneaking players inside Invisibility</li>
 * <li>v3: glow-squid ink carpet (eerie teal shimmer)</li>
 * <li>v4: fast thin smoke carpet spiralling inward</li>
 * <li>v5: mycelium dust carpet with drifting spore columns</li>
 * <li>v6: ink carpet plus a periodic silent shriek pip above each veiled player
 * (one pip every 200 ticks; no sound and no entry detection)</li>
 * </ul>
 */
public final class ShadowVeil implements InsideEffectBehavior {
	public static final String ID = "shadow_veil";
	private static final int MAX_POINTS = 96;

	@Override
	public void tick(ServerLevel level, Vec3 center, float radius, ShieldShape shape, EffectDefinition def, long gameTime, ContextState ctx) {
		if (gameTime % ctx.effectiveThrottle(10L) != 0L) {
			return;
		}

		int variant = def.behaviorVariant();
		SimpleParticleType wisp = switch (variant) {
			case 1, 4 -> variant == 1 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE;
			case 3 -> ParticleTypes.GLOW_SQUID_INK;
			case 5 -> ParticleTypes.MYCELIUM;
			default -> ParticleTypes.SQUID_INK;
		};
		double crawl = gameTime / 10.0 * (variant == 4 ? -0.3 : 0.1);
		int arms = 3;
		double reach = radius * 0.9 * Mth.clamp(def.behaviorStrength(), 0.8F, 1.1F);
		int budget = MAX_POINTS / arms;
		int points = ctx.scaleCount(Mth.clamp((int) (reach / 0.5), 6, budget), budget);
		for (int arm = 0; arm < arms; arm++) {
			for (int i = 0; i < points; i++) {
				double t = (i + 0.5) / points;
				double angle = crawl + Math.PI * 2.0 * arm / arms + t * Math.PI * 1.5;
				double dist = reach * t;
				// A strength-widened carpet (reach up to 0.99r) can cross the 0.98r line.
				BehaviorSupport.sendContained(level, wisp, shape, center, radius,
						center.x + Math.cos(angle) * dist, center.y + 0.15, center.z + Math.sin(angle) * dist,
						1, 0.15, 0.02, 0.15, 0.0);
			}
		}

		if (variant == 1) {
			BehaviorSupport.sendContained(level, ParticleTypes.LARGE_SMOKE, shape, center, radius, center.x, center.y + 0.5, center.z, ctx.scaleCount(4, 8), 0.2, 0.6, 0.2, 0.01);
		} else if (variant == 5) {
			BehaviorSupport.sendContained(level, ParticleTypes.WARPED_SPORE, shape, center, radius, center.x, center.y + 1.0, center.z, ctx.scaleCount(6, 12), radius * 0.3, 0.8, radius * 0.3, 0.0);
		} else if (variant == 2 || variant == 6) {
			AABB box = AABB.ofSize(center, radius * 2.0, radius * 2.0, radius * 2.0);
			for (Player player : level.getEntitiesOfClass(Player.class, box)) {
				if (!ShieldGeometry.isInside(shape, center, radius, player.position())) {
					continue;
				}

				if (variant == 2 && player.isShiftKeyDown()) {
					player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 60, 0));
				} else if (variant == 6 && gameTime % 200L == 0L) {
					// A silent shriek pip above the veiled player once per ten seconds
					// (contained: a tall player hugging the wall could poke it out).
					BehaviorSupport.sendContained(level, new ShriekParticleOption(0), shape, center, radius,
							player.getX(), player.getY() + 2.2, player.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
				}
			}
		}
	}
}
