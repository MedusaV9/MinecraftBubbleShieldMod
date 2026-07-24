package com.bubbleshield.shield;

import net.minecraft.util.Mth;

/**
 * Pure surface-dynamics math for the bubble membrane (WP-Dyn): traveling damped
 * impact waves, the whitelisted-player aperture (displacement lip, alpha field,
 * radius smoothing, open/close hysteresis) and the last-stand tremble. Statics
 * only, NO client imports — the client mesh emitters consume these, and the
 * server-side gametests ({@code SurfaceDynamicsGameTests}) pin every bound and
 * constant here without needing a render context.
 *
 * <p>All distances are WORLD units (blocks), ages/times are SECONDS and the
 * returned displacements are world-unit offsets applied along per-vertex
 * normals by the client mesh code, which clamps the accumulated vector to
 * {@link #TOTAL_DISPLACEMENT_CLAMP}.
 */
public final class SurfaceWaveMath {
	/** Wave propagation speed along the surface (blocks per second). */
	public static final float WAVE_SPEED = 12.0F;
	/** Exponential time decay rate of waves and crests (per second). */
	public static final float TIME_DECAY = 1.6F;
	/** Exponential distance decay rate of waves and crests (per block). */
	public static final float DISTANCE_DECAY = 0.10F;
	/** Base wave amplitude (blocks) at strength 1 and full health. */
	public static final float BASE_AMPLITUDE = 0.12F;
	/** Amplitude boost factor as health drops: {@code 1 + 1.5 * (1 - healthFrac)}. */
	public static final float WEAKNESS_AMPLITUDE_BOOST = 1.5F;
	/** Hard per-impact displacement clamp (blocks); a safety net for out-of-contract strengths. */
	public static final float PER_IMPACT_CLAMP = 0.35F;
	/** Hard clamp on the ACCUMULATED per-vertex displacement vector length (blocks). */
	public static final float TOTAL_DISPLACEMENT_CLAMP = 0.5F;
	/** Peak crest color weight at strength 1 (dimensionless, see {@link #crestWeight}). */
	public static final float CREST_MAX = 0.6F;

	/** Fully-open aperture hole radius (blocks); lip/rim/alpha formulas normalize by it. */
	public static final float HOLE_R_MAX = 2.8F;
	/** Peak lip displacement (blocks) at a fully-open aperture. */
	public static final float LIP_MAX = 0.35F;
	/** Gaussian variance (blocks^2) of the lip/rim profile around {@code d = holeR} (sigma 1.2). */
	public static final float LIP_SIGMA_SQ = 1.44F;
	/** Alpha fade range (blocks) at a fully-open aperture; matches the legacy dissolve's 6.0 linear ramp. */
	public static final float APERTURE_FADE_RANGE = 6.0F;
	/** The alpha field is fully transparent inside {@code 0.75 * holeR} of the aperture point. */
	public static final float APERTURE_ALPHA_INNER_FRAC = 0.75F;
	/** Aperture OPENS when the player is within this many blocks of the wall (hysteresis low edge). */
	public static final float APERTURE_OPEN_DIST = 5.5F;
	/** Aperture CLOSES when the player is beyond this many blocks of the wall (hysteresis high edge). */
	public static final float APERTURE_CLOSE_DIST = 6.5F;
	/** Exponential-approach time constant (seconds) while the aperture opens. */
	public static final float APERTURE_TAU_OPEN = 0.15F;
	/** Exponential-approach time constant (seconds) while the aperture closes (slower than opening). */
	public static final float APERTURE_TAU_CLOSE = 0.5F;

	/** Last-stand tremble amplitude (blocks). */
	public static final float TREMBLE_AMPLITUDE = 0.05F;
	/** Last-stand tremble frequency (Hz). */
	public static final float TREMBLE_HZ = 1.8F;

	private SurfaceWaveMath() {
	}

