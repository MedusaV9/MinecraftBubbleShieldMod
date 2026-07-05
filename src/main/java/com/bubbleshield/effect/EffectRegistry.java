package com.bubbleshield.effect;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The fixed catalogue of the 50 selectable shield effects: a grid of 10 color/behaviour
 * themes x the 5 {@link SurfaceTemplate}s ({@code id = theme * 5 + surface}).
 */
public final class EffectRegistry {
	public static final int COUNT = 50;

	private static final SurfaceTemplate[] SURFACES = {
			SurfaceTemplate.PLASMA,
			SurfaceTemplate.HEX,
			SurfaceTemplate.WAVES,
			SurfaceTemplate.AURORA,
			SurfaceTemplate.SPARKLE
	};

	/** One row per theme: hand-picked ARGB pair + the inside behaviour it drives. */
	private static final Theme[] THEMES = {
			new Theme(0xFF66FFAA, 0xFF1E9E6E, "particle_dome"), // aurora greens
			new Theme(0xFFFF33CC, 0xFF7A00FF, "particle_spiral"), // plasma magenta
			new Theme(0xFF2E7BFF, 0xFF00CFEA, "regen_aura"), // ocean blue
			new Theme(0xFFFF7A1A, 0xFFFFC23D, "ember_rain"), // ember orange
			new Theme(0xFF8A2BE2, 0xFF3B0A66, "slow_hostiles"), // void purple
			new Theme(0xFFFFD24D, 0xFFB8860B, "speed_aura"), // gold
			new Theme(0xFF7FE9FF, 0xFF2FA8C9, "snowfall"), // cyan ice
			new Theme(0xFFC80F1E, 0xFF5A0710, "heartbeat_pulse"), // blood red
			new Theme(0xFF2ECC71, 0xFF0E7A3C, "firefly_swarm"), // emerald
			new Theme(0xFFF2F2F2, 0xFF3C3C3C, "mist_layer") // monochrome
	};

	public static final List<EffectDefinition> ALL = buildAll();

	private EffectRegistry() {
	}

	private static List<EffectDefinition> buildAll() {
		List<EffectDefinition> all = new ArrayList<>(COUNT);
		for (int theme = 0; theme < THEMES.length; theme++) {
			for (int surface = 0; surface < SURFACES.length; surface++) {
				int id = theme * SURFACES.length + surface;
				// paramA: renderer animation speed per theme; paramB: renderer intensity per surface.
				float paramA = 0.4F + 0.08F * theme;
				float paramB = 0.6F + 0.1F * surface;
				all.add(EffectDefinition.of(id, THEMES[theme].argbPrimary, THEMES[theme].argbSecondary, SURFACES[surface], paramA, paramB, THEMES[theme].behaviorId));
			}
		}

		return List.copyOf(all);
	}

	/** Returns the definition for the given id, clamped into [0, {@link #COUNT} - 1]. */
	public static EffectDefinition get(int id) {
		return ALL.get(Math.clamp(id, 0, ALL.size() - 1));
	}

	/**
	 * Fails fast when the catalogue is malformed: exactly 50 entries, ids 0..49 unique
	 * and every inside behaviour id registered in {@link InsideEffectBehavior#REGISTRY}.
	 */
	public static void validate() {
		if (ALL.size() != COUNT) {
			throw new IllegalStateException("Expected " + COUNT + " effect definitions, found " + ALL.size());
		}

		Set<Integer> seenIds = new HashSet<>();
		for (int i = 0; i < ALL.size(); i++) {
			EffectDefinition def = ALL.get(i);
			if (def.id() != i) {
				throw new IllegalStateException("Effect at index " + i + " has id " + def.id());
			}

			if (!seenIds.add(def.id())) {
				throw new IllegalStateException("Duplicate effect id: " + def.id());
			}

			if (def.id() < 0 || def.id() >= COUNT) {
				throw new IllegalStateException("Effect id out of range [0, " + (COUNT - 1) + "]: " + def.id());
			}

			if (!InsideEffectBehavior.REGISTRY.containsKey(def.insideBehaviorId())) {
				throw new IllegalStateException("Effect " + def.id() + " references unregistered inside behavior: " + def.insideBehaviorId());
			}
		}
	}

	private record Theme(int argbPrimary, int argbSecondary, String behaviorId) {
	}
}
