package com.bubbleshield.client.render;

import java.util.List;

import com.bubbleshield.net.ShieldPayloads;

import net.minecraft.world.phys.Vec3;

/**
 * Immutable per-shield, per-frame surface-dynamics snapshot consumed by
 * {@link SphereMesh}'s emitters (WP-Dyn): the recent impacts driving traveling
 * waves, the whitelisted-player apertures (physical wall openings replacing the
 * old flat dissolve), the interpolated time for the last-stand tremble and the
 * synced health fraction scaling wave amplitude. Built once per shield per
 * frame by {@code ShieldRenderer.collectSubmits}; {@link #NONE} is the idle
 * fast path (mesh output byte-identical to the pre-WP-Dyn emitters).
 */
public record DeformState(List<Impact> impacts, List<Aperture> apertures, float timeSec, float radius, float healthFrac) {
	/** The idle state: no impacts, no apertures, no tremble (healthFrac 1). */
	public static final DeformState NONE = new DeformState(List.of(), List.of(), 0.0F, 1.0F, 1.0F);

	/** The tremble kicks in below this health fraction (last stand). */
	public static final float TREMBLE_HEALTH_FRAC = 0.25F;

	/**
	 * One wave source: {@code dirUnit} is the outward unit direction from the
	 * shield center to the hit point ({@link Vec3#ZERO} for the omnidirectional
	 * BREAK pulse), {@code strength01} the per-kind EFFECTIVE strength in [0, 1]
	 * (already scaled by the consumer's kind table), {@code ageSec} the
	 * partial-tick-interpolated age and {@code kind} one of the
	 * {@link ShieldPayloads.ImpactEntry} {@code KIND_*} constants (HEAL grades
	 * toward the secondary color, BREAK pulses the whole surface).
	 */
	public record Impact(Vec3 dirUnit, float strength01, float ageSec, int kind) {
	}

	/**
	 * One wall opening: {@code relPos} is the passing player's position relative
	 * to the shield center (world units), {@code holeR} the ANIMATED hole radius
	 * from {@code ApertureTracker} (0..{@code SurfaceWaveMath.HOLE_R_MAX}).
	 */
	public record Aperture(Vec3 relPos, float holeR) {
	}

	/** True when the mesh can take the idle fast path (no per-vertex dynamics at all). */
	public boolean isIdle() {
		return this.impacts.isEmpty() && this.apertures.isEmpty() && this.healthFrac >= TREMBLE_HEALTH_FRAC;
	}
}
