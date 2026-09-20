package net.hawthorn.dndsheets.client.gui.components;

import net.hawthorn.dndsheets.client.gui.GuiStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.network.chat.Component;

/**
 * <p>Section header within a list: the brass-colored label between two rules, with no row background.
 * Splits a long menu (the DM Panel reached 17 indistinguishable flat rows) into named blocks,
 * without inventing a second container: {@code ButtonListWidget} only knows about {@code Button}, so the
 * header IS a button — one that doesn't respond to clicks or keyboard and paints itself as a label. See
 * {@code ListPickerScreen.addHeader}, which also excludes it from the search.</p>
 */
public class SectionHeader extends Button {

	public SectionHeader(Component label, int width, int height) {
		super(0, 0, width, height, label, b -> {}, DEFAULT_NARRATION);
	}

	@Override
	public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		Font font = Minecraft.getInstance().font;
		int centerX = this.getX() + this.width / 2;
		int ruleY = this.getY() + this.height / 2;
		int halfText = font.width(this.getMessage()) / 2;

		GuiStyle.rule(guiGraphics, this.getX() + 6, centerX - halfText - 6, ruleY);
		GuiStyle.rule(guiGraphics, centerX + halfText + 6, this.getX() + this.width - 6, ruleY);
		guiGraphics.drawCenteredString(font, this.getMessage(), centerX, this.getY() + (this.height - 8) / 2, GuiStyle.ACCENT_COLOR);
	}

	//A label, not a control: no clicking, sound, or keyboard focus (ButtonListWidget re-enables
	//"active" every frame for visible rows, so the block has to happen through these methods and
	//not through active=false).
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		return false;
	}

	@Override
	public void playDownSound(SoundManager soundManager) {
	}

	@Override
	public boolean isFocused() {
		return false;
	}
}
