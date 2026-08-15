package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Las entradas de una categoría del compendio, con buscador. El buscador no es un adorno aquí: la
 * lista de monstruos tiene 330 filas, y sin filtrar no es consultable — es justo el caso para el que
 * {@link ListPickerScreen#searchable()} existe.</p>
 *
 * <p>Pulsar una fila pide su ficha completa al servidor en vez de traerla cargada con la lista: con 362
 * objetos, mandar todas las descripciones de golpe serían decenas de kilobytes en un solo paquete.</p>
 *
 * <p>Los ids llegan como {@code categoria|id} y se devuelven tal cual, así que esta pantalla no necesita
 * saber de qué registro salió cada entrada — solo pintarla.</p>
 */
public class CompendiumListScreen extends ListPickerScreen {

	private final List<String> ids;
	private final List<String> labels;

	private CompendiumListScreen(Component title, List<String> ids, List<String> labels, Screen parent) {
		super(title, parent);
		this.ids = ids;
		this.labels = labels;
	}

	public static void open(List<String> ids, List<String> labels) {
		String category = ids.isEmpty() ? "" : ids.get(0).split("[|]", 2)[0];
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.setScreen(new CompendiumListScreen(
			Component.literal("Compendio · " + category), ids, labels, minecraft.screen));
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected int buttonWidth() {
		return 260; //Cada fila lleva nombre y dos o tres datos; a 200px se cortaban.
	}

	@Override
	protected void buildRows() {
		for (int i = 0; i < ids.size(); i++) {
			String entryId = ids.get(i);
			addRow(Component.literal(labels.get(i)).withStyle(ChatFormatting.GRAY),
				b -> DndsheetsMod.PACKET_HANDLER.sendToServer(
					new BrowseActionMessage(BrowseActionMessage.Action.CONTENT_DETAIL, entryId)));
		}
	}

	@Override
	protected Component emptyMessage() {
		return ids.isEmpty() ? Component.literal("No hay contenido cargado de esa categoría.") : null;
	}
}
