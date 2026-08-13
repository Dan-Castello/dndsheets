package net.hawthorn.dndsheets.client.gui;

import net.minecraft.client.gui.GuiGraphics;

/**
 * <p>Colores y panel de fondo compartidos por las pantallas "planas" del mod (sin textura propia:
 * paneles de lista, formularios cortos, diálogos modales). Antes cada una flotaba con sus botones
 * sueltos directamente sobre el fondo borroso de Minecraft, sin ningún elemento que las distinguiera
 * — la única excepción ad hoc era el relleno manual de {@code DeathSaveScreen}. Este panel unifica
 * esa identidad visual en un solo sitio en vez de repetir el mismo {@code guiGraphics.fill(...)} en
 * cada pantalla. Ver GUI_REFERENCE.md.</p>
 */
final class GuiStyle {
	static final int TITLE_COLOR = 0xFFFFFF;
	static final int SUBTITLE_COLOR = 0xAAAAAA;
	static final int MUTED_COLOR = 0x888888;

	private static final int FILL_COLOR = 0xCC101010;
	private static final int BORDER_COLOR = 0xFF3E3E3E;

	private GuiStyle() {
	}

	static void panel(GuiGraphics guiGraphics, int left, int top, int right, int bottom) {
		guiGraphics.fill(left, top, right, bottom, FILL_COLOR);
		guiGraphics.fill(left, top, right, top + 1, BORDER_COLOR);
		guiGraphics.fill(left, bottom - 1, right, bottom, BORDER_COLOR);
		guiGraphics.fill(left, top, left + 1, bottom, BORDER_COLOR);
		guiGraphics.fill(right - 1, top, right, bottom, BORDER_COLOR);
	}
}
