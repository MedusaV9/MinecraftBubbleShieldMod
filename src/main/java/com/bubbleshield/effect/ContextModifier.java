package com.bubbleshield.effect;

/**
 * Pure context-reactivity logic: turns a {@link ContextProfile} plus the current
 * environment/shield inputs into a {@link ContextState} that inside behaviors apply
 * to their tick (particle counts, throttle cadence, dust colors, extra sparks).
 *
 * <p>Deliberately world-free so the decision table is unit-testable headlessly
 * (26.2 world time runs on the WorldClock system and cannot be set in gametests).
 */
public final class ContextModifier {
	/**
	 * The modulation an inside behavior applies for one tick.
	 *
	 * @param countMult         multiplier for particle counts (results stay clamped to each
	 *                          behavior's per-pulse budget, never above 128)
	 * @param periodDivisor     divisor for the behavior's gameTime throttle
	 *                          (effective throttle = base / divisor, floored at 1); never 0
	 * @param useSecondaryColor when true, dust-colored behaviors use the effect's secondary color
	 * @param extraSparks       when true, a small electric spark sprinkle is added to the tick
	 */
	public record ContextState(float countMult, int periodDivisor, boolean useSecondaryColor, boolean extraSparks) {
		/** No modulation: behaviors run with their original v0-era semantics. */
		public static final ContextState NEUTRAL = new ContextState(1.0F, 1, false, false);

		public ContextState {
			periodDivisor = Math.max(1, periodDivisor);
		}

		/** The behavior's effective gameTime throttle: {@code max(1, base / periodDivisor)}. */
		public long effectiveThrottle(long baseThrottleTicks) {
			return Math.max(1L, baseThrottleTicks / this.periodDivisor);
		}

		/** Scales a particle count by {@link #countMult}, clamped into {@code [0, max]}. */
		public int scaleCount(int count, int max) {
			return Math.clamp(Math.round(count * this.countMult), 0, max);
		}

		/** Picks the dust color: the secondary color when {@link #useSecondaryColor}, else the primary. */
		public int pickColor(int argbPrimary, int argbSecondary) {
			return this.useSecondaryColor ? argbSecondary : argbPrimary;
		}
	}

	private ContextModifier() {
	}

	/**
	 * Computes the modulation for one behavior tick.
	 *
	 * @param profile       the effect's context profile
	 * @param night         {@code level.isDarkOutside()}
	 * @param raining       {@code level.isRaining()}
	 * @param playersInside number of players currently inside the shield
	 * @param healthFrac    shield health fraction in {@code [0, 1]}
	 */
	public static ContextState compute(ContextProfile profile, boolean night, boolean raining, int playersInside, float healthFrac) {
		return switch (profile) {
			case NIGHT_BLOOM -> night ? new ContextState(2.0F, 1, false, false) : ContextState.NEUTRAL;
			case STORM_CHARGED -> raining ? new ContextState(1.0F, 1, false, true) : ContextState.NEUTRAL;
			case CROWD_SCALE -> new ContextState(Math.min(3.0F, 1.0F + 0.5F * Math.max(0, playersInside)), 1, false, false);
			case LOW_HEALTH_FRENZY -> healthFrac < 0.5F ? new ContextState(1.0F, 2, false, false) : ContextState.NEUTRAL;
			case HEALTH_HUE -> healthFrac < 0.5F ? new ContextState(1.0F, 1, true, false) : ContextState.NEUTRAL;
			case NONE -> ContextState.NEUTRAL;
		};
	}
}
