package com.bubbleshield.gametest;

import com.bubbleshield.shield.SurfaceWaveMath;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Coverage for WP-Dyn's pure surface-dynamics math ({@link SurfaceWaveMath}):
 * traveling-wave decay and clamps, crest weight bounds, the aperture's lip /
 * alpha / radius-smoothing formulas with their pinned hysteresis constants,
 * and the last-stand tremble bound. All CPU math, no render context needed —
 * these run headless and pin the constants the client mesh deformation relies
 * on.
 */
public class SurfaceDynamicsGameTests {
	/**
	 * A dedicated (but otherwise vanilla-default) test environment,
	 * {@code data/bubbleshield/test_environment/dynamics.json}: the vanilla
	 * runner batches tests by environment (50 per batch, ticked in parallel),
	 * and adding this class to any pre-existing batch would reshuffle which
	 * tests overlap in time (see ColorGameTests for the full rationale).
	 */
	private static final String ISOLATED_ENVIRONMENT = "bubbleshield:dynamics";
	/** Representative shield radii sampled by the sweeps (min 4 .. max 100). */
	private static final float[] RADII = {4.0F, 10.0F, 25.0F, 100.0F};

	/**
	 * (1) A wave is visually dead by age 2 s: the time envelope e^(-1.6 * 2)
	 * (~0.04) caps any in-contract displacement below 0.02 blocks everywhere on
	 * the surface, at any health, for ages 2 s and beyond.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void displacementNearZeroByTwoSeconds(GameTestHelper helper) {
		for (float radius : RADII) {
			for (float age = 2.0F; age <= 4.0F; age += 0.25F) {
				for (float d = 0.0F; d <= 2.0F * radius; d += 0.5F) {
					float disp = SurfaceWaveMath.impactDisplacement(d, age, 1.0F, 0.0F, radius);
					helper.assertTrue(Math.abs(disp) < 0.02F,
							"displacement must be ~0 at age >= 2s, got " + disp + " at d=" + d + " age=" + age + " r=" + radius);
				}
			}
		}

		helper.succeed();
	}

	/**
	 * (2) The per-impact clamp: even out-of-contract strengths (the amplitude
	 * formula tops out at 0.3 for strength &le; 1, so only bad inputs can push
	 * past it) never displace beyond +-{@link SurfaceWaveMath#PER_IMPACT_CLAMP}.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void perImpactDisplacementClamped(GameTestHelper helper) {
		for (float strength : new float[] {1.0F, 2.0F, 10.0F, 100.0F}) {
			for (float d = 0.0F; d <= 20.0F; d += 0.25F) {
				for (float age = 0.0F; age <= 1.0F; age += 0.05F) {
					float disp = SurfaceWaveMath.impactDisplacement(d, age, strength, 0.0F, 10.0F);
					helper.assertTrue(Math.abs(disp) <= SurfaceWaveMath.PER_IMPACT_CLAMP + 1.0e-6F,
							"per-impact displacement must clamp at +-0.35, got " + disp + " for strength " + strength);
				}
			}
		}

		// The break pulse rides the same clamp.
		float pulse = SurfaceWaveMath.breakPulseDisplacement(0.0F, 0.0F, 10.0F);
		helper.assertTrue(Math.abs(pulse) <= SurfaceWaveMath.PER_IMPACT_CLAMP + 1.0e-6F,
				"the break pulse must respect the per-impact clamp, got " + pulse);
		helper.succeed();
	}

	/** (3) The accumulated-displacement clamp constant is pinned at 0.5 blocks. */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void totalDisplacementClampPinned(GameTestHelper helper) {
		helper.assertTrue(SurfaceWaveMath.TOTAL_DISPLACEMENT_CLAMP == 0.5F,
				"TOTAL_DISPLACEMENT_CLAMP must stay 0.5, got " + SurfaceWaveMath.TOTAL_DISPLACEMENT_CLAMP);
		helper.succeed();
	}

