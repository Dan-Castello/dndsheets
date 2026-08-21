package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Las subclases que este personaje puede elegir <b>ahora</b>. La lista la manda el servidor
 * ({@code BrowseListMessage.Kind.SUBCLASS}) porque depende de dos cosas que el cliente no decide: qué
 * preset lleva puesto y qué nivel tiene.</p>
 *
 * <p>La lista vacía no es un error, es la respuesta correcta a "todavía no": un guerrero de nivel 1 no
 * elige arquetipo hasta el 3. Por eso el mensaje de vacío dice el porqué en vez de dejar un panel en
 * blanco, que es lo que se reporta como fallo.</p>
 */
public class SubclassScreen extends ListPickerScreen {

	private final List<String> ids;
	private final List<String> labels;

	private SubclassScreen(List<String> ids, List<String> labels) {
		super(Component.translatable("gui.dndsheets.subclass.title"), Minecraft.getInstance().screen);
		this.ids = ids;
		this.labels = labels;
	}

	public static void open(List<String> ids, List<String> labels) {
		Minecraft.getInstance().setScreen(new SubclassScreen(ids, labels));
	}

	@Override
	protected void buildRows() {
		for (int i = 0; i < ids.size(); i++) {
			String subclassId = ids.get(i);
			addRow(Component.literal(labels.get(i)), button -> {
				DndsheetsMod.PACKET_HANDLER.sendToServer(
					new BrowseActionMessage(BrowseActionMessage.Action.SUBCLASS_CHOOSE, subclassId));
				this.onClose();
			});
		}
	}

	@Override
	protected Component emptyMessage() {
		return ids.isEmpty()
			? Component.translatable("gui.dndsheets.subclass.empty")
			: null;
	}
}
