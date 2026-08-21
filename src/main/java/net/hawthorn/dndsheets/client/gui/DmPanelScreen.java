package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.DungeonPieceListRequestMessage;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.hawthorn.dndsheets.network.PresetListRequestMessage;
import net.hawthorn.dndsheets.network.SheetSummaryRequestMessage;
import net.hawthorn.dndsheets.network.TraitListRequestMessage;
import net.minecraft.client.Minecraft;
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
	private DmPanelScreen() {
		super(Component.translatable("gui.dndsheets.dm_panel.title"));
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new DmPanelScreen());
	}

	@Override
	protected void buildRows() {
		//Primera fila: es lo que un DM mira más veces por sesión, y hasta ahora había que abrir los Ajustes
		//de hoja de cada jugador por separado para ver sus PG.
		addRow(Component.translatable("gui.dndsheets.dm_panel.party"),
			b -> DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(BrowseActionMessage.Action.LIST_PARTY)));
		addRow(Component.translatable("gui.dndsheets.dm_panel.turn_mode"), b -> TurnControlScreen.open());
		addRow(Component.translatable("gui.dndsheets.dm_panel.spawn_npc"), b -> SpawnGenericScreen.open());
		addRow(Component.translatable("gui.dndsheets.dm_panel.spawn_monster"), b -> MonsterSpawnListScreen.open());
		addRow(Component.translatable("gui.dndsheets.dm_panel.grant_trait"), b -> PlayerPickerScreen.open(Component.translatable("gui.dndsheets.dm_panel.pick_trait"),
			uuid -> DndsheetsMod.PACKET_HANDLER.sendToServer(new TraitListRequestMessage(uuid))));
		addRow(Component.translatable("gui.dndsheets.dm_panel.give_item"), b -> PlayerPickerScreen.open(Component.translatable("gui.dndsheets.dm_panel.pick_item"), GiveItemListScreen::open));
		addRow(Component.translatable("gui.dndsheets.dm_panel.give_weapon"), b -> PlayerPickerScreen.open(Component.translatable("gui.dndsheets.dm_panel.pick_weapon"), WeaponGiveListScreen::open));
		addRow(Component.translatable("gui.dndsheets.dm_panel.give_spell"), b -> PlayerPickerScreen.open(Component.translatable("gui.dndsheets.dm_panel.pick_spell"), SpellGiveListScreen::open));
		addRow(Component.translatable("gui.dndsheets.dm_panel.sheet_adjust"), b -> PlayerPickerScreen.open(Component.translatable("gui.dndsheets.dm_panel.pick_sheet"),
			uuid -> DndsheetsMod.PACKET_HANDLER.sendToServer(new SheetSummaryRequestMessage(uuid))));
		addRow(Component.translatable("gui.dndsheets.dm_panel.apply_preset"), b -> PlayerPickerScreen.open(Component.translatable("gui.dndsheets.dm_panel.pick_preset"),
			uuid -> DndsheetsMod.PACKET_HANDLER.sendToServer(new PresetListRequestMessage(uuid))));
		addRow(Component.translatable("gui.dndsheets.dm_panel.dungeons"),
			b -> DndsheetsMod.PACKET_HANDLER.sendToServer(new DungeonPieceListRequestMessage()));
		addRow(Component.translatable("gui.dndsheets.dm_panel.compendium"), b -> CompendiumScreen.open());
		//El diario se abre por comando (/dndjournal) y no desde aquí con un mensaje: el servidor ya sabe
		//qué puede leer cada uno, y pedirlo desde el cliente sería un viaje de más para el mismo resultado.
		addRow(Component.translatable("gui.dndsheets.dm_panel.journal"), b -> {
			net.minecraft.client.Minecraft.getInstance().player.connection.sendCommand("dndjournal");
		});
		addRow(Component.translatable("gui.dndsheets.dm_panel.create_content"), b -> ContentTypeListScreen.open());
		addRow(Component.translatable("gui.dndsheets.guide.button"), b -> GuideBook.open(true));
	}
}