	/** Wavelength (blocks) of the traveling impact wave: {@code max(4, 0.5 * radius)}. */
	public static float wavelength(float radius) {
		return Math.max(4.0F, 0.5F * radius);
	}

	/** Angular wave number {@code k = 2 * PI / wavelength(radius)} (radians per block). */
	public static float waveNumber(float radius) {
		return Mth.TWO_PI / wavelength(radius);
	}

	/** Angular frequency {@code omega = WAVE_SPEED * k} (radians per second). */
	public static float angularFrequency(float radius) {
		return WAVE_SPEED * waveNumber(radius);
	}

	/**
	 * Outward displacement (blocks) of one traveling damped impact wave at surface
	 * distance {@code d} (blocks) from the hit point, {@code ageSec} seconds after
	 * the hit: {@code A * cos(k*d - omega*age) * e^(-TIME_DECAY*age) * e^(-DISTANCE_DECAY*d)}
	 * with {@code A = BASE_AMPLITUDE * strength * (1 + WEAKNESS_AMPLITUDE_BOOST * (1 - healthFrac))},
	 * clamped to +-{@link #PER_IMPACT_CLAMP}. With in-contract strengths (&le; 1)
	 * A tops out at 0.3, so the clamp only ever binds on out-of-contract inputs.
	 */
	public static float impactDisplacement(float d, float ageSec, float strength, float healthFrac, float radius) {
		float k = waveNumber(radius);
		float amplitude = BASE_AMPLITUDE * strength * (1.0F + WEAKNESS_AMPLITUDE_BOOST * (1.0F - healthFrac));
		float disp = amplitude * Mth.cos(k * d - WAVE_SPEED * k * ageSec)
				* (float) Math.exp(-TIME_DECAY * ageSec) * (float) Math.exp(-DISTANCE_DECAY * d);
		return Mth.clamp(disp, -PER_IMPACT_CLAMP, PER_IMPACT_CLAMP);
	}

	/**
	 * Crest color weight in {@code [0, CREST_MAX * strength]} at surface distance
	 * {@code d} from the hit point: the squared positive part of the same traveling
	 * cosine, with the same time/distance decays, scaled by {@code strength}.
	 */
	public static float crestWeight(float d, float ageSec, float strength, float radius) {
		float k = waveNumber(radius);
		float crest = Math.max(Mth.cos(k * d - WAVE_SPEED * k * ageSec), 0.0F);
		return CREST_MAX * crest * crest
				* (float) Math.exp(-TIME_DECAY * ageSec) * (float) Math.exp(-DISTANCE_DECAY * d) * strength;
	}

	/**
	 * Whole-surface radial "breathing" displacement (blocks) of a BREAK pulse
	 * (directionless, applied uniformly along every vertex normal):
	 * {@code A * cos(omega * age) * e^(-TIME_DECAY * age)} — the {@code d}-terms of
	 * {@link #impactDisplacement} dropped, since the pulse is omnidirectional.
	 * Same per-impact clamp.
	 */
	public static float breakPulseDisplacement(float ageSec, float healthFrac, float radius) {
		float amplitude = BASE_AMPLITUDE * (1.0F + WEAKNESS_AMPLITUDE_BOOST * (1.0F - healthFrac));
		float disp = amplitude * Mth.cos(angularFrequency(radius) * ageSec) * (float) Math.exp(-TIME_DECAY * ageSec);
		return Mth.clamp(disp, -PER_IMPACT_CLAMP, PER_IMPACT_CLAMP);
	}

	/**
	 * Uniform crest weight of a BREAK pulse: {@link #crestWeight}'s time envelope
	 * without the distance decay, at strength 1.
	 */
	public static float breakCrestWeight(float ageSec, float radius) {
		float crest = Math.max(Mth.cos(angularFrequency(radius) * ageSec), 0.0F);
		return CREST_MAX * crest * crest * (float) Math.exp(-TIME_DECAY * ageSec);
	}

