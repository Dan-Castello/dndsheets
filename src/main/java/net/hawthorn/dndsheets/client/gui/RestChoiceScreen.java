package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.RestProposeMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * <p>Se abre para quien usó el Kit de Descanso: elige corto o largo, lo que manda la propuesta
 * al resto de jugadores (ver {@link net.hawthorn.dndsheets.RestManager#propose}).</p>
 */
public class RestChoiceScreen extends ModalDialogScreen {
	private static final int WIDTH = 220;
	private static final int HEIGHT = 80;

	protected RestChoiceScreen() {
		super(Component.translatable("gui.dndsheets.rest_choice.title"), WIDTH, HEIGHT);
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new RestChoiceScreen());
	}

	@Override
	protected void init() {
		addModalButton(20, 30, WIDTH - 40, 20, Component.translatable("gui.dndsheets.rest_choice.short"), button -> {
			DndsheetsMod.PACKET_HANDLER.sendToServer(new RestProposeMessage(false));
			this.onClose();
		});

		addModalButton(20, 54, WIDTH - 40, 20, Component.translatable("gui.dndsheets.rest_choice.long"), button -> {
			DndsheetsMod.PACKET_HANDLER.sendToServer(new RestProposeMessage(true));
			this.onClose();
		});
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderPanel(guiGraphics);
		guiGraphics.drawCenteredString(this.font, Component.translatable("gui.dndsheets.rest_choice.prompt"), this.width / 2, dialogTop() + 8, 0xFFFFFF);
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
	}
}
