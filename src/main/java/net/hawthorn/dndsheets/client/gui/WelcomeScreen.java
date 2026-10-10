package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.init.DndsheetsModKeyMappings;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * <p>The first thing a new player sees, once per character-less login: what the four keys do and two ways forward
 * (set the character up, open the guide). It exists because everything the mod offers used to be discoverable only by
 * already knowing the H key or the {@code /dndguide} command.</p>
 *
 * <p>The server decides when to show it (the {@code onboarded} flag on the sheet, see {@code SheetLoader.onPlayerLogin})
 * and opens it with {@code ScreenActionMessage.Action.ONBOARDING_OPEN}; nothing here writes anything.</p>
 */
public class WelcomeScreen extends ModalDialogScreen {
	private static final int WIDTH = 320;
	private static final int HEIGHT = 196;
	private static final int ROW = 13;

	protected WelcomeScreen() {
		super(Component.translatable("gui.dndsheets.welcome.title"), WIDTH, HEIGHT);
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new WelcomeScreen());
	}

	@Override
	protected void init() {
		addModalButton(20, HEIGHT - 66, WIDTH - 40, 20, Component.translatable("gui.dndsheets.welcome.setup"), button -> CharacterSetupScreen.open(this));
		addModalButton(20, HEIGHT - 42, (WIDTH - 44) / 2, 20, Component.translatable("gui.dndsheets.welcome.guide"), button -> {
			this.onClose();
			if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.connection.sendCommand("dndguide");
		});
		addModalButton(24 + (WIDTH - 44) / 2, HEIGHT - 42, (WIDTH - 44) / 2, 20, Component.translatable("gui.dndsheets.welcome.close"), button -> this.onClose());
	}

	private void keyLine(GuiGraphics guiGraphics, int row, KeyMapping key, String labelKey) {
		int y = dialogTop() + 40 + row * ROW;
		guiGraphics.drawString(this.font, key.getTranslatedKeyMessage().copy().withStyle(net.minecraft.ChatFormatting.GOLD), dialogLeft() + 28, y, GuiStyle.ACCENT_COLOR);
		guiGraphics.drawString(this.font, Component.translatable(labelKey), dialogLeft() + 70, y, GuiStyle.TITLE_COLOR);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderPanel(guiGraphics);
		guiGraphics.drawCenteredString(this.font, GuiStyle.title(this.title), this.width / 2, dialogTop() + 10, GuiStyle.TITLE_COLOR);
		GuiStyle.ruleOrnate(guiGraphics, dialogLeft() + 14, dialogLeft() + WIDTH - 14, dialogTop() + 24);
		guiGraphics.drawCenteredString(this.font, Component.translatable("gui.dndsheets.welcome.intro"), this.width / 2, dialogTop() + 28, GuiStyle.SUBTITLE_COLOR);
		keyLine(guiGraphics, 0, DndsheetsModKeyMappings.CHARACTER, "gui.dndsheets.welcome.key_sheet");
		keyLine(guiGraphics, 1, DndsheetsModKeyMappings.GRIMOIRE, "gui.dndsheets.welcome.key_grimoire");
		keyLine(guiGraphics, 2, DndsheetsModKeyMappings.QUICK_CAST, "gui.dndsheets.welcome.key_cast");
		keyLine(guiGraphics, 3, DndsheetsModKeyMappings.DM_PANEL, "gui.dndsheets.welcome.key_dm");
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
	}
}
