package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.RestProposeMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Se abre para quien usó el Kit de Descanso: elige corto o largo, lo que manda la propuesta
 * al resto de jugadores (ver {@link net.hawthorn.dndsheets.RestManager#propose}).</p>
 */
public class RestChoiceScreen extends Screen {
	private static final int WIDTH = 220;
	private static final int HEIGHT = 80;

	protected RestChoiceScreen() {
		super(Component.literal("Proponer Descanso"));
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new RestChoiceScreen());
	}

	@Override
	protected void init() {
		int left = (this.width - WIDTH) / 2;
		int top = (this.height - HEIGHT) / 2;

		this.addRenderableWidget(Button.builder(Component.literal("Descanso corto"), button -> {
			DndsheetsMod.PACKET_HANDLER.sendToServer(new RestProposeMessage(false));
			this.onClose();
		}).bounds(left + 20, top + 30, WIDTH - 40, 20).build());

		this.addRenderableWidget(Button.builder(Component.literal("Descanso largo"), button -> {
			DndsheetsMod.PACKET_HANDLER.sendToServer(new RestProposeMessage(true));
			this.onClose();
		}).bounds(left + 20, top + 54, WIDTH - 40, 20).build());
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		this.renderBackground(guiGraphics);
		int top = (this.height - HEIGHT) / 2;
		guiGraphics.drawCenteredString(this.font, Component.literal("¿Qué tipo de descanso propones?"), this.width / 2, top + 8, 0xFFFFFF);
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
	}
}