	/**
	 * (4) The aperture alpha field: fully transparent at the aperture point of a
	 * fully-open hole, fully opaque beyond the fade range (2.1 + 6.0 blocks at
	 * holeR 2.8 — the legacy dissolve's 6.0 linear ramp shifted by the open
	 * core), monotone in between, and a collapsed hole is fully opaque even at
	 * d = 0 (the 0/0 guard).
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void apertureAlphaEndpointsAndGuard(GameTestHelper helper) {
		float open = SurfaceWaveMath.HOLE_R_MAX;
		helper.assertTrue(SurfaceWaveMath.apertureAlphaFactor(0.0F, open) == 0.0F,
				"the fully-open aperture center must be fully transparent");
		helper.assertTrue(SurfaceWaveMath.apertureAlphaFactor(2.0F, open) == 0.0F,
				"inside 0.75 * holeR (2.1) must still be fully transparent");
		float edge = SurfaceWaveMath.APERTURE_ALPHA_INNER_FRAC * open + SurfaceWaveMath.APERTURE_FADE_RANGE;
		helper.assertTrue(SurfaceWaveMath.apertureAlphaFactor(edge, open) == 1.0F,
				"the fade must complete exactly at 2.1 + 6.0 blocks");
		helper.assertTrue(SurfaceWaveMath.apertureAlphaFactor(50.0F, open) == 1.0F,
				"far beyond the range must be fully opaque");

		float prev = -1.0F;
		for (float d = 0.0F; d <= 10.0F; d += 0.1F) {
			float factor = SurfaceWaveMath.apertureAlphaFactor(d, open);
			helper.assertTrue(factor >= prev, "the alpha ramp must be monotone, dipped at d=" + d);
			prev = factor;
		}

		helper.assertTrue(SurfaceWaveMath.apertureAlphaFactor(0.0F, 0.0F) == 1.0F,
				"a collapsed hole must be fully opaque at d = 0 (no NaN)");
		helper.succeed();
	}

	/**
	 * (5) The lip ridge peaks exactly at {@code d = holeR} with height
	 * {@code LIP_MAX * holeR / HOLE_R_MAX}, falling off on both sides.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void lipPeaksAtHoleRadius(GameTestHelper helper) {
		for (float holeR : new float[] {0.7F, 1.4F, SurfaceWaveMath.HOLE_R_MAX}) {
			float peak = SurfaceWaveMath.lipDisplacement(holeR, holeR);
			float expected = SurfaceWaveMath.LIP_MAX * holeR / SurfaceWaveMath.HOLE_R_MAX;
			helper.assertTrue(Math.abs(peak - expected) < 1.0e-6F,
					"lip peak at d = holeR must be LIP_MAX * holeR / 2.8, got " + peak);
			for (float d = 0.0F; d <= holeR + 6.0F; d += 0.05F) {
				helper.assertTrue(SurfaceWaveMath.lipDisplacement(d, holeR) <= peak + 1.0e-6F,
						"the lip must peak AT the hole radius, exceeded at d=" + d + " holeR=" + holeR);
			}
		}

		helper.succeed();
	}

	/**
	 * (6) Aperture radius smoothing is monotone toward the target with no
	 * overshoot, opening is faster than closing (tau 0.15 vs 0.5), and the
	 * hysteresis edges are pinned at open &le; 5.5 / close &gt; 6.5.
	 */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void radiusStepMonotoneAndHysteresisPinned(GameTestHelper helper) {
		float cur = 0.0F;
		for (int i = 0; i < 100; i++) {
			float next = SurfaceWaveMath.apertureRadiusStep(cur, SurfaceWaveMath.HOLE_R_MAX, 0.05F, true);
			helper.assertTrue(next >= cur && next <= SurfaceWaveMath.HOLE_R_MAX,
					"opening must be monotone without overshoot, got " + next + " from " + cur);
			cur = next;
		}

		helper.assertTrue(cur > SurfaceWaveMath.HOLE_R_MAX - 0.01F, "5 s of opening must converge, got " + cur);
		for (int i = 0; i < 100; i++) {
			float next = SurfaceWaveMath.apertureRadiusStep(cur, 0.0F, 0.05F, false);
			helper.assertTrue(next <= cur && next >= 0.0F, "closing must be monotone without overshoot");
			cur = next;
		}

		float opened = SurfaceWaveMath.apertureRadiusStep(1.0F, 2.8F, 0.05F, true);
		float closed = SurfaceWaveMath.apertureRadiusStep(1.0F, 2.8F, 0.05F, false);
		helper.assertTrue(opened > closed, "the opening tau (0.15) must approach faster than the closing tau (0.5)");
		helper.assertTrue(SurfaceWaveMath.APERTURE_OPEN_DIST == 5.5F,
				"the open hysteresis edge must stay 5.5, got " + SurfaceWaveMath.APERTURE_OPEN_DIST);
		helper.assertTrue(SurfaceWaveMath.APERTURE_CLOSE_DIST == 6.5F,
				"the close hysteresis edge must stay 6.5, got " + SurfaceWaveMath.APERTURE_CLOSE_DIST);
		helper.succeed();
	}

	/** (7) Crest weight stays inside {@code [0, CREST_MAX * strength]} everywhere. */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void crestWeightBounds(GameTestHelper helper) {
		for (float radius : RADII) {
			for (float strength : new float[] {0.1F, 0.6F, 1.0F}) {
				for (float age = 0.0F; age <= 2.5F; age += 0.05F) {
					for (float d = 0.0F; d <= radius; d += 0.5F) {
						float weight = SurfaceWaveMath.crestWeight(d, age, strength, radius);
						helper.assertTrue(weight >= 0.0F && weight <= SurfaceWaveMath.CREST_MAX * strength + 1.0e-6F,
								"crest weight must stay in [0, 0.6 * strength], got " + weight);
					}
				}
			}
		}

		float breakWeight = SurfaceWaveMath.breakCrestWeight(0.0F, 10.0F);
		helper.assertTrue(breakWeight >= 0.0F && breakWeight <= SurfaceWaveMath.CREST_MAX + 1.0e-6F,
				"the break crest weight must stay in [0, 0.6], got " + breakWeight);
		helper.succeed();
	}

	/** (8) The tremble offset is bounded to +-0.05 blocks for any vertex and time. */
	@GameTest(environment = ISOLATED_ENVIRONMENT)
	public void trembleBounded(GameTestHelper helper) {
		for (float t = 0.0F; t <= 3.0F; t += 0.017F) {
			for (float x = -1.0F; x <= 1.0F; x += 0.4F) {
				for (float y = -1.0F; y <= 1.0F; y += 0.4F) {
					for (float z = -1.0F; z <= 1.0F; z += 0.4F) {
						float offset = SurfaceWaveMath.trembleOffset(x, y, z, t);
						helper.assertTrue(Math.abs(offset) <= SurfaceWaveMath.TREMBLE_AMPLITUDE + 1.0e-6F,
								"tremble must stay within +-0.05, got " + offset);
					}
				}
			}
		}

		// The degenerate zero-length vertex (the dome disc center) must not NaN.
		float zero = SurfaceWaveMath.trembleOffset(0.0F, 0.0F, 0.0F, 1.0F);
		helper.assertTrue(!Float.isNaN(zero) && Math.abs(zero) <= SurfaceWaveMath.TREMBLE_AMPLITUDE + 1.0e-6F,
				"the zero-position tremble must be finite and bounded, got " + zero);
		helper.succeed();
	}
}
