package com.bubbleshield.effect;

/**
 * Visual surface style of the bubble. Pure data: the client renderer picks the
 * matching shader/texture treatment; the server only stores and syncs it.
 */
public enum SurfaceTemplate {
	PLASMA,
	HEX,
	WAVES,
	AURORA,
	SPARKLE
}
