package net.hawthorn.dndsheets.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * <p>"Are you sure?" for every deletion in the mod's screens. One entry point, {@link #ask}: it shows what is
 * about to be deleted, and only the red button runs the action — Escape, Cancel or clicking away do nothing.</p>
 *
 * <p>One confirmation at a time and one run per confirmation: a second {@code ask} while a dialog is open is
 * ignored, and the confirm button locks itself on the first click, so a double click can't send the delete twice.</p>
 */
public class ConfirmScreen extends ModalDialogScreen {
	private static final int WIDTH = 240;
	private static final int HEIGHT = 96;
	private static boolean open = false;

	private final Screen parent;
	private final Component what;
	private final Runnable action;
	private boolean done = false;

	private ConfirmScreen(Component what, Runnable action, Screen parent) {
		super(Component.translatable("gui.dndsheets.confirm.title"), WIDTH, HEIGHT);
		this.what = what;
		this.action = action;
		this.parent = parent;
	}

	/** Asks before deleting {@code what}; {@code action} only runs if confirmed, after returning to the screen that asked. */
	public static void ask(Component what, Runnable action) {
		if (open) return;
		open = true;
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.setScreen(new ConfirmScreen(what, action, minecraft.screen));
	}

	@Override
	protected void init() {
		Button yes = addModalButton(16, HEIGHT - 32, WIDTH / 2 - 20, 20,
			Component.translatable("gui.dndsheets.confirm.yes").withStyle(net.minecraft.ChatFormatting.RED), b -> {
				if (done) return;
				done = true;
				close();
				action.run();
			});
		yes.active = !done;
		addModalButton(WIDTH / 2 + 4, HEIGHT - 32, WIDTH / 2 - 20, 20, Component.translatable("gui.dndsheets.confirm.no"), b -> onClose());
	}

	private void close() {
		open = false;
		Minecraft.getInstance().setScreen(parent);
	}

	@Override
	public void onClose() {
		close();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderPanel(guiGraphics);
		guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, dialogTop() + 8, GuiStyle.TITLE_COLOR);
		List<FormattedCharSequence> lines = this.font.split(Component.translatable("gui.dndsheets.confirm.body", what), WIDTH - 24);
		int y = dialogTop() + 26;
		for (FormattedCharSequence line : lines) {
			guiGraphics.drawCenteredString(this.font, line, this.width / 2, y, 0xFFFFFF);
			y += 10;
		}
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
	}
}
