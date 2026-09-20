package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonObject;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.network.DeathSaveMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * <p>Forced window while the character is downed at 0 HP: it can't be closed with ESC, only the
 * server closes it when the player stabilizes (3 successes, a natural 20, or someone revives them)
 * or actually dies (3 failures). See {@link net.hawthorn.dndsheets.DeathSaveManager}.</p>
 */
public class DeathSaveScreen extends ModalDialogScreen {
	private static final int WIDTH = 240;
	private static final int HEIGHT = 115;

	protected DeathSaveScreen() {
		super(Component.translatable("gui.dndsheets.death_save.title"), WIDTH, HEIGHT);
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new DeathSaveScreen());
	}

	public static void close() {
		if (Minecraft.getInstance().screen instanceof DeathSaveScreen) {
			Minecraft.getInstance().setScreen(null);
		}
	}

	@Override
	protected void init() {
		addModalButton(20, 60, WIDTH - 40, 20, Component.translatable("gui.dndsheets.death_save.roll"), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(new DeathSaveMessage(DeathSaveMessage.Kind.ROLL))
		);
		//Give up: for someone who doesn't want to keep rolling (a character whose arc is already done, a
		//session that needs to wrap up, etc.) — kills them for real, instantly, same path as 3 failed saves
		//(see DeathSaveManager.handleGiveUpRequest). No extra confirmation: a single click, same as rolling.
		addModalButton(20, 85, WIDTH - 40, 20, Component.translatable("gui.dndsheets.death_save.give_up").withStyle(ChatFormatting.DARK_RED), button ->
			DndsheetsMod.PACKET_HANDLER.sendToServer(new DeathSaveMessage(DeathSaveMessage.Kind.GIVE_UP))
		);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderPanel(guiGraphics);

		int top = dialogTop();
		guiGraphics.drawCenteredString(this.font, Component.translatable("gui.dndsheets.death_save.downed"), this.width / 2, top + 8, 0xFFFFFF);

		JsonObject sheet = SheetLoader.getClientSheet();
		int successes = sheet != null && sheet.has("deathSaveSuccesses") ? sheet.get("deathSaveSuccesses").getAsInt() : 0;
		int failures = sheet != null && sheet.has("deathSaveFailures") ? sheet.get("deathSaveFailures").getAsInt() : 0;

		guiGraphics.drawCenteredString(this.font, Component.translatable("gui.dndsheets.death_save.marks", marks(successes), marks(failures)), this.width / 2, top + 24, 0xAAAAAA);
		guiGraphics.drawCenteredString(this.font, Component.translatable("gui.dndsheets.death_save.revive_hint"), this.width / 2, top + 38, 0x888888);

		super.render(guiGraphics, mouseX, mouseY, partialTicks);
	}

	private static String marks(int count) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < 3; i++) builder.append(i < count ? "●" : "○");
		return builder.toString();
	}
}
