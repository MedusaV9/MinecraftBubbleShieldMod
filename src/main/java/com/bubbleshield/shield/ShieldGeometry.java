package com.bubbleshield.shield;

import net.minecraft.world.phys.Vec3;

/**
 * Pure shape-aware containment math shared by the barrier, the expel pass and
 * projectile interception, so every server-side check agrees on what "inside the
 * shield" means for each {@link ShieldShape}.
 */
public final class ShieldGeometry {
	private ShieldGeometry() {
	}

	/**
	 * @return true when {@code pos} lies inside the shield of the given shape.
	 * A {@link ShieldShape#DOME} only contains points at or above the center's Y plane.
	 */
	public static boolean isInside(ShieldShape shape, Vec3 center, double radius, Vec3 pos) {
		if (pos.distanceTo(center) > radius) {
			return false;
		}

		return shape != ShieldShape.DOME || pos.y >= center.y;
	}

	/**
	 * @return true when a movement from {@code prev} to {@code cur} crossed the shield
	 * boundary inward (outside before, inside now). Outward or fully-inside movement
	 * never triggers, which is what keeps deflected projectiles from being re-intercepted.
	 */
	public static boolean crossedInto(ShieldShape shape, Vec3 center, double radius, Vec3 prev, Vec3 cur) {
		return !isInside(shape, center, radius, prev) && isInside(shape, center, radius, cur);
	}
}
