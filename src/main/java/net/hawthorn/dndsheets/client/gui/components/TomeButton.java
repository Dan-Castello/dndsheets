package net.hawthorn.dndsheets.client.gui.components;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.Mth;

/**
 * <p>List row with the mod's identity: a strip of parchment over the panel's leather, with a brass
 * rule on the left and Minecraft-style beveling. Replaces vanilla's stone-gray button, which
 * on a leather panel reads as a widget borrowed from another interface.</p>
 *
 * <p>Lives here instead of in each screen because {@code ListPickerScreen.addRow} and {@code SmallFormScreen}
 * are the only two places that create rows: changing it here repaints, from the inside, the more than forty
 * screens that hang off them, the same way {@code GuiStyle} did with its frames.</p>
 *
 * <p>Focus/hover state isn't marked with just a background color change: the brass rule also lights up
 * and the text lightens. A single-tone change on a dark background is exactly what doesn't
 * get distinguished at low monitor brightness.</p>
 */
public class TomeButton extends Button {

	//Dim parchment at rest, lit up on hover. Darker than the character sheet: it's
	//a strip over leather, not the whole sheet, and competes with less surface area.
	private static final int FILL_IDLE = 0xF2241C13;
	private static final int FILL_HOVER = 0xF2382B1B;
	private static final int BEVEL_LIGHT = 0xFF5A4830;
	private static final int BEVEL_DARK = 0xFF0B0906;
	private static final int RAIL_IDLE = 0xFF6B5636;
	private static final int RAIL_HOVER = 0xFFC9A227;
	private static final int TEXT_IDLE = 0xFFCBBA97;
	private static final int TEXT_HOVER = 0xFFF0E2C0;
	private static final int TEXT_DISABLED = 0xFF6E6455;

	private static final int RAIL_WIDTH = 2;
	/** Padding between the text and the edges, so clipped text doesn't sit flush against the bevel. */
	private static final int TEXT_PADDING = 2;
	private static final String ELLIPSIS = "...";

	public TomeButton(int x, int y, int width, int height, Component message, OnPress onPress) {
		super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
	}

	/** Same entry point as {@code Button.builder(...)}, so the call site that creates it doesn't need to change shape. */
	public static TomeButton of(Component message, OnPress onPress, int x, int y, int width, int height) {
		return new TomeButton(x, y, width, height, message, onPress);
	}

	@Override
	public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		int left = this.getX();
		int top = this.getY();
		int right = left + this.width;
		int bottom = top + this.height;

		boolean active = this.isHoveredOrFocused() && this.active;
		int alpha = Mth.ceil(this.alpha * 255.0F) << 24;

		guiGraphics.fill(left, top, right, bottom, (active ? FILL_HOVER : FILL_IDLE));
		//Minecraft-style bevel, same as GuiStyle: it's what makes the row feel like it belongs to the game.
		guiGraphics.fill(left, top, right - 1, top + 1, BEVEL_LIGHT);
		guiGraphics.fill(left, top, left + 1, bottom - 1, BEVEL_LIGHT);
		guiGraphics.fill(left + 1, bottom - 1, right, bottom, BEVEL_DARK);
		guiGraphics.fill(right - 1, top + 1, right, bottom, BEVEL_DARK);

		//Brass rule on the left: it's the mark of "this is a tome row," and lighting up gives a
		//second focus cue besides the background.
		guiGraphics.fill(left + 1, top + 1, left + 1 + RAIL_WIDTH, bottom - 1, active ? RAIL_HOVER : RAIL_IDLE);

		int color = !this.active ? TEXT_DISABLED : (active ? TEXT_HOVER : TEXT_IDLE);
		Minecraft minecraft = Minecraft.getInstance();
		//The text is centered in the space LEFT after the rule, not the whole button: centering it on the
		//button would leave it visibly off-center to the left.
		int textLeft = left + 1 + RAIL_WIDTH + TEXT_PADDING;
		int textRight = right - TEXT_PADDING;
		int available = textRight - textLeft;
		Component message = this.getMessage();

		//Plain drawCenteredString doesn't clip ANYTHING: a label wider than the row —"Bandit Ambush ·
		//Bandit x4, Bandit Captain · Deadly", and half the compendium list— overflowed on both sides and
		//read as cut off at both ends, with neither a start nor an end.
		if (this.isHoveredOrFocused()) {
			//On top: vanilla's version, which clips it to the row and scrolls it side to side to the end. Only
			//on the hovered row, because five labels sliding at once aren't readable, they're just watched.
			renderScrollingString(guiGraphics, minecraft.font, message, textLeft, top, textRight, bottom, color | alpha);
			return;
		}
		int y = top + (this.height - 8) / 2;
		if (minecraft.font.width(message) <= available) {
			guiGraphics.drawCenteredString(minecraft.font, message, textLeft + available / 2, y, color | alpha);
			return;
		}
		//And at rest, the beginning with an ellipsis: the name is at the start, and dots signal
		//"there's more" —which is exactly what a hard cutoff doesn't say—.
		FormattedText trimmed = FormattedText.composite(
			minecraft.font.substrByWidth(message, available - minecraft.font.width(ELLIPSIS)), FormattedText.of(ELLIPSIS));
		guiGraphics.drawString(minecraft.font, Language.getInstance().getVisualOrder(trimmed),
			textLeft, y, color | alpha);
	}
}
