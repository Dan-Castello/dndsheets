package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Los personajes de un jugador, con el que lleva puesto marcado; pulsar uno cambia a él. Se abre con
 * {@code /dndchar} sin argumentos, que es un punto de entrada sin riesgo de layout — la hoja de personaje
 * es una de las tres pantallas que sí son {@code AbstractContainerScreen} y meterle un botón más es una
 * operación bastante más cara que un comando.</p>
 *
 * <p>No se reconstruye en local al pulsar: el servidor manda la lista otra vez ya con la marca movida,
 * porque es él quien decide si el cambio valía (el personaje tiene que ser tuyo). Repintar aquí una
 * marca que el servidor podría rechazar es exactamente cómo se enseña un estado que no existe.</p>
 */
public class CharacterListScreen extends ListPickerScreen {

	private final List<String> ids;
	private final List<String> labels;
	/**
	 * <p>Modo borrar: hay que activarlo antes de que una fila borre nada. Es la confirmación, y va aquí en
	 * vez de en un diálogo por fila porque borrar ya deja una copia en disco ({@code .json.deleted}) — la
	 * protección que hace falta es que no se borre de un clic despistado, no una pregunta que se contesta
	 * que sí sin leerla.</p>
	 */
	private boolean deleteMode;

	private CharacterListScreen(List<String> ids, List<String> labels) {
		super(Component.literal("Tus personajes"));
		this.ids = ids;
		this.labels = labels;
	}

	public static void open(List<String> ids, List<String> labels) {
		Minecraft.getInstance().setScreen(new CharacterListScreen(ids, labels));
	}

	//Deja sitio bajo la lista para el interruptor de borrar, que no se desplaza con los personajes.
	@Override
	protected int listHeight() {
		return super.listHeight() - BUTTON_HEIGHT - SPACING;
	}

	@Override
	protected void init() {
		super.init();
		int left = (this.width - buttonWidth()) / 2;
		int y = listTop() + listHeight() + SPACING;
		net.minecraft.client.gui.components.Button toggle = this.addRenderableWidget(
			net.hawthorn.dndsheets.client.gui.components.TomeButton.of(deleteLabel(), button -> {
				deleteMode = !deleteMode;
				//Se reconstruye la pantalla entera: las filas cambian de color y de acción, y repintar solo
				//el interruptor dejaría botones que hacen una cosa distinta de la que dicen.
				this.rebuildWidgets();
			}, left, y, buttonWidth(), BUTTON_HEIGHT));
		toggle.setMessage(deleteLabel());
	}

	private Component deleteLabel() {
		return deleteMode
			? Component.literal("Cancelar (elige uno para borrarlo)").withStyle(ChatFormatting.RED)
			: Component.literal("Borrar un personaje...").withStyle(ChatFormatting.GRAY);
	}

	@Override
	protected void buildRows() {
		for (int i = 0; i < ids.size(); i++) {
			String characterId = ids.get(i);
			boolean active = labels.get(i).startsWith("▶");
			ChatFormatting color = deleteMode ? ChatFormatting.RED : (active ? ChatFormatting.GREEN : ChatFormatting.GRAY);
			String label = deleteMode ? "Borrar: " + labels.get(i) : labels.get(i);
			addRow(Component.literal(label).withStyle(color),
				button -> DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(
					deleteMode ? BrowseActionMessage.Action.DELETE : BrowseActionMessage.Action.SWITCH, characterId)));
		}
	}

	@Override
	protected Component emptyMessage() {
		//Un jugador siempre tiene al menos su hoja de siempre, así que esto solo se ve si algo fue mal
		//cargándolas — decirlo es más útil que una lista vacía sin explicación.
		return ids.isEmpty() ? Component.literal("No se encontró ningún personaje. Crea uno con /dndchar new <nombre>.") : null;
	}
}
