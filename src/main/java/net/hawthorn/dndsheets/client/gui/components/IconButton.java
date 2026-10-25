package net.hawthorn.dndsheets.client.gui.components;

import net.hawthorn.dndsheets.client.gui.Icons;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * <p>A square tome-style button that holds only a glyph (a d20, a pencil, a plus), with its meaning in the tooltip. The
 * same parchment strip and bevel as {@link TomeButton}, so a row of small buttons next to a row of text ones reads as one
 * interface. Used where a text label would not fit, such as the roll buttons on the character sheet.</p>
 */
public class IconButton extends Button {
	private static final int FILL_IDLE = 0xF2241C13;
	private static final int FILL_HOVER = 0xF2382B1B;
	private static final int BEVEL_LIGHT = 0xFF5A4830;
	private static final int BEVEL_DARK = 0xFF0B0906;
	private static final int RAIL_HOVER = 0xFFC9A227;

	private final ResourceLocation icon;

	public IconButton(int x, int y, int size, ResourceLocation icon, Component tooltip, OnPress onPress) {
		super(x, y, size, size, Component.empty(), onPress, DEFAULT_NARRATION);
		this.icon = icon;
		this.setTooltip(Tooltip.create(tooltip));
	}

	@Override
	public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		int left = this.getX();
		int top = this.getY();
		int right = left + this.width;
		int bottom = top + this.height;
		boolean hot = this.isHoveredOrFocused() && this.active;

		guiGraphics.fill(left, top, right, bottom, hot ? FILL_HOVER : FILL_IDLE);
		guiGraphics.fill(left, top, right - 1, top + 1, hot ? RAIL_HOVER : BEVEL_LIGHT);
		guiGraphics.fill(left, top, left + 1, bottom - 1, hot ? RAIL_HOVER : BEVEL_LIGHT);
		guiGraphics.fill(left + 1, bottom - 1, right, bottom, BEVEL_DARK);
		guiGraphics.fill(right - 1, top + 1, right, bottom, BEVEL_DARK);

		int inset = Math.max(1, this.width / 8);
		Icons.draw(guiGraphics, icon, left + inset, top + inset, this.width - 2 * inset);
	}
}
