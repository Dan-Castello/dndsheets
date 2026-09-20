package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.TurnActionManager;
import net.hawthorn.dndsheets.network.TurnActionMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * <p>The three turn actions that aren't attacking or casting a spell, for whoever uses the Turn
 * Actions item. Each button states what the action DOES, not just its name: "Dodge" alone tells
 * someone who's never played D&amp;D nothing, and this mod is played mostly by people who haven't.</p>
 */
public class TurnActionScreen extends ModalDialogScreen {
	private static final int WIDTH = 260;
	private static final int HEIGHT = 104;

	protected TurnActionScreen() {
		super(Component.translatable("gui.dndsheets.turn_action.title"), WIDTH, HEIGHT);
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new TurnActionScreen());
	}

	@Override
	protected void init() {
		addAction(30, TurnActionManager.TurnAction.DODGE, "gui.dndsheets.turn_action.dodge");
		addAction(54, TurnActionManager.TurnAction.DASH, "gui.dndsheets.turn_action.dash");
		addAction(78, TurnActionManager.TurnAction.DISENGAGE, "gui.dndsheets.turn_action.disengage");
	}

	private void addAction(int y, TurnActionManager.TurnAction action, String labelKey) {
		addModalButton(20, y, WIDTH - 40, 20, Component.translatable(labelKey), button -> {
			DndsheetsMod.PACKET_HANDLER.sendToServer(new TurnActionMessage(action));
			this.onClose();
		});
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderPanel(guiGraphics);
		guiGraphics.drawCenteredString(this.font, Component.translatable("gui.dndsheets.turn_action.prompt"), this.width / 2, dialogTop() + 8, 0xFFFFFF);
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
	}
}
