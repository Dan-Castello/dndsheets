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
	private final List<Component> labels;
	/**
	 * <p>Modo borrar: hay que activarlo antes de que una fila borre nada. Es la confirmación, y va aquí en
	 * vez de en un diálogo por fila porque borrar ya deja una copia en disco ({@code .json.deleted}) — la
	 * protección que hace falta es que no se borre de un clic despistado, no una pregunta que se contesta
	 * que sí sin leerla.</p>
	 */
	private boolean deleteMode;

	private CharacterListScreen(List<String> ids, List<Component> labels) {
		super(Component.translatable("gui.dndsheets.character_list.title"));
		this.ids = ids;
		this.labels = labels;
	}

	public static void open(List<String> ids, List<Component> labels) {
		Minecraft.getInstance().setScreen(new CharacterListScreen(ids, labels));
	}

	//Deja sitio bajo la lista para las tres filas fijas: competencias, crear y borrar.
	@Override
	protected int listHeight() {
		return super.listHeight() - 3 * (BUTTON_HEIGHT + SPACING);
	}

	@Override
	protected void init() {
		super.init();
		int left = (this.width - buttonWidth()) / 2;
		int y = listTop() + listHeight() + SPACING;
		//Crear va ARRIBA de borrar y con el mismo aspecto que una fila normal: es la acción que más se usa
		//de las dos, y la destructiva no debería ser la que queda más a mano.
		this.addRenderableWidget(net.hawthorn.dndsheets.client.gui.components.TomeButton.of(
			Component.translatable("gui.dndsheets.character_list.new"), button -> NewCharacterScreen.open(),
			left, y, buttonWidth(), BUTTON_HEIGHT));
		y += BUTTON_HEIGHT + SPACING;
		//Configurar es de un personaje, así que vive donde se elige personaje. La hoja no puede abrirlo: es
		//una de las tres pantallas que sí son AbstractContainerScreen y su fila de botones ya está llena
		//(ver el punto 25 de PROJECT_CONTEXT.md), y esta pantalla se abre desde ella.
		this.addRenderableWidget(net.hawthorn.dndsheets.client.gui.components.TomeButton.of(
			Component.translatable("gui.dndsheets.character_list.setup"), button -> CharacterSetupScreen.open(this),
			left, y, buttonWidth(), BUTTON_HEIGHT));
		y += BUTTON_HEIGHT + SPACING;
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
			? Component.translatable("gui.dndsheets.character_list.delete_cancel").withStyle(ChatFormatting.RED)
			: Component.translatable("gui.dndsheets.character_list.delete").withStyle(ChatFormatting.GRAY);
	}

	@Override
	protected void buildRows() {
		for (int i = 0; i < ids.size(); i++) {
			String characterId = ids.get(i);
			//El personaje activo viene marcado con "▶" desde el servidor (ver BrowseActionMessage.listMine).
			//Se sigue mirando el texto ya resuelto porque la marca es un simbolo, no una palabra: no cambia
			//con el idioma.
			boolean active = labels.get(i).getString().startsWith("▶");
			ChatFormatting color = deleteMode ? ChatFormatting.RED : (active ? ChatFormatting.GREEN : ChatFormatting.GRAY);
			Component label = deleteMode
				? Component.translatable("gui.dndsheets.character_list.delete_row", labels.get(i))
				: labels.get(i).copy();
			addRow(label.copy().withStyle(color),
				button -> DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(
					deleteMode ? BrowseActionMessage.Action.DELETE : BrowseActionMessage.Action.SWITCH, characterId)));
		}
	}

	@Override
	protected Component emptyMessage() {
		//Un jugador siempre tiene al menos su hoja de siempre, así que esto solo se ve si algo fue mal
		//cargándolas — decirlo es más útil que una lista vacía sin explicación.
		return ids.isEmpty() ? Component.translatable("gui.dndsheets.character_list.empty") : null;
	}
}
