package net.hawthorn.dndsheets.client.gui.components;

import net.minecraft.client.gui.GuiGraphics;

/**
 * <p>Brass frame for the character sheet's slots: the text fields and the attack list.</p>
 *
 * <p>Vanilla draws both the same way —a one-pixel gray ring (white when focused) around a
 * black fill— and both colors are fixed inside {@code EditBox.renderWidget} and
 * {@code AbstractScrollWidget.renderBorder}. On parchment that reads as widgets borrowed from another
 * interface.</p>
 *
 * <p>Removing the border isn't an option for {@code EditBox}: {@code setBordered(false)} takes the
 * black fill with it AND moves the text (from centered-with-margin to flush against the top-left
 * corner), and without a dark background behind it the text would have to be ink on parchment — which with
 * Minecraft's fixed shadow reads as doubled. So the ring isn't removed: it's repainted over. It occupies exactly
 * one pixel outside the slot, meaning covering it touches neither the text nor the interior.</p>
 *
 * <p>Lives in this package and not alongside {@code GuiStyle} because both sides use it: the sheet
 * screen ({@code client.gui}) for its fields, and {@code RollScrollWidget} (here) for the attack list and
 * for each row's name. {@code GuiStyle} isn't visible from here.</p>
 */
public final class TomeField {

	//Same palette as GuiStyle and TomeButton. Duplicated deliberately: the components package can't see GuiStyle,
	//and a repeated color is less harmful than exposing the entire style class for just three integers.
	private static final int RING = 0xFF6B5636;
	private static final int RING_FOCUSED = 0xFFC9A227;
	private static final int SHADOW = 0xFF8A7B5E;
	/** Fill for a large slot (the attack list): leather, not pure black. */
	public static final int WELL_FILL = 0xFF15100A;

	private TomeField() {
	}

	/**
	 * <p>Repaints a slot's ring in brass, and adds shadow outside on the top and left —where
	 * light would come from in Minecraft's beveling— so it reads as sunken into the sheet.</p>
	 *
	 * <p>The coordinates are the RING's, meaning one pixel outside the widget.</p>
	 */
	public static void frame(GuiGraphics guiGraphics, int left, int top, int right, int bottom, boolean focused) {
		int ring = focused ? RING_FOCUSED : RING;

		guiGraphics.fill(left, top, right, top + 1, ring);
		guiGraphics.fill(left, bottom - 1, right, bottom, ring);
		guiGraphics.fill(left, top, left + 1, bottom, ring);
		guiGraphics.fill(right - 1, top, right, bottom, ring);

		guiGraphics.fill(left - 1, top - 1, right, top, SHADOW);
		guiGraphics.fill(left - 1, top - 1, left, bottom, SHADOW);
	}

	/** Overload for a widget: the ring sits just outside its rectangle. */
	public static void frameWidget(GuiGraphics guiGraphics, int x, int y, int width, int height, boolean focused) {
		frame(guiGraphics, x - 1, y - 1, x + width + 1, y + height + 1, focused);
	}
}
