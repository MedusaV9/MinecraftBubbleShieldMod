package com.bubbleshield.client.fx;

import com.bubbleshield.client.ClientShieldManager;
import com.bubbleshield.client.ClientShieldManager.ClientShield;
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
 * {@code clamp(1 - wallDist / 8, 0, 0.35)} — silent 8+ blocks from the wall,
 * loudest pressed against it.
 *
 * <p>A static watcher (END_CLIENT_TICK, registered by {@link ImpactFxManager})
 * finds the nearest ACTIVE shield by spherical wall distance
 * (|dist(center) - r|, same approximation as {@code ContactFlash}) and
 * starts/stops/retunes a single {@link HumInstance}. Allocation-light: steady
 * state allocates nothing — a new instance is created only when the hum starts
 * or its sound event changes (VOID boundary crossings between shields).
 */
public final class ProximityHum {
	/** Wall distance (blocks) beyond which the hum is fully silent. */
	private static final double SILENCE_DISTANCE = 8.0;
	/** Peak hum volume, reached with the player right at the wall. */
	private static final float MAX_VOLUME = 0.35F;

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

			double wallDist = Math.abs(pos.distanceTo(Vec3.atCenterOf(shield.pos())) - shield.currentRadius());
			if (wallDist < nearestWallDist) {
				nearest = shield;
				nearestWallDist = wallDist;
			}
		}

		float volume = nearest == null ? 0.0F : (float) Math.clamp(1.0 - nearestWallDist / SILENCE_DISTANCE, 0.0, MAX_VOLUME);
		if (volume <= 0.0F) {
			stop();
			return;
		}

		SoundEvent desired = SurfaceSoundGroup.of(EffectRegistry.get(nearest.effectId()).surface()) == SurfaceSoundGroup.VOID
				? SoundEvents.PORTAL_AMBIENT
				: SoundEvents.BEACON_AMBIENT;
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
