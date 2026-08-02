package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.RestVoteResponseMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Se abre para todos al recibir una propuesta de descanso (ver {@link net.hawthorn.dndsheets.RestManager#propose}).
 * Aceptar o rechazar manda la respuesta; el descanso solo se aplica si TODOS aceptan.</p>
 */
public class RestVoteScreen extends Screen {
	private static final int WIDTH = 240;
	private static final int HEIGHT = 90;

	private final String proposerName;
	private final String typeLabel;

	protected RestVoteScreen(String proposerName, String typeLabel) {
		super(Component.literal("Votación de Descanso"));
		this.proposerName = proposerName;
		this.typeLabel = typeLabel;
	}

	public static void open(String proposerName, String typeLabel) {
		Minecraft.getInstance().setScreen(new RestVoteScreen(proposerName, typeLabel));
	}

	public static void close() {
		if (Minecraft.getInstance().screen instanceof RestVoteScreen) {
			Minecraft.getInstance().setScreen(null);
		}
	}

	@Override
	protected void init() {
		int left = (this.width - WIDTH) / 2;
		int top = (this.height - HEIGHT) / 2;

		this.addRenderableWidget(Button.builder(Component.literal("Aceptar"), button -> {
			DndsheetsMod.PACKET_HANDLER.sendToServer(new RestVoteResponseMessage(true));
			this.onClose();
		}).bounds(left + 20, top + 60, (WIDTH - 50) / 2, 20).build());

		this.addRenderableWidget(Button.builder(Component.literal("Rechazar"), button -> {
			DndsheetsMod.PACKET_HANDLER.sendToServer(new RestVoteResponseMessage(false));
			this.onClose();
		}).bounds(left + 30 + (WIDTH - 50) / 2, top + 60, (WIDTH - 50) / 2, 20).build());
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderBackground(guiGraphics);
		int top = (this.height - HEIGHT) / 2;
		guiGraphics.drawCenteredString(this.font, Component.literal(proposerName + " propone un descanso " + typeLabel + "."), this.width / 2, top + 8, 0xFFFFFF);
		guiGraphics.drawCenteredString(this.font, Component.literal("Se aplicará solo si todos aceptan."), this.width / 2, top + 22, 0xAAAAAA);
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
	}
}
