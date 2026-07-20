package com.bubbleshield.effect;

import java.util.Locale;

/**
 * Immutable description of one selectable shield effect.
 *
 * <p>Server-safe pure data: colors are packed ARGB ints, {@code surface} names the
 * technique family (tooltips/invariants; the actual per-effect fragment shader is
 * {@link #surfaceShaderId()}), {@code paramA}/{@code paramB} parameterize the
 * (client-side) surface renderer,
 * {@code insideBehaviorId}/{@code behaviorVariant}/{@code behaviorStrength} select and
 * tune the server-side {@link InsideEffectBehavior}, {@code guard} and {@code context}
 * pick the boundary-retaliation style and reactivity profile, the {@code ambient*}
 * fields describe the looping ambient sound (vanilla sound id without namespace, e.g.
 * {@code "block.beacon.ambient"}), and {@code screenTemplate} names the screen-fx
 * technique family (tooltips/invariants; the actual per-effect screen shader referenced
 * by {@code post_effect/effect_NN.json} is {@link #screenShaderId()}).
 */
public record EffectDefinition(
		int id,
		String nameKey,
		int argbPrimary,
		int argbSecondary,
		SurfaceTemplate surface,
		float paramA,
		float paramB,
		String insideBehaviorId,
		int behaviorVariant,
		float behaviorStrength,
		GuardStyle guard,
		ContextProfile context,
		String ambientSoundId,
		float ambientPitch,
		int ambientPeriodTicks,
		String screenTemplate,
		String screenEffectName
) {
	/**
	 * Id-derived path of this effect's dedicated bubble surface fragment shader
	 * ({@code assets/bubbleshield/shaders/bubble/fx_NNN.fsh}, client source set). Every
	 * effect has its own shader; {@link #surface} remains technique-family metadata
	 * (tooltips, catalogue invariants), not a shader selector.
	 */
	public String surfaceShaderId() {
		return String.format(Locale.ROOT, "bubble/fx_%03d", id);
	}

	/**
	 * Id-derived path of this effect's dedicated screen post-effect fragment shader
	 * ({@code assets/bubbleshield/shaders/screenfx/sfx_NNN.fsh}, main source set so the
	 * gametests can see it). Every effect has its own screen shader; {@link #screenTemplate}
	 * remains technique-family metadata (invariants, generator seeding), not a shader
	 * selector.
	 */
	public String screenShaderId() {
		return String.format(Locale.ROOT, "screenfx/sfx_%03d", id);
	}

	/**
	 * Creates a definition, deriving {@code nameKey} ("effect.bubbleshield.NN") and
	 * {@code screenEffectName} ("effect_NN") from the id.
	 */
	public static EffectDefinition of(int id, int argbPrimary, int argbSecondary, SurfaceTemplate surface, float paramA, float paramB,
			String insideBehaviorId, int behaviorVariant, float behaviorStrength, GuardStyle guard, ContextProfile context,
			String ambientSoundId, float ambientPitch, int ambientPeriodTicks, String screenTemplate) {
		return new EffectDefinition(
				id,
				String.format(Locale.ROOT, "effect.bubbleshield.%02d", id),
				argbPrimary,
				argbSecondary,
				surface,
				paramA,
				paramB,
				insideBehaviorId,
				behaviorVariant,
				behaviorStrength,
				guard,
				context,
				ambientSoundId,
				ambientPitch,
				ambientPeriodTicks,
				screenTemplate,
				String.format(Locale.ROOT, "effect_%02d", id)
		);
	}
}
