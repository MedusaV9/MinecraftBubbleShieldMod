package com.bubbleshield.effect;

/**
 * Audio family of a bubble surface: every {@link SurfaceTemplate} maps to one of
 * five sound characters, which pick the impact-layer sound pair the server plays
 * at a projectile hit point (see {@code ShieldLogic}) and the client-side
 * proximity hum flavour (VOID hums with the portal loop instead of the beacon).
 *
 * <p>{@link #of(SurfaceTemplate)} is an EXHAUSTIVE switch expression (no default
 * branch), so adding a {@link SurfaceTemplate} constant without assigning its
 * sound group is a compile error — the mapping can never silently rot.
 */
public enum SurfaceSoundGroup {
	/** Plasma/lightning/fire: crackling charge sounds. */
	ENERGY,
	/** Facets, ice, films and glass: chimes and shatters. */
	CRYSTAL,
	/** Growth, fluids, smoke and ectoplasm: soft squelches. */
	ORGANIC,
	/** Circuits, grids and geometric lattices: metallic clicks. */
	TECH,
	/** Portals, rifts and deep space: hollow resonance. */
	VOID;

	/** The sound group of the given surface technique family (exhaustive; see class javadoc). */
	public static SurfaceSoundGroup of(SurfaceTemplate surface) {
		return switch (surface) {
			// Charged, burning or arcing energy surfaces.
			case PLASMA, LIGHTNING, SOLARFLARE, ARCS, EMBERSTORM,
					AURORA, RIBBONAURORA, LAVAFLOW, PLASMAGLOBE -> ENERGY;
			// Faceted, icy, filmy or glassy surfaces.
			case HEX, CRYSTALREFRACT, CRYSTALSDF, DEEPICE, FROSTFERN, SHARDTESS,
					SPARKLE, PRISMDISPERSE, THINFILM, CHROME, STAINEDGLASS,
					VORONOI, KALEIDO, RIDGED, IRISFILM, OILSLICK -> CRYSTAL;
			// Living, fluid or smoky surfaces.
			case PETALS, MYCELIA, ECTOPLASM, SCALES, BIOLUME, FLUIDINK,
					CURLSMOKE, AETHERSMOKE, WAVES, CAUSTIC, VOLUMECLOUD,
					TENDRILNET, RAYMARCHFOG -> ORGANIC;
			// Structured, lattice-like or engineered surfaces.
			case CIRCUIT, RUNECIRCUIT, HOLOGRID, HOLOPARALLAX, TRUCHET, MOIRE,
					INTERFERENCE, RINGS, TRIWEAVE, SACREDGEO -> TECH;
			// Portal, rift, fractal-space or ghostly surfaces.
			case PORTALVOID, VOIDRIFT, VOIDTENDRIL, GRAVLENS, NEBULA, STARFIELD,
					GALAXYSWIRL, VORTEX, KALISET, SPECTRALVEIL, ORBITTRAP, PHANTOMECHO -> VOID;
		};
	}
}
