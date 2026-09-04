package org.continuouspath.justtype.ime

/**
 * In-process flag for whether the JustType IME's window is currently shown.
 *
 * The Nav overlay can't rely on the accessibility window list alone to detect an open
 * keyboard: a full-screen touch-capture overlay (Nav's or the IME's own, for
 * touch-screen-switch / directional selection) occludes the IME window, and the system
 * drops fully-occluded windows from getWindows(). All JT components share one process,
 * so the IME publishes its own window state here.
 */
object ImeWindowState {
	@Volatile
	var shown = false
}
