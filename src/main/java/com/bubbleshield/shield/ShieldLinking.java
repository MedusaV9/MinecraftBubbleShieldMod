package com.bubbleshield.shield;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.bubbleshield.block.BubbleShieldBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Pure resonance-link resolution: same-owner ACTIVE shields whose spheres overlap
 * form a link with the origin shield, splitting projectile damage evenly and
 * connecting their projectors with an END_ROD particle tether.
 *
 * <p>Linking is deliberately NOT transitive: only shields directly overlapping the
 * origin are returned. A chain A-B-C where only B overlaps both therefore splits
 * A's damage between A and B, never C.
 */
public final class ShieldLinking {
	private ShieldLinking() {
	}

	/**
	 * Resolves the shields resonance-linked to {@code origin} among {@code candidates}.
	 *
	 * <p>A candidate links when both shields are active, share the same non-null
	 * {@code ownerUuid}, and their spheres overlap:
	 * {@code distance(center1, center2) < currentRadius1 + currentRadius2}, with each
	 * center at {@link Vec3#atCenterOf} of the projector position. The radii are the
	 * shields' CURRENT (health-shrunk, mode-capped) radii, so linking follows the live
	 * bubbles. Shape is intentionally ignored: a {@link ShieldShape#DOME} links by its
	 * full sphere radius even though its open underside renders no surface there, so
	 * two domes whose sphere volumes overlap below the center plane still resonate.
	 *
	 * @param origin the shield whose link set is resolved; always part of the result.
	 * @param candidates the shields to consider (typically the level's loaded shields);
	 * the origin itself and duplicates are tolerated and deduplicated by identity.
	 * @return the linked shields including {@code origin}, deterministically ordered
	 * by {@link BlockPos#asLong()} of their positions.
	 */
	public static List<BubbleShieldBlockEntity> findLinked(BubbleShieldBlockEntity origin, Collection<BubbleShieldBlockEntity> candidates) {
		List<BubbleShieldBlockEntity> linked = new ArrayList<>();
		linked.add(origin);

		UUID owner = origin.getShieldState().ownerUuid;
		if (origin.getShieldState().active && owner != null) {
			Vec3 originCenter = Vec3.atCenterOf(origin.getBlockPos());
			double originRadius = origin.currentRadius();
			for (BubbleShieldBlockEntity candidate : candidates) {
				if (candidate == origin) {
					continue;
				}

				if (!candidate.getShieldState().active || !owner.equals(candidate.getShieldState().ownerUuid)) {
					continue;
				}

				Vec3 candidateCenter = Vec3.atCenterOf(candidate.getBlockPos());
				if (originCenter.distanceTo(candidateCenter) < originRadius + candidate.currentRadius()) {
					linked.add(candidate);
				}
			}
		}

		linked.sort(Comparator.comparingLong(shield -> shield.getBlockPos().asLong()));
		return linked;
	}
}
