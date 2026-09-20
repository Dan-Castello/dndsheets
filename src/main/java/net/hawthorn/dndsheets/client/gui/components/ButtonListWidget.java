package net.hawthorn.dndsheets.client.gui.components;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractScrollWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>Vertical list of full-width buttons with automatic scrolling if they don't all fit in the available
 * height — used by the DM Panel's pick-one-of-several screens (player, preset, trait,
 * monster action, custom attack to remove). Before this, each screen centered its list by
 * hand with {@code (height - total) / 2} with no clamp: with enough rows that calculation went
 * negative and pushed the buttons off-screen, with no way to reach them.</p>
 *
 * <p>Buttons must be registered on the screen with {@code Screen#addWidget} (NOT
 * {@code addRenderableWidget}, so the screen doesn't draw them on its own — this widget already
 * handles it) and on this widget with {@link #addRow}. Same scissor/scroll pattern already used by
 * {@link RollScrollWidget} for the Attacks tab, simplified for a single one-button row.</p>
 */
public class ButtonListWidget extends AbstractScrollWidget {
	private final List<Button> rows = new ArrayList<>();
	//Gap between rows. The HEIGHT is set by each button (getHeight), not this list: that way a section
	//header can be half the height of a row without this widget having to know what a header is.
	//When all rows had the same height, the DM Panel's five headers cost five list rows'
	//worth of space and forced it to scroll with seventeen actions that, by height, would otherwise have fit.
	private final int spacing;

	public ButtonListWidget(int x, int y, int width, int height, int spacing) {
		super(x, y, width, height, Component.empty());
		this.spacing = spacing;
	}

	private int stepOf(Button row) {
		return row.getHeight() + spacing;
	}

	public void addRow(Button button) {
		rows.add(button);
	}

	//Used by ListPickerScreen to filter by search text: buttons that fall out of the visible
	//list aren't destroyed (they stay registered in Screen#children so a future replaceRows can
	//bring them back), but visible/active must be turned off by hand — renderContents only does it
	//for the ones that are ALREADY within the current list's scroll range.
	public void replaceRows(List<Button> newRows) {
		for (Button button : rows) {
			if (!newRows.contains(button)) {
				button.visible = false;
				button.active = false;
			}
		}
		rows.clear();
		rows.addAll(newRows);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
	}

	@Override
	protected int getInnerHeight() {
		int total = 0;
		for (Button row : rows) total += stepOf(row);
		return total;
	}

	//Half a row per wheel notch, using the first row as a reference: with rows of two different heights
	//there's no single "the" height, and the scroll step doesn't need to be exact, just comfortable.
	@Override
	protected double scrollRate() {
		return rows.isEmpty() ? 12 : stepOf(rows.get(0)) / 2.0;
	}

	@Override
	protected boolean scrollbarVisible() {
		return getInnerHeight() > this.height;
	}

	@Override
	public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		if (!this.visible) return;
		this.renderBackground(guiGraphics);
		guiGraphics.enableScissor(this.getX() + 1, this.getY() + 1, this.getX() + this.width - 1, this.getY() + this.height - 1);
		renderContents(guiGraphics, mouseX, mouseY, partialTick);
		guiGraphics.disableScissor();
		this.renderDecorations(guiGraphics);
	}

	@Override
	protected void renderContents(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		if (rows.isEmpty()) return;
		int scroll = (int) this.scrollAmount();
		//Only what falls within the clip is still positioned and drawn (before, setX/setY was called
		//on every button every frame, visible or not, and with long lists it was noticeable while scrolling). What
		//changes with per-row heights is that the range no longer comes from a division: the height accumulates
		//while iterating, which is the same pass this loop was already doing.
		int offset = 0;
		for (Button button : rows) {
			int step = stepOf(button);
			int top = offset - scroll;
			offset += step;
			//One step of margin above and below, so it doesn't "pop" right at the edge.
			boolean rowVisible = top + step >= -step && top <= this.height + step;
			button.visible = rowVisible;
			button.active = rowVisible;
			if (!rowVisible) continue;
			button.setX(this.getX());
			button.setY(this.getY() + top);
			button.render(guiGraphics, mouseX, mouseY, partialTicks);
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return false; //Clicks go to the child buttons (registered separately on the screen), not to the container.
	}

	//scrollAmount()/setScrollAmount() are protected in AbstractScrollWidget; this exposes them so
	//ListPickerScreen can carry over the scroll position of a list it replaces when rebuilding
	//the screen (see its init()). The clamp to the real height is still done by setScrollAmount itself.
	public double scroll() {
		return this.scrollAmount();
	}

	public void scrollTo(double amount) {
		this.setScrollAmount(amount);
	}
}