	/**
	 * Aperture lip displacement magnitude (blocks) at Euclidean distance {@code d}
	 * from the aperture point: a Gaussian ridge peaking at exactly {@code d = holeR}
	 * with height {@code LIP_MAX * holeR / HOLE_R_MAX}.
	 */
	public static float lipDisplacement(float d, float holeR) {
		float offset = d - holeR;
		return LIP_MAX * (holeR / HOLE_R_MAX) * (float) Math.exp(-(offset * offset) / LIP_SIGMA_SQ);
	}

	/**
	 * Rim-ring color weight at Euclidean distance {@code d} from the aperture
	 * point: the same Gaussian ridge as the lip, peaking at 0.5 for a fully-open
	 * aperture.
	 */
	public static float rimRingWeight(float d, float holeR) {
		float offset = d - holeR;
		return 0.5F * (float) Math.exp(-(offset * offset) / LIP_SIGMA_SQ) * (holeR / HOLE_R_MAX);
	}

	/**
	 * Per-vertex alpha factor of one aperture:
	 * {@code clamp((d - APERTURE_ALPHA_INNER_FRAC * holeR) / (APERTURE_FADE_RANGE * holeR / HOLE_R_MAX), 0, 1)}
	 * — fully transparent inside {@code 0.75 * holeR}, ramping linearly back to
	 * opaque over a fade range that scales with the hole. At the fully-open
	 * {@code holeR = HOLE_R_MAX} this is the legacy dissolve's 6.0-block linear
	 * ramp ({@code clamp(dist / DISSOLVE_RANGE, 0, 1)}) shifted outward by the
	 * 2.1-block fully-open core — the closest field to the old dissolve that
	 * still carries a genuinely open hole (documented parity deviation: the old
	 * ramp started at d = 0, this one at d = 2.1).
	 *
	 * <p>A collapsed aperture ({@code holeR} &le; 1e-4) is fully opaque (guards the
	 * 0/0 at {@code d = 0} while the hole animates shut).
	 */
	public static float apertureAlphaFactor(float d, float holeR) {
		if (holeR <= 1.0e-4F) {
			return 1.0F;
		}

		return Mth.clamp((d - APERTURE_ALPHA_INNER_FRAC * holeR) / (APERTURE_FADE_RANGE * holeR / HOLE_R_MAX), 0.0F, 1.0F);
	}

	/**
	 * One smoothing step of the animated aperture radius toward {@code target}:
	 * exponential approach {@code cur + (target - cur) * (1 - e^(-dt / tau))} with
	 * {@link #APERTURE_TAU_OPEN} while opening and the slower
	 * {@link #APERTURE_TAU_CLOSE} while closing. Monotone and never overshoots.
	 */
	public static float apertureRadiusStep(float cur, float target, float dtSec, boolean opening) {
		float tau = opening ? APERTURE_TAU_OPEN : APERTURE_TAU_CLOSE;
		return cur + (target - cur) * (1.0F - (float) Math.exp(-dtSec / tau));
	}

	/**
	 * Last-stand tremble offset (blocks) along the vertex normal for the vertex at
	 * unit-scale position {@code (px, py, pz)}:
	 * {@code TREMBLE_AMPLITUDE * sin(2 * PI * TREMBLE_HZ * t + phase(p_hat))} with the
	 * spatial phase {@code dot(p_hat, (12.9898, 78.233, 37.719))} de-synchronizing
	 * neighboring vertices. Bounded to +-{@link #TREMBLE_AMPLITUDE} by construction.
	 */
	public static float trembleOffset(float px, float py, float pz, float timeSec) {
		float length = (float) Math.sqrt(px * px + py * py + pz * pz);
		float phase = length > 1.0e-6F
				? (px * 12.9898F + py * 78.233F + pz * 37.719F) / length
				: 0.0F;
		return TREMBLE_AMPLITUDE * Mth.sin(Mth.TWO_PI * TREMBLE_HZ * timeSec + phase);
	}
}
