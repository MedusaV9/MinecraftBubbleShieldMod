package com.bubbleshield.client.fx;

import com.bubbleshield.client.ClientShieldManager;
import com.bubbleshield.client.ClientShieldManager.ClientShield;
import com.bubbleshield.client.ShieldWallMath;
import com.bubbleshield.effect.EffectRegistry;
import com.bubbleshield.effect.SurfaceSoundGroup;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

/**
 * Looping proximity hum near a shield wall: {@code BEACON_AMBIENT} (or
 * {@code PORTAL_AMBIENT} when the nearest shield's surface family maps to
 * {@link SurfaceSoundGroup#VOID}) at volume
 * {@code base * clamp(1 - wallDist / 8, 0, 1)} — a REAL linear fade from the
 * base volume at the wall to silence 8 blocks out (the old
 * {@code clamp(fade, 0, 0.35)} form pinned the volume at its maximum through
 * the first 5.2 blocks). The base is {@value #MAX_VOLUME} normally and the
 * quieter {@value #VOID_MAX_VOLUME} for the VOID group, whose hum stacks on
 * the already-droning portal ambient.
 *
 * <p>A static watcher (END_CLIENT_TICK, registered by {@link ImpactFxManager})
 * finds the nearest ACTIVE shield by shape-aware wall distance
 * ({@link ShieldWallMath#wallDistance}, same helper as {@code ContactFlash})
 * and starts/stops/retunes a single {@link HumInstance}. Allocation-light:
 * steady state allocates nothing — a new instance is created only when the hum
 * starts or its sound event changes (VOID boundary crossings between shields).
 */
public final class ProximityHum {
	/** Wall distance (blocks) beyond which the hum is fully silent. */
	private static final double SILENCE_DISTANCE = 8.0;
	/** Peak hum volume, reached with the player right at the wall. */
	private static final float MAX_VOLUME = 0.35F;
	/** Peak volume for the VOID group (stacks with the portal ambient's own drone). */
	private static final float VOID_MAX_VOLUME = 0.25F;

	private static @Nullable HumInstance current;

	private ProximityHum() {
	}

	/** Per-tick watcher; registered on END_CLIENT_TICK by {@link ImpactFxManager}. */
	static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (mc.level == null || player == null) {
			stop();
			return;
		}

		Vec3 pos = player.position();
		ClientShield nearest = null;
		double nearestWallDist = Double.MAX_VALUE;
		for (ClientShield shield : ClientShieldManager.currentDimensionShields()) {
			if (!shield.active()) {
				continue;
			}

			double wallDist = ShieldWallMath.wallDistance(shield, pos);
			if (wallDist < nearestWallDist) {
				nearest = shield;
				nearestWallDist = wallDist;
			}
		}

		float fade = nearest == null ? 0.0F : (float) Math.clamp(1.0 - nearestWallDist / SILENCE_DISTANCE, 0.0, 1.0);
		if (fade <= 0.0F) {
			stop();
			return;
		}

		boolean voidGroup = SurfaceSoundGroup.of(EffectRegistry.get(nearest.effectId()).surface()) == SurfaceSoundGroup.VOID;
		SoundEvent desired = voidGroup ? SoundEvents.PORTAL_AMBIENT : SoundEvents.BEACON_AMBIENT;
		float volume = (voidGroup ? VOID_MAX_VOLUME : MAX_VOLUME) * fade;
		if (current == null || current.isStopped() || current.event != desired) {
			stop();
			current = new HumInstance(desired, volume, pos);
			mc.getSoundManager().play(current);
		} else {
			current.retune(volume, pos);
		}
	}

	/** Stops and forgets the hum (also called on disconnect / level change). */
	static void stop() {
		if (current != null) {
			current.requestStop();
			current = null;
		}
	}

	/**
	 * The looping hum itself. It carries the player's position (updated by the
	 * watcher, so vanilla's LINEAR attenuation hears it at full strength) and the
	 * watcher-computed volume; {@code tick()} only applies the latest retune.
	 */
	private static final class HumInstance extends AbstractTickableSoundInstance {
		final SoundEvent event;
		private float targetVolume;
		private boolean stopRequested;

		HumInstance(SoundEvent event, float volume, Vec3 pos) {
			super(event, SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
			this.event = event;
			this.looping = true;
			this.delay = 0;
			this.targetVolume = volume;
			this.volume = volume;
			this.x = pos.x;
			this.y = pos.y;
			this.z = pos.z;
		}

		void retune(float volume, Vec3 pos) {
			this.targetVolume = volume;
			this.x = pos.x;
			this.y = pos.y;
			this.z = pos.z;
		}

		void requestStop() {
			this.stopRequested = true;
		}

		@Override
		public void tick() {
			if (this.stopRequested) {
				this.stop();
				return;
			}

			this.volume = this.targetVolume;
		}
	}
}
