package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.TurnControlMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Control de modo turnos desde el Panel de DM (equivalente en GUI a {@code /dndturns
 * start|next|cancel|end}). "Iniciar" siempre usa el radio por defecto ({@link
 * net.hawthorn.dndsheets.command.TurnCommand#DEFAULT_RADIUS}) — para un radio distinto sigue haciendo
 * falta el comando.</p>
 */
public class TurnControlScreen extends Screen {
	private static final int BUTTON_WIDTH = 200;
	private static final int BUTTON_HEIGHT = 20;
	private static final int SPACING = 4;
	private static final String[] ACTIONS = {"start", "next", "cancel", "end"};
	private static final String[] LABELS = {"Iniciar turnos", "Siguiente turno", "Saltar (cancelar)", "Terminar turnos"};

	private TurnControlScreen() {
		super(Component.literal("Modo turnos"));
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new TurnControlScreen());
	}

	@Override
	protected void init() {
		int totalHeight = ACTIONS.length * (BUTTON_HEIGHT + SPACING);
		int startY = (this.height - totalHeight) / 2;

		for (int i = 0; i < ACTIONS.length; i++) {
			String action = ACTIONS[i];
			this.addRenderableWidget(Button.builder(Component.literal(LABELS[i]), button -> {
				DndsheetsMod.PACKET_HANDLER.sendToServer(new TurnControlMessage(action));
				this.onClose();
			}).bounds((this.width - BUTTON_WIDTH) / 2, startY + i * (BUTTON_HEIGHT + SPACING), BUTTON_WIDTH, BUTTON_HEIGHT).build());
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
