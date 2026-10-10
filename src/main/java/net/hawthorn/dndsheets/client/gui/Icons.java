package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.Condition;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

import javax.annotation.Nullable;

/**
 * <p>The 32x32 game-icons.net glyphs under {@code textures/gui/icons} (credited in {@code CREDITS.txt}), drawn
 * at whatever size a screen needs. The lookup is by the same lowercase labels the rules engine already uses
 * ({@link Condition#label()}, {@code MagicSchool.key}, {@code CreatureType.label}), so nothing keeps a second
 * table: a concept either has its icon or {@code null} comes back and the caller falls back to text.</p>
 */
public final class Icons {
	private static final int SOURCE = 32;

	private Icons() {
	}

	/** Icon for a condition by its label ("frightened") or display label ("Exhaustion 3"); {@code null} if it is not one. */
	@Nullable
	public static ResourceLocation condition(String label) {
		if (label == null || label.isBlank()) return null;
		String word = label.trim().toLowerCase(Locale.ROOT);
		int space = word.indexOf(' ');
		if (space > 0) word = word.substring(0, space);
		Condition condition = Condition.fromLabel(word);
		return condition == null ? null : icon("condition", condition.label());
	}

	public static ResourceLocation school(String key) {
		return icon("school", key);
	}

	public static ResourceLocation creature(String key) {
		return icon("creature", key);
	}

	public static ResourceLocation action(String key) {
		return icon("action", key);
	}

	public static ResourceLocation resource(String key) {
		return icon("resource", key);
	}

	public static ResourceLocation stat(String key) {
		return icon("stat", key);
	}

	private static ResourceLocation icon(String category, String name) {
		return new ResourceLocation("dndsheets", "textures/gui/icons/" + category + "/" + name + ".png");
	}

	/** Draws an icon scaled to {@code size} pixels; a missing texture draws Minecraft's checkerboard, never crashes. */
	public static void draw(GuiGraphics guiGraphics, ResourceLocation icon, int x, int y, int size) {
		guiGraphics.blit(icon, x, y, size, size, 0, 0, SOURCE, SOURCE, SOURCE, SOURCE);
	}
}
