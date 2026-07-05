package com.bubbleshield.effect;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

/**
 * Server-side ambient behaviour running inside an active bubble shield
 * (particles, auras, sounds). Implementations must only use server-safe APIs
 * and should throttle themselves (act only when {@code gameTime % 10 == 0}).
 */
public interface InsideEffectBehavior {
	/** Registry of all known behaviours by id. Populated during mod init. */
	Map<String, InsideEffectBehavior> REGISTRY = new HashMap<>();

	/**
	 * Runs one server tick of this behaviour for an active shield.
	 *
	 * @param level    the server level containing the shield
	 * @param center   the shield center (block center of the projector)
	 * @param radius   the current shield radius
	 * @param def      the effect definition that selected this behaviour (colors, params)
	 * @param gameTime the level game time, for throttling and animation phase
	 */
	void tick(ServerLevel level, Vec3 center, float radius, EffectDefinition def, long gameTime);

	static void register(String id, InsideEffectBehavior behavior) {
		if (REGISTRY.putIfAbsent(id, behavior) != null) {
			throw new IllegalStateException("Duplicate inside effect behavior id: " + id);
		}
	}

	static @Nullable InsideEffectBehavior get(String id) {
		return REGISTRY.get(id);
	}
}
