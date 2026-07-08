package com.bubbleshield.effect;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * The fixed catalogue of the 105 selectable shield effects (ids 0..104), organized as
 * 21 color families x 5 effects, but individually authored: every id is a unique row
 * in the flat table below (palette pair, surface, behavior@variant, guard style,
 * context profile, ambient sound and screen-fx template all vary per id).
 *
 * <p>Uniqueness is machine-enforced by {@link #validate()} and the gametests.
 */
public final class EffectRegistry {
	public static final int COUNT = 105;

	/**
	 * Modulus/denominator of the per-id paramB/behaviorStrength derivations in
	 * {@link #row}. FROZEN at the V1 catalogue size (75): retuning it to {@link #COUNT}
	 * would silently change the derived params (and the generated post-effect JSONs) of
	 * ids 0..74, which must stay stable across catalogue expansions. Mirrored by
	 * PARAM_CYCLE in tools/gen_post_effects.py -- keep both in lockstep.
	 */
	public static final int PARAM_CYCLE = 75;

	/** The full screen-fx template catalogue; every row's screenTemplate must be one of these. */
	public static final Set<String> SCREEN_TEMPLATES = Set.of(
			"tint", "wobble", "vignette", "chroma", "pixelate", "desat",
			"bloomglow", "ripple", "scanlines", "edgeglow", "frostlens", "heathaze",
			"posterize", "radialblur", "glitch", "duotone");

	public static final List<EffectDefinition> ALL = buildAll();

	private EffectRegistry() {
	}

	private static List<EffectDefinition> buildAll() {
		List<EffectDefinition> all = new ArrayList<>(COUNT);
		// F0 "Aurora Borealis" (greens)
		all.add(row(0, 0x66FFAA, 0x1E9E6E, "aurora", "particle_dome", 0, GuardStyle.NONE, ContextProfile.NIGHT_BLOOM, "block.beacon.ambient", 1.0F, 160, "tint"));
		all.add(row(1, 0x8CFFC1, 0x0FBF8F, "waves", "enchant_stream", 0, GuardStyle.GLOW, ContextProfile.NONE, "block.amethyst_block.chime", 0.8F, 120, "bloomglow"));
		all.add(row(2, 0x3DF5B0, 0x136B4F, "interference", "night_glow_aura", 0, GuardStyle.NONE, ContextProfile.CROWD_SCALE, "ambient.underwater.loop", 1.2F, 200, "edgeglow"));
		all.add(row(3, 0xA1FFD9, 0x2FBF71, "starfield", "falling_petals", 0, GuardStyle.SLOW, ContextProfile.NONE, "block.vault.ambient", 1.1F, 140, "ripple"));
		all.add(row(4, 0x57E68C, 0x0E5C38, "sparkle", "music_pulse", 0, GuardStyle.NONE, ContextProfile.HEALTH_HUE, "block.note_block.harp", 1.0F, 100, "desat"));
		// F1 "Plasma Nexus" (magentas)
		all.add(row(5, 0xFF33CC, 0x7A00FF, "plasma", "particle_spiral", 0, GuardStyle.STING, ContextProfile.LOW_HEALTH_FRENZY, "block.portal.ambient", 1.3F, 160, "wobble"));
		all.add(row(6, 0xFF66E0, 0x5500BB, "arcs", "static_field", 0, GuardStyle.NONE, ContextProfile.STORM_CHARGED, "entity.breeze.idle_ground", 1.4F, 90, "chroma"));
		all.add(row(7, 0xE01FA9, 0x3D0066, "vortex", "meteor_burst", 0, GuardStyle.GUST, ContextProfile.NONE, "block.trial_spawner.ambient", 0.9F, 130, "heathaze"));
		all.add(row(8, 0xFF4DD6, 0x8A1FB8, "voronoi", "purge_pulse", 0, GuardStyle.DARK, ContextProfile.NONE, "entity.warden.heartbeat", 1.2F, 120, "scanlines"));
		all.add(row(9, 0xFF00B3, 0xBF00FF, "interference", "haste_aura", 0, GuardStyle.NONE, ContextProfile.CROWD_SCALE, "block.sculk_sensor.clicking", 1.4F, 80, "bloomglow"));
		// F2 "Deep Ocean" (blues)
		all.add(row(10, 0x2E7BFF, 0x00CFEA, "waves", "bubble_veil", 0, GuardStyle.NONE, ContextProfile.NONE, "ambient.underwater.loop", 1.0F, 180, "ripple"));
		all.add(row(11, 0x1F5FD6, 0x00A3C4, "voronoi", "regen_aura", 0, GuardStyle.SLOW, ContextProfile.NONE, "block.conduit.ambient", 1.0F, 140, "tint"));
		all.add(row(12, 0x66B8FF, 0x0E4FA3, "rings", "resist_aura", 0, GuardStyle.NONE, ContextProfile.STORM_CHARGED, "block.beacon.ambient", 0.8F, 170, "frostlens"));
		all.add(row(13, 0x3E8FE0, 0x123C78, "plasma", "mist_layer", 0, GuardStyle.BLIND, ContextProfile.NIGHT_BLOOM, "ambient.cave", 0.9F, 220, "wobble"));
		all.add(row(14, 0x8FD4FF, 0x1F73B8, "interference", "snowfall", 0, GuardStyle.NONE, ContextProfile.HEALTH_HUE, "block.amethyst_block.chime", 1.2F, 110, "vignette"));
		// F3 "Ember Forge" (oranges)
		all.add(row(15, 0xFF7A1A, 0xFFC23D, "plasma", "ember_rain", 0, GuardStyle.STING, ContextProfile.NONE, "block.respawn_anchor.ambient", 1.1F, 150, "heathaze"));
		all.add(row(16, 0xE85D04, 0x9D2B06, "arcs", "fire_ward", 0, GuardStyle.NONE, ContextProfile.NONE, "entity.breeze.idle_ground", 0.7F, 130, "vignette"));
		all.add(row(17, 0xFFA347, 0xB33F00, "scales", "meteor_burst", 1, GuardStyle.GUST, ContextProfile.LOW_HEALTH_FRENZY, "block.trial_spawner.ambient", 1.2F, 100, "bloomglow"));
		all.add(row(18, 0xFF8C29, 0xFFD97A, "rings", "speed_aura", 0, GuardStyle.NONE, ContextProfile.CROWD_SCALE, "block.note_block.bell", 1.3F, 90, "tint"));
		all.add(row(19, 0xD9480F, 0x5A0F05, "sparkle", "heartbeat_pulse", 0, GuardStyle.DARK, ContextProfile.NONE, "entity.warden.heartbeat", 0.9F, 120, "chroma"));
		// F4 "Void Whisper" (purples)
		all.add(row(20, 0x8A2BE2, 0x3B0A66, "starfield", "rising_souls", 0, GuardStyle.NONE, ContextProfile.NIGHT_BLOOM, "particle.soul_escape", 0.8F, 140, "chroma"));
		all.add(row(21, 0x6A0DAD, 0x2E0854, "vortex", "slow_hostiles", 0, GuardStyle.SLOW, ContextProfile.NONE, "block.portal.ambient", 0.7F, 190, "scanlines"));
		all.add(row(22, 0x9D4EDD, 0x4A148C, "hex", "enchant_stream", 1, GuardStyle.NONE, ContextProfile.HEALTH_HUE, "block.vault.ambient", 0.8F, 160, "desat"));
		all.add(row(23, 0xB388FF, 0x1A0033, "voronoi", "orbiting_shards", 0, GuardStyle.BLIND, ContextProfile.NONE, "ambient.cave", 0.6F, 240, "vignette"));
		all.add(row(24, 0x7C43BD, 0x26094D, "arcs", "frost_intruders", 0, GuardStyle.GLOW, ContextProfile.STORM_CHARGED, "block.sculk_sensor.clicking", 0.8F, 130, "edgeglow"));
		// F5 "Solar Crown" (golds)
		all.add(row(25, 0xFFD24D, 0xB8860B, "rings", "haste_aura", 1, GuardStyle.NONE, ContextProfile.NONE, "block.note_block.bell", 1.0F, 110, "bloomglow"));
		all.add(row(26, 0xFFE066, 0xCC9A06, "sparkle", "speed_aura", 1, GuardStyle.GUST, ContextProfile.CROWD_SCALE, "block.amethyst_block.chime", 1.4F, 95, "tint"));
		all.add(row(27, 0xF5C518, 0x8A6D00, "scales", "firefly_swarm", 0, GuardStyle.NONE, ContextProfile.NIGHT_BLOOM, "block.beacon.ambient", 1.2F, 150, "heathaze"));
		all.add(row(28, 0xFFDF80, 0xA67C00, "hex", "music_pulse", 1, GuardStyle.STING, ContextProfile.NONE, "block.note_block.chime", 1.1F, 100, "edgeglow"));
		all.add(row(29, 0xEAB530, 0x6B4E00, "plasma", "regen_aura", 1, GuardStyle.NONE, ContextProfile.LOW_HEALTH_FRENZY, "block.conduit.ambient", 1.3F, 140, "pixelate"));
		// F6 "Glacial Palace" (ice cyans)
		all.add(row(30, 0x7FE9FF, 0x2FA8C9, "hex", "snowfall", 1, GuardStyle.NONE, ContextProfile.STORM_CHARGED, "ambient.underwater.loop", 1.3F, 160, "frostlens"));
		all.add(row(31, 0xB3F0FF, 0x1B7A99, "voronoi", "frost_intruders", 1, GuardStyle.SLOW, ContextProfile.NONE, "block.amethyst_block.chime", 0.6F, 130, "desat"));
		all.add(row(32, 0xA0E8F0, 0x33808C, "interference", "mist_layer", 1, GuardStyle.NONE, ContextProfile.NONE, "ambient.cave", 1.1F, 210, "ripple"));
		all.add(row(33, 0x66D9E8, 0x0B525B, "sparkle", "bubble_veil", 1, GuardStyle.DARK, ContextProfile.HEALTH_HUE, "block.conduit.ambient", 0.7F, 170, "pixelate"));
		all.add(row(34, 0xD0FBFF, 0x4FB3C9, "waves", "resist_aura", 1, GuardStyle.NONE, ContextProfile.NONE, "block.beacon.ambient", 0.6F, 180, "tint"));
		// F7 "Crimson Rite" (reds)
		all.add(row(35, 0xC80F1E, 0x5A0710, "scales", "heartbeat_pulse", 1, GuardStyle.STING, ContextProfile.LOW_HEALTH_FRENZY, "entity.warden.heartbeat", 1.1F, 110, "vignette"));
		all.add(row(36, 0xE5383B, 0x7F0A0F, "plasma", "ember_rain", 1, GuardStyle.NONE, ContextProfile.NONE, "block.respawn_anchor.ambient", 0.9F, 140, "heathaze"));
		all.add(row(37, 0xFF5A5F, 0x8C1C13, "arcs", "purge_pulse", 1, GuardStyle.GUST, ContextProfile.NONE, "block.trial_spawner.ambient", 0.7F, 120, "chroma"));
		all.add(row(38, 0xA4161A, 0x3D0000, "hex", "slow_hostiles", 1, GuardStyle.BLIND, ContextProfile.NIGHT_BLOOM, "block.sculk_sensor.clicking", 0.7F, 150, "scanlines"));
		all.add(row(39, 0xF25C54, 0x661411, "vortex", "fire_ward", 1, GuardStyle.NONE, ContextProfile.HEALTH_HUE, "particle.soul_escape", 1.0F, 160, "ripple"));
		// F8 "Verdant Grove" (emerald/lime)
		all.add(row(40, 0x2ECC71, 0x0E7A3C, "voronoi", "falling_petals", 1, GuardStyle.NONE, ContextProfile.NONE, "block.note_block.harp", 1.2F, 130, "tint"));
		all.add(row(41, 0x7AE582, 0x1E6B2F, "waves", "firefly_swarm", 1, GuardStyle.NONE, ContextProfile.NIGHT_BLOOM, "ambient.cave", 1.3F, 190, "ripple"));
		all.add(row(42, 0x58D68D, 0x145A32, "sparkle", "regen_aura", 2, GuardStyle.SLOW, ContextProfile.CROWD_SCALE, "block.conduit.ambient", 1.1F, 150, "bloomglow"));
		all.add(row(43, 0x9CCC65, 0x33691E, "scales", "spore_drift", 0, GuardStyle.NONE, ContextProfile.NONE, "block.vault.ambient", 1.3F, 170, "frostlens"));
		all.add(row(44, 0x66BB6A, 0x1B5E20, "aurora", "night_glow_aura", 1, GuardStyle.GLOW, ContextProfile.NONE, "block.beacon.ambient", 1.4F, 200, "wobble"));
		// F9 "Monochrome Static" (greys)
		all.add(row(45, 0xF2F2F2, 0x3C3C3C, "interference", "mist_layer", 2, GuardStyle.NONE, ContextProfile.NONE, "ambient.cave", 0.8F, 230, "desat"));
		all.add(row(46, 0xE0E0E0, 0x616161, "hex", "static_field", 1, GuardStyle.DARK, ContextProfile.STORM_CHARGED, "block.sculk_sensor.clicking", 1.0F, 100, "pixelate"));
		all.add(row(47, 0xBDBDBD, 0x212121, "rings", "particle_dome", 1, GuardStyle.NONE, ContextProfile.CROWD_SCALE, "block.beacon.ambient", 0.7F, 190, "scanlines"));
		all.add(row(48, 0x9E9E9E, 0x424242, "starfield", "slow_hostiles", 2, GuardStyle.SLOW, ContextProfile.NONE, "entity.breeze.idle_ground", 1.0F, 140, "edgeglow"));
		all.add(row(49, 0xEEEEEE, 0x757575, "arcs", "music_pulse", 2, GuardStyle.NONE, ContextProfile.HEALTH_HUE, "block.note_block.chime", 0.8F, 120, "vignette"));
		// F10 "Celestial Vault" (indigo/silver)
		all.add(row(50, 0x7986CB, 0xE8EAF6, "starfield", "orbiting_shards", 1, GuardStyle.NONE, ContextProfile.NIGHT_BLOOM, "block.vault.ambient", 0.9F, 150, "edgeglow"));
		all.add(row(51, 0x5C6BC0, 0xC5CAE9, "aurora", "enchant_stream", 2, GuardStyle.GLOW, ContextProfile.NONE, "block.amethyst_block.chime", 1.0F, 140, "bloomglow"));
		all.add(row(52, 0x3F51B5, 0x9FA8DA, "rings", "particle_spiral", 1, GuardStyle.GUST, ContextProfile.NONE, "block.portal.ambient", 1.1F, 170, "pixelate"));
		all.add(row(53, 0x283593, 0xB0BEC5, "vortex", "firefly_swarm", 2, GuardStyle.NONE, ContextProfile.CROWD_SCALE, "ambient.underwater.loop", 0.7F, 210, "desat"));
		all.add(row(54, 0x9FA8DA, 0x1A237E, "sparkle", "night_glow_aura", 2, GuardStyle.NONE, ContextProfile.NONE, "particle.soul_escape", 1.2F, 180, "wobble"));
		// F11 "Tempest Cell" (storm blue/electric)
		all.add(row(55, 0x4FC3F7, 0x01579B, "arcs", "static_field", 2, GuardStyle.DARK, ContextProfile.STORM_CHARGED, "entity.breeze.idle_ground", 1.2F, 80, "scanlines"));
		all.add(row(56, 0x29B6F6, 0x0D47A1, "plasma", "meteor_burst", 2, GuardStyle.STING, ContextProfile.LOW_HEALTH_FRENZY, "block.trial_spawner.ambient", 1.4F, 110, "chroma"));
		all.add(row(57, 0x81D4FA, 0x0277BD, "waves", "purge_pulse", 2, GuardStyle.GUST, ContextProfile.NONE, "entity.warden.heartbeat", 1.4F, 130, "wobble"));
		all.add(row(58, 0x00B0FF, 0x002F6C, "vortex", "orbiting_shards", 2, GuardStyle.NONE, ContextProfile.CROWD_SCALE, "block.sculk_sensor.clicking", 1.2F, 90, "heathaze"));
		all.add(row(59, 0x40C4FF, 0x01426A, "starfield", "speed_aura", 2, GuardStyle.NONE, ContextProfile.NONE, "block.beacon.ambient", 1.1F, 160, "frostlens"));
		// F12 "Sakura Dream" (pink/white)
		all.add(row(60, 0xF8BBD0, 0xAD1457, "aurora", "falling_petals", 2, GuardStyle.NONE, ContextProfile.NONE, "block.note_block.harp", 1.4F, 120, "ripple"));
		all.add(row(61, 0xF48FB1, 0x880E4F, "waves", "bubble_veil", 2, GuardStyle.NONE, ContextProfile.CROWD_SCALE, "ambient.underwater.loop", 1.4F, 170, "tint"));
		all.add(row(62, 0xFF80AB, 0xC2185B, "hex", "particle_spiral", 2, GuardStyle.SLOW, ContextProfile.NONE, "block.amethyst_block.chime", 1.3F, 100, "pixelate"));
		all.add(row(63, 0xFFC1E3, 0xD81B60, "rings", "haste_aura", 2, GuardStyle.NONE, ContextProfile.HEALTH_HUE, "block.note_block.bell", 1.4F, 85, "wobble"));
		all.add(row(64, 0xEC407A, 0x4A0025, "voronoi", "heartbeat_pulse", 2, GuardStyle.GLOW, ContextProfile.LOW_HEALTH_FRENZY, "entity.warden.heartbeat", 1.3F, 115, "bloomglow"));
		// F13 "Sculk Depths" (dark teals)
		all.add(row(65, 0x1DE9B6, 0x0B3D33, "vortex", "rising_souls", 1, GuardStyle.NONE, ContextProfile.NIGHT_BLOOM, "block.sculk_sensor.clicking", 0.6F, 140, "desat"));
		all.add(row(66, 0x00BFA5, 0x00332B, "starfield", "frost_intruders", 2, GuardStyle.SLOW, ContextProfile.NONE, "ambient.cave", 0.7F, 250, "edgeglow"));
		all.add(row(67, 0x26A69A, 0x00201C, "aurora", "particle_dome", 2, GuardStyle.DARK, ContextProfile.NONE, "entity.warden.heartbeat", 0.6F, 150, "scanlines"));
		all.add(row(68, 0x4DB6AC, 0x00251F, "interference", "resist_aura", 2, GuardStyle.NONE, ContextProfile.HEALTH_HUE, "block.vault.ambient", 0.6F, 180, "vignette"));
		all.add(row(69, 0x80CBC4, 0x0E2E29, "scales", "spore_drift", 1, GuardStyle.BLIND, ContextProfile.NONE, "particle.soul_escape", 0.7F, 200, "chroma"));
		// F14 "Twin Nether" (crimson/warped duotones)
		all.add(row(70, 0xDD2C00, 0x00C853, "scales", "spore_drift", 2, GuardStyle.NONE, ContextProfile.NONE, "block.respawn_anchor.ambient", 0.7F, 160, "heathaze"));
		all.add(row(71, 0xFF3D00, 0x1B5E20, "arcs", "fire_ward", 2, GuardStyle.STING, ContextProfile.NONE, "block.portal.ambient", 0.9F, 150, "vignette"));
		all.add(row(72, 0x00E5FF, 0xBF360C, "aurora", "ember_rain", 2, GuardStyle.NONE, ContextProfile.NIGHT_BLOOM, "particle.soul_escape", 0.9F, 130, "frostlens"));
		all.add(row(73, 0x76FF03, 0x8D1007, "vortex", "rising_souls", 2, GuardStyle.DARK, ContextProfile.LOW_HEALTH_FRENZY, "ambient.cave", 0.6F, 220, "pixelate"));
		all.add(row(74, 0xCFD8DC, 0x4E342E, "rings", "snowfall", 2, GuardStyle.GUST, ContextProfile.STORM_CHARGED, "entity.breeze.idle_ground", 0.8F, 170, "ripple"));
		// F15 "Copper Patina" (teal x copper)
		all.add(row(75, 0x2FBFA3, 0xB35A2D, "kaleido", "leap_aura", 0, GuardStyle.NONE, ContextProfile.NONE, "block.copper_bulb.turn_on", 1.0F, 150, "posterize"));
		all.add(row(76, 0x53D9C0, 0x8C4A21, "circuit", "tide_aura", 0, GuardStyle.GUST, ContextProfile.STORM_CHARGED, "block.bubble_column.upwards_ambient", 1.1F, 140, "duotone"));
		all.add(row(77, 0x1FA08A, 0xD97C4A, "voronoi", "ember_guard", 0, GuardStyle.STING, ContextProfile.NONE, "block.respawn_anchor.ambient", 1.3F, 170, "heathaze"));
		all.add(row(78, 0x7FE0CF, 0x6B3517, "scales", "lucky_charm", 0, GuardStyle.NONE, ContextProfile.CROWD_SCALE, "block.amethyst_block.resonate", 1.0F, 130, "tint"));
		all.add(row(79, 0x0F8C77, 0xE09A66, "rings", "echo_pulse", 0, GuardStyle.GLOW, ContextProfile.NIGHT_BLOOM, "block.sculk_sensor.clicking", 0.9F, 160, "edgeglow"));
		// F16 "Amber Twilight" (amber x violet)
		all.add(row(80, 0xFFB733, 0x6A2C91, "petals", "prismatic_rays", 0, GuardStyle.NONE, ContextProfile.NIGHT_BLOOM, "block.amethyst_block.chime", 0.9F, 145, "duotone"));
		all.add(row(81, 0xE6960F, 0x8F5BC2, "kaleido", "void_tendrils", 0, GuardStyle.DARK, ContextProfile.NONE, "ambient.cave", 1.0F, 200, "glitch"));
		all.add(row(82, 0xFFCC66, 0x4A1A70, "aurora", "honey_drip", 0, GuardStyle.NONE, ContextProfile.HEALTH_HUE, "block.honey_block.slide", 0.8F, 150, "vignette"));
		all.add(row(83, 0xCC8419, 0xB08AE0, "starfield", "wax_glow", 0, GuardStyle.SLOW, ContextProfile.NONE, "block.copper_bulb.turn_on", 0.7F, 190, "bloomglow"));
		all.add(row(84, 0xF2A63B, 0x33104D, "lightning", "storm_cage", 0, GuardStyle.NONE, ContextProfile.STORM_CHARGED, "entity.breeze.idle_ground", 0.9F, 100, "scanlines"));
		// F17 "Toxic Bloom" (acid-green x charcoal)
		all.add(row(85, 0xA6E22E, 0x2B2B2B, "circuit", "leap_aura", 1, GuardStyle.SLOW, ContextProfile.CROWD_SCALE, "entity.slime.squish", 0.8F, 120, "glitch"));
		all.add(row(86, 0xC3F73A, 0x1A1F16, "voronoi", "tide_aura", 1, GuardStyle.NONE, ContextProfile.NONE, "block.sponge.absorb", 0.7F, 180, "posterize"));
		all.add(row(87, 0x86B300, 0x3D3D3D, "lightning", "ember_guard", 1, GuardStyle.STING, ContextProfile.LOW_HEALTH_FRENZY, "block.respawn_anchor.ambient", 1.4F, 110, "chroma"));
		all.add(row(88, 0xD6FF66, 0x22261C, "hex", "lucky_charm", 1, GuardStyle.BLIND, ContextProfile.NONE, "block.vault.ambient", 1.3F, 155, "desat"));
		all.add(row(89, 0x9ACD32, 0x101410, "petals", "echo_pulse", 1, GuardStyle.NONE, ContextProfile.NIGHT_BLOOM, "block.sculk_catalyst.bloom", 0.9F, 175, "edgeglow"));
		// F18 "Royal Abyss" (navy x gold)
		all.add(row(90, 0x16296B, 0xF0C24A, "kaleido", "prismatic_rays", 1, GuardStyle.GLOW, ContextProfile.NONE, "block.beacon.power_select", 0.8F, 165, "bloomglow"));
		all.add(row(91, 0x0A1A4D, 0xD4AF37, "lightning", "void_tendrils", 1, GuardStyle.DARK, ContextProfile.NIGHT_BLOOM, "block.portal.ambient", 0.6F, 210, "duotone"));
		all.add(row(92, 0x24408F, 0xFFDD80, "waves", "honey_drip", 1, GuardStyle.NONE, ContextProfile.CROWD_SCALE, "block.honey_block.slide", 1.1F, 135, "radialblur"));
		all.add(row(93, 0x33509E, 0xB8912F, "sparkle", "wax_glow", 1, GuardStyle.NONE, ContextProfile.NONE, "block.copper_bulb.turn_on", 1.2F, 145, "vignette"));
		all.add(row(94, 0x0D2140, 0xE6C35C, "arcs", "storm_cage", 1, GuardStyle.GUST, ContextProfile.STORM_CHARGED, "entity.breeze.idle_ground", 1.3F, 105, "posterize"));
		// F19 "Coral Reef" (coral x turquoise)
		all.add(row(95, 0xFF6F61, 0x20B2AA, "petals", "leap_aura", 2, GuardStyle.NONE, ContextProfile.HEALTH_HUE, "entity.axolotl.swim", 1.0F, 125, "radialblur"));
		all.add(row(96, 0xFF8A75, 0x0E8074, "waves", "tide_aura", 2, GuardStyle.NONE, ContextProfile.NONE, "block.bubble_column.upwards_ambient", 0.8F, 160, "ripple"));
		all.add(row(97, 0xE85A4F, 0x30D5C8, "scales", "ember_guard", 2, GuardStyle.STING, ContextProfile.NONE, "entity.puffer_fish.blow_up", 0.9F, 185, "duotone"));
		all.add(row(98, 0xFFA599, 0x117A6F, "circuit", "lucky_charm", 2, GuardStyle.GLOW, ContextProfile.CROWD_SCALE, "entity.allay.ambient_with_item", 1.1F, 150, "tint"));
		all.add(row(99, 0xD94F41, 0x66E5DB, "kaleido", "echo_pulse", 2, GuardStyle.GUST, ContextProfile.NONE, "ambient.underwater.loop", 0.9F, 195, "glitch"));
		// F20 "Spectral Circus" (high-contrast complements)
		all.add(row(100, 0xFF2E9A, 0x2EFF93, "lightning", "prismatic_rays", 2, GuardStyle.NONE, ContextProfile.NONE, "block.amethyst_block.resonate", 1.4F, 115, "glitch"));
		all.add(row(101, 0x7A00E6, 0xE6C800, "vortex", "void_tendrils", 2, GuardStyle.BLIND, ContextProfile.NIGHT_BLOOM, "entity.evoker.cast_spell", 0.7F, 220, "posterize"));
		all.add(row(102, 0xFF6600, 0x0066FF, "petals", "honey_drip", 2, GuardStyle.NONE, ContextProfile.LOW_HEALTH_FRENZY, "block.honey_block.slide", 1.4F, 170, "pixelate"));
		all.add(row(103, 0x00E5B0, 0xE5003F, "circuit", "wax_glow", 2, GuardStyle.DARK, ContextProfile.HEALTH_HUE, "block.chorus_flower.grow", 0.8F, 140, "radialblur"));
		all.add(row(104, 0xFFE600, 0x3D0099, "interference", "storm_cage", 2, GuardStyle.GUST, ContextProfile.STORM_CHARGED, "block.bell.resonate", 1.2F, 125, "duotone"));

		return List.copyOf(all);
	}

	/**
	 * Builds one catalogue row. Colors are given as 24-bit RGB (matching the design
	 * matrix) and packed to opaque ARGB. paramA (surface pattern scale, ~0.3-1.2),
	 * paramB (surface scroll speed, ~0.4-1.4) and behaviorStrength (~0.8-1.5) are
	 * derived per id so every effect animates distinctly; the same derivation is
	 * mirrored by tools/gen_post_effects.py. The modulus is {@link #PARAM_CYCLE}
	 * (not {@link #COUNT}) so ids 0..74 keep their V1 params across expansions.
	 */
	private static EffectDefinition row(int id, int rgbPrimary, int rgbSecondary, String surfaceName, String behaviorId, int behaviorVariant,
			GuardStyle guard, ContextProfile context, String ambientSoundId, float ambientPitch, int ambientPeriodTicks, String screenTemplate) {
		float paramA = 0.3F + 0.012F * id;
		float paramB = 0.4F + ((id * 37) % PARAM_CYCLE) / (float) PARAM_CYCLE;
		float behaviorStrength = 0.8F + 0.7F * ((id * 23) % PARAM_CYCLE) / (float) PARAM_CYCLE;
		return EffectDefinition.of(id, 0xFF000000 | rgbPrimary, 0xFF000000 | rgbSecondary, SurfaceTemplate.valueOf(surfaceName.toUpperCase(Locale.ROOT)), paramA, paramB,
				behaviorId, behaviorVariant, behaviorStrength, guard, context, ambientSoundId, ambientPitch, ambientPeriodTicks, screenTemplate);
	}

	/** Returns the definition for the given id, clamped into [0, {@link #COUNT} - 1]. */
	public static EffectDefinition get(int id) {
		return ALL.get(Math.clamp(id, 0, ALL.size() - 1));
	}

	/**
	 * Fails fast when the catalogue is malformed. Enforces: exactly {@link #COUNT}
	 * entries with ids 0..COUNT-1; all palette pairs pairwise distinct; all
	 * (insideBehaviorId, behaviorVariant) pairs pairwise distinct with every behavior id
	 * used EXACTLY 3 times covering variants {0, 1, 2}; ambientPeriodTicks positive;
	 * every (ambientSoundId, ambientPitch, ambientPeriodTicks) triple distinct;
	 * every behavior id registered in {@link InsideEffectBehavior#REGISTRY};
	 * every screenTemplate one of the 16 {@link #SCREEN_TEMPLATES}; every
	 * ambientSoundId resolvable in the vanilla sound registry; no surface,
	 * screenTemplate or behavior id repeated within a 5-effect color family
	 * (id / 5); no (surface, screenTemplate) pair used more than 3 times across
	 * the whole catalogue; and every {@link #SCREEN_TEMPLATES} entry used by at
	 * least one effect.
	 */
	public static void validate() {
		if (ALL.size() != COUNT) {
			throw new IllegalStateException("Expected " + COUNT + " effect definitions, found " + ALL.size());
		}

		Set<Long> palettes = new HashSet<>();
		Map<String, Set<Integer>> behaviorVariants = new HashMap<>();
		Set<String> soundTriples = new HashSet<>();
		Map<Integer, Set<SurfaceTemplate>> surfacesPerFamily = new HashMap<>();
		Map<Integer, Set<String>> screenFxPerFamily = new HashMap<>();
		Map<Integer, Set<String>> behaviorsPerFamily = new HashMap<>();
		Map<String, Integer> surfaceScreenPairCounts = new HashMap<>();
		Set<String> screenTemplatesUsed = new HashSet<>();
		for (int i = 0; i < ALL.size(); i++) {
			EffectDefinition def = ALL.get(i);
			if (def.id() != i) {
				throw new IllegalStateException("Effect at index " + i + " has id " + def.id());
			}

			long palette = ((long) def.argbPrimary() << 32) | (def.argbSecondary() & 0xFFFFFFFFL);
			if (!palettes.add(palette)) {
				throw new IllegalStateException("Effect " + def.id() + " reuses another effect's palette pair");
			}

			if (!behaviorVariants.computeIfAbsent(def.insideBehaviorId(), b -> new HashSet<>()).add(def.behaviorVariant())) {
				throw new IllegalStateException("Effect " + def.id() + " reuses behavior/variant pair: " + def.insideBehaviorId() + "@" + def.behaviorVariant());
			}

			if (def.ambientPeriodTicks() <= 0) {
				throw new IllegalStateException("Effect " + def.id() + " has non-positive ambientPeriodTicks");
			}

			String soundTriple = def.ambientSoundId() + "@" + def.ambientPitch() + "@" + def.ambientPeriodTicks();
			if (!soundTriples.add(soundTriple)) {
				throw new IllegalStateException("Effect " + def.id() + " reuses ambient sound triple: " + soundTriple);
			}

			if (!InsideEffectBehavior.REGISTRY.containsKey(def.insideBehaviorId())) {
				throw new IllegalStateException("Effect " + def.id() + " references unregistered inside behavior: " + def.insideBehaviorId());
			}

			if (!SCREEN_TEMPLATES.contains(def.screenTemplate())) {
				throw new IllegalStateException("Effect " + def.id() + " uses unknown screen template: " + def.screenTemplate());
			}

			if (!BuiltInRegistries.SOUND_EVENT.containsKey(Identifier.parse("minecraft:" + def.ambientSoundId()))) {
				throw new IllegalStateException("Effect " + def.id() + " ambient sound does not resolve: " + def.ambientSoundId());
			}

			int family = def.id() / 5;
			if (!surfacesPerFamily.computeIfAbsent(family, f -> new HashSet<>()).add(def.surface())) {
				throw new IllegalStateException("Family " + family + " repeats surface " + def.surface() + " (effect " + def.id() + ")");
			}

			if (!screenFxPerFamily.computeIfAbsent(family, f -> new HashSet<>()).add(def.screenTemplate())) {
				throw new IllegalStateException("Family " + family + " repeats screen template " + def.screenTemplate() + " (effect " + def.id() + ")");
			}

			if (!behaviorsPerFamily.computeIfAbsent(family, f -> new HashSet<>()).add(def.insideBehaviorId())) {
				throw new IllegalStateException("Family " + family + " repeats behavior " + def.insideBehaviorId() + " (effect " + def.id() + ")");
			}

			String surfaceScreenPair = def.surface() + "+" + def.screenTemplate();
			int pairCount = surfaceScreenPairCounts.merge(surfaceScreenPair, 1, Integer::sum);
			if (pairCount > 3) {
				throw new IllegalStateException("(surface, screenTemplate) pair " + surfaceScreenPair + " used more than 3 times (effect " + def.id() + ")");
			}

			screenTemplatesUsed.add(def.screenTemplate());
		}

		// Every behavior id must appear exactly 3 times, once per variant {0, 1, 2}.
		for (Map.Entry<String, Set<Integer>> entry : behaviorVariants.entrySet()) {
			if (!entry.getValue().equals(Set.of(0, 1, 2))) {
				throw new IllegalStateException("Behavior " + entry.getKey() + " must be used exactly 3 times with variants {0,1,2}, found variants " + entry.getValue());
			}
		}

		// Every screen-fx template in the catalogue must be exercised by at least one effect.
		for (String template : SCREEN_TEMPLATES) {
			if (!screenTemplatesUsed.contains(template)) {
				throw new IllegalStateException("Screen template " + template + " is not used by any effect");
			}
		}
	}
}
