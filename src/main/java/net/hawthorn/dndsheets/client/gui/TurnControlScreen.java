package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.TurnControlMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * <p>Control de modo turnos desde el Panel de DM (equivalente en GUI a {@code /dndturns
 * start|next|cancel|end}). "Iniciar" siempre usa el radio por defecto ({@link
 * net.hawthorn.dndsheets.command.TurnCommand#DEFAULT_RADIUS}) — para un radio distinto sigue haciendo
 * falta el comando.</p>
 */
public class TurnControlScreen extends ListPickerScreen {
	private static final String[] ACTIONS = {"start", "next", "cancel", "end"};
	private static final String[] LABELS = {"Iniciar turnos", "Siguiente turno", "Saltar (cancelar)", "Terminar turnos"};

	private TurnControlScreen() {
		super(Component.literal("Modo turnos"));
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new TurnControlScreen());
	}

	@Override
	protected void buildRows() {
		for (int i = 0; i < ACTIONS.length; i++) {
			String action = ACTIONS[i];
			addRow(Component.literal(LABELS[i]), b -> {
				DndsheetsMod.PACKET_HANDLER.sendToServer(new TurnControlMessage(action));
				this.onClose();
			});
		}

		//Ver AUDIT_UX.md, DM #4: aplicar un efecto de estado (veneno, etc.) solo existía como
		///dndturns effect tecleado a mano, sin GUI para elegir dado/duración.
		addRow(Component.literal("Aplicar efecto"), b ->
			PlayerPickerScreen.open("Elige a quién aplicar el efecto", AddTurnEffectScreen::open));
	}
}
