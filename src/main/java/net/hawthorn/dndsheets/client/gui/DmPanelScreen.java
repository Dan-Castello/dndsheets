package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.hawthorn.dndsheets.network.SheetSummaryRequestMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Punto de entrada del DM a todo lo que antes solo eran comandos: turnos, invocar un NPC en blanco,
 * conceder un rasgo. Se abre con la tecla de acceso rápido (ver
 * {@link net.hawthorn.dndsheets.init.DndsheetsModKeyMappings#DM_PANEL}), que ya comprueba permisos de
 * operador antes de abrir esto — dar/quitar ataques a un monstruo concreto sigue viviendo en su propio
 * menú (clic derecho con la Vara de DM, ver {@link MonsterActionScreen}), porque ese ya necesita el
 * monstruo señalado y no tiene sentido pedirlo aparte aquí.</p>
 */
public class DmPanelScreen extends ListPickerScreen {
	private DmPanelScreen(Screen parent) {
		super(Component.translatable("gui.dndsheets.dm_panel.title"), parent);
	}

	/** Desde la tecla de acceso rápido: pantalla raíz, Escape cierra el menú. */
	public static void open() {
		open(null);
	}

	/** Desde el Menú del jugador (ver {@link PlayerPanelScreen}): "&lt; Atrás" vuelve allí. */
	public static void open(Screen parent) {
		Minecraft.getInstance().setScreen(new DmPanelScreen(parent));
	}

	//Cinco secciones con cabecera (ver ListPickerScreen.addHeader) en vez de 17 filas planas: con tantas
	//acciones, "todo junto por orden de llegada" obligaba a leer la lista entera para encontrar una.
	//El orden de las secciones es el de frecuencia en sesión: primero lo que se mira cada combate.
	@Override
	protected void buildRows() {
		addHeader(Component.translatable("gui.dndsheets.dm_panel.section_party"));
		//Es lo que un DM mira más veces por sesión, y hasta ahora había que abrir los Ajustes de hoja de
		//cada jugador por separado para ver sus PG.
		addRow(Component.translatable("gui.dndsheets.dm_panel.party"),
			b -> DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(BrowseActionMessage.Action.LIST_PARTY)));
		addRow(Component.translatable("gui.dndsheets.dm_panel.turn_mode"), b -> TurnControlScreen.open());

		addHeader(Component.translatable("gui.dndsheets.dm_panel.section_spawn"));
		addRow(Component.translatable("gui.dndsheets.dm_panel.spawn_npc"), b -> SpawnGenericScreen.open());
		addRow(Component.translatable("gui.dndsheets.dm_panel.spawn_monster"),
			b -> send(BrowseActionMessage.Action.SPAWN_MONSTERS, ""));
		//Encuentros completos ("goblin x4, lobo x2"): la lista viene del servidor y cada fila dispara el
		///dndencounters spawn de siempre — antes solo existía tecleado a mano.
		addRow(Component.translatable("gui.dndsheets.dm_panel.encounters"),
			b -> send(BrowseActionMessage.Action.LIST_ENCOUNTERS, ""));
		//Armar uno nuevo viendo la dificultad que le sale al grupo, en vez de escribir "goblin x4" a ciegas
		//en el creador de contenido — ver EncounterDesignerScreen.
		addRow(Component.translatable("gui.dndsheets.dm_panel.design_encounter"),
			b -> send(BrowseActionMessage.Action.DESIGN_ENCOUNTER, ""));

		addHeader(Component.translatable("gui.dndsheets.dm_panel.section_give"));
		addRow(Component.translatable("gui.dndsheets.dm_panel.grant_trait"), b -> PlayerPickerScreen.open(Component.translatable("gui.dndsheets.dm_panel.pick_trait"),
			uuid -> send(BrowseActionMessage.Action.GRANT_TRAITS, uuid)));
		addRow(Component.translatable("gui.dndsheets.dm_panel.give_item"), b -> PlayerPickerScreen.open(Component.translatable("gui.dndsheets.dm_panel.pick_item"), GiveItemListScreen::open));
		addRow(Component.translatable("gui.dndsheets.dm_panel.give_weapon"), b -> PlayerPickerScreen.open(Component.translatable("gui.dndsheets.dm_panel.pick_weapon"),
			uuid -> send(BrowseActionMessage.Action.GIVE_WEAPONS, uuid)));
		addRow(Component.translatable("gui.dndsheets.dm_panel.give_spell"), b -> PlayerPickerScreen.open(Component.translatable("gui.dndsheets.dm_panel.pick_spell"),
			uuid -> send(BrowseActionMessage.Action.GIVE_SPELLS, uuid)));
		addRow(Component.translatable("gui.dndsheets.dm_panel.sheet_adjust"), b -> PlayerPickerScreen.open(Component.translatable("gui.dndsheets.dm_panel.pick_sheet"),
			uuid -> DndsheetsMod.PACKET_HANDLER.sendToServer(new SheetSummaryRequestMessage(uuid))));
		addRow(Component.translatable("gui.dndsheets.dm_panel.apply_preset"), b -> PlayerPickerScreen.open(Component.translatable("gui.dndsheets.dm_panel.pick_preset"),
			uuid -> send(BrowseActionMessage.Action.LIST_PRESETS, uuid)));

		addHeader(Component.translatable("gui.dndsheets.dm_panel.section_world"));
		//El toolkit de mazmorras vive en su propio addon (dndsheets_dungeon) desde PROJECT_CONTEXT.md /
		//Modularity Map: este panel ya no conoce sus mensajes de red, solo dispara su comando — mismo
		//patrón que "journal" más abajo. Si el addon no está instalado, Brigadier ya rechaza el comando.
		addRow(Component.translatable("gui.dndsheets.dm_panel.dungeons"),
			b -> Minecraft.getInstance().player.connection.sendCommand("dnddungeon gui"));
		//La dificultad de monstruos son tres opciones fijas que ya viven en /dnddifficulty: la lista se
		//arma en el cliente (no hay nada que preguntarle al servidor) y el clic manda el comando.
		addRow(Component.translatable("gui.dndsheets.dm_panel.difficulty"), b -> CommandListScreen.open(
			Component.translatable("gui.dndsheets.dm_panel.difficulty"),
			java.util.List.of(
				new CommandListScreen.Row(Component.translatable("gui.dndsheets.difficulty.facil"), "dnddifficulty facil"),
				new CommandListScreen.Row(Component.translatable("gui.dndsheets.difficulty.normal"), "dnddifficulty normal"),
				new CommandListScreen.Row(Component.translatable("gui.dndsheets.difficulty.dificil"), "dnddifficulty dificil"))));
		//El registro de tiradas ya se imprime bonito por chat; la fila solo evita tener que saberse el comando.
		addRow(Component.translatable("gui.dndsheets.dm_panel.roll_log"), b -> {
			Minecraft.getInstance().player.connection.sendCommand("dndrolls");
			Minecraft.getInstance().setScreen(null);
		});
		//El diario se abre por comando (/dndjournal) y no desde aquí con un mensaje: el servidor ya sabe
		//qué puede leer cada uno, y pedirlo desde el cliente sería un viaje de más para el mismo resultado.
		addRow(Component.translatable("gui.dndsheets.dm_panel.journal"), b -> {
			net.minecraft.client.Minecraft.getInstance().player.connection.sendCommand("dndjournal");
		});
		addRow(Component.translatable("gui.dndsheets.dm_panel.compendium"), b -> CompendiumScreen.open());

		addHeader(Component.translatable("gui.dndsheets.dm_panel.section_content"));
		addRow(Component.translatable("gui.dndsheets.dm_panel.create_content"), b -> ContentTypeListScreen.open());
		addRow(Component.translatable("gui.dndsheets.guide.button"), b -> GuideBook.open(true));
	}

	private static void send(BrowseActionMessage.Action action, String argument) {
		DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(action, argument));
	}
}
