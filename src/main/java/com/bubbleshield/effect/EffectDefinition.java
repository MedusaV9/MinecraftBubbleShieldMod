package com.bubbleshield.effect;

/**
 * Immutable description of one selectable shield effect.
 *
 * <p>Server-safe pure data: colors are packed ARGB ints, {@code surface} plus
 * {@code paramA}/{@code paramB} parameterize the (client-side) surface renderer and
 * {@code insideBehaviorId} selects the server-side {@link InsideEffectBehavior}.
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
		String screenEffectName
) {
	/**
	 * Creates a definition, deriving {@code nameKey} ("effect.bubbleshield.NN") and
	 * {@code screenEffectName} ("effect_NN") from the id.
	 */
	public static EffectDefinition of(int id, int argbPrimary, int argbSecondary, SurfaceTemplate surface, float paramA, float paramB, String insideBehaviorId) {
		return new EffectDefinition(
				id,
				"effect.bubbleshield.%02d".formatted(id),
				argbPrimary,
				argbSecondary,
				surface,
				paramA,
				paramB,
				insideBehaviorId,
				"effect_%02d".formatted(id)
		);
	}
}
