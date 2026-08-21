package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Las dotes cargadas, con una marca en las que este personaje ya tiene. Se llega desde la pantalla de
 * Mejora de Característica, porque una dote <b>gasta esa misma mejora</b>: son las dos caras de la misma
 * elección de 5e y por eso se eligen en el mismo sitio.</p>
 *
 * <p>La lista la manda el servidor, que es quien tiene el registro; que se pueda coger o no lo decide él
 * también al elegirla. Aquí no se apaga ninguna fila: una fila apagada sin explicación se lee como un
 * fallo, y el servidor sí puede decir por qué.</p>
 */
public class FeatScreen extends ListPickerScreen {

	private final List<String> ids;
	private final List<Component> labels;

	private FeatScreen(List<String> ids, List<Component> labels) {
		super(Component.translatable("gui.dndsheets.feat.title"), Minecraft.getInstance().screen);
		this.ids = ids;
		this.labels = labels;
	}

	public static void open(List<String> ids, List<Component> labels) {
		Minecraft.getInstance().setScreen(new FeatScreen(ids, labels));
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		for (int i = 0; i < ids.size(); i++) {
			String featId = ids.get(i);
			addRow(labels.get(i), button -> {
				DndsheetsMod.PACKET_HANDLER.sendToServer(
					new BrowseActionMessage(BrowseActionMessage.Action.FEAT_CHOOSE, featId));
				this.onClose();
			});
		}
	}

	@Override
	protected Component emptyMessage() {
		return ids.isEmpty()
			? Component.translatable("gui.dndsheets.feat.empty")
			: null;
	}
}
