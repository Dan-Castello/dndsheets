package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Diario de campaña y handouts. La lista que llega ya viene filtrada por el servidor: aquí solo hay
 * entradas que este jugador puede leer, así que la pantalla no oculta nada — no le llegó.</p>
 *
 * <p>El texto se lee en el mismo diálogo que las fichas del compendio: los dos son «un texto largo con
 * un título», y una segunda pantalla para lo mismo solo sería otra que mantener.</p>
 */
public class JournalScreen extends ListPickerScreen {

	private final List<String> ids;
	private final List<String> labels;

	private JournalScreen(List<String> ids, List<String> labels) {
		super(Component.literal("Diario de campaña"));
		this.ids = ids;
		this.labels = labels;
	}

	public static void open(List<String> ids, List<String> labels) {
		Minecraft.getInstance().setScreen(new JournalScreen(ids, labels));
	}

	@Override
	protected boolean searchable() {
		return true; //Una campaña larga acumula entradas; buscar por título es lo que las hace consultables.
	}

	@Override
	protected int buttonWidth() {
		return 260;
	}

	@Override
	protected void buildRows() {
		for (int i = 0; i < ids.size(); i++) {
			String id = ids.get(i);
			addRow(Component.literal(labels.get(i)).withStyle(ChatFormatting.GRAY),
				b -> DndsheetsMod.PACKET_HANDLER.sendToServer(
					new BrowseActionMessage(BrowseActionMessage.Action.JOURNAL_DETAIL, id)));
		}
	}

	@Override
	protected Component emptyMessage() {
		return ids.isEmpty()
			? Component.literal("No hay nada en el diario todavía.")
			: null;
	}
}
