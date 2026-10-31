package net.hawthorn.dndsheets.dungeon.client.gui;

import net.hawthorn.dndsheets.client.gui.GuiStyle;
import net.hawthorn.dndsheets.client.gui.ModalDialogScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * <p>What a whole dungeon gets around its rooms (see GridToStructure.Extras): a ceiling, the inside carved
 * out, a light every few blocks, and a sign with each note. Changes go straight into the editor's fields;
 * "Back" returns to it.</p>
 */
public class DungeonExtrasScreen extends ModalDialogScreen {
	private static final int WIDTH = 220;
	private static final int HEIGHT = 168;
	private static final int ROW = 22;
	private static final int[] SPACINGS = {0, 4, 6, 8, 10, 12, 16};

	private final DungeonTraceScreen trace;

	DungeonExtrasScreen(DungeonTraceScreen trace) {
		super(Component.translatable("gui.dndsheets.dungeon_extras.title"), WIDTH, HEIGHT);
		this.trace = trace;
	}

	@Override
	protected void init() {
		int x = 16, width = WIDTH - 32, y = 26;
		toggle(x, y, width, "ceiling", trace.ceiling, () -> trace.ceiling = !trace.ceiling);
		y += ROW;
		toggle(x, y, width, "carve", trace.carve, () -> trace.carve = !trace.carve);
		y += ROW;
		addModalButton(x, y, width, 18, Component.translatable("gui.dndsheets.dungeon_extras.light_block", trace.lightBlockOrDefault().getPath()),
			b -> BlockPickerScreen.open(this, id -> trace.lightBlock = id))
			.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.dungeon_extras.tip_light_block")));
		y += ROW;
		addModalButton(x, y, 18, 18, Component.literal("-"), b -> stepSpacing(-1)); //i18n-ok: symbol
		addModalButton(x + width - 18, y, 18, 18, Component.literal("+"), b -> stepSpacing(1)); //i18n-ok: symbol
		y += ROW;
		Button signs = toggle(x, y, width, "signs", trace.signs, () -> trace.signs = !trace.signs);
		signs.active = !trace.notes.isEmpty();
		y += ROW + 4;
		addModalButton(x, y, width, 20, Component.translatable("gui.dndsheets.common.back"), b -> onClose());
	}

	private Button toggle(int x, int y, int width, String key, boolean on, Runnable flip) {
		Button button = addModalButton(x, y, width, 18, Component.translatable("gui.dndsheets.dungeon_extras." + key,
				Component.translatable(on ? "gui.dndsheets.dungeon_extras.on" : "gui.dndsheets.dungeon_extras.off")), b -> {
			flip.run();
			this.rebuildWidgets();
		});
		button.setTooltip(Tooltip.create(Component.translatable("gui.dndsheets.dungeon_extras.tip_" + key)));
		return button;
	}

	private void stepSpacing(int delta) {
		int index = 0;
		for (int i = 0; i < SPACINGS.length; i++) if (SPACINGS[i] == trace.lightSpacing) index = i;
		trace.lightSpacing = SPACINGS[Math.max(0, Math.min(SPACINGS.length - 1, index + delta))];
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(trace);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderPanel(guiGraphics);
		guiGraphics.drawCenteredString(this.font, GuiStyle.title(this.title), this.width / 2, dialogTop() + 8, GuiStyle.TITLE_COLOR);
		Component spacing = trace.lightSpacing > 0
			? Component.translatable("gui.dndsheets.dungeon_extras.light_spacing", trace.lightSpacing)
			: Component.translatable("gui.dndsheets.dungeon_extras.light_none");
		guiGraphics.drawCenteredString(this.font, spacing, this.width / 2, dialogTop() + 26 + 3 * ROW + 5, GuiStyle.TITLE_COLOR);
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
	}
}
