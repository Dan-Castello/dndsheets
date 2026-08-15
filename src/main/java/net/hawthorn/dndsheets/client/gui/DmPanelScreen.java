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
		super(Component.literal("Panel de DM"));
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new DmPanelScreen());
	}

	@Override
	protected void buildRows() {
		//Primera fila: es lo que un DM mira más veces por sesión, y hasta ahora había que abrir los Ajustes
		//de hoja de cada jugador por separado para ver sus PG.
		addRow(Component.literal("Grupo (PG, CA, condiciones)"),
			b -> DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(BrowseActionMessage.Action.LIST_PARTY)));
		addRow(Component.literal("Modo turnos"), b -> TurnControlScreen.open());
		addRow(Component.literal("Invocar NPC genérico"), b -> SpawnGenericScreen.open());
		addRow(Component.literal("Invocar monstruo cargado"), b -> MonsterSpawnListScreen.open());
		addRow(Component.literal("Conceder rasgo"), b -> PlayerPickerScreen.open("Elige a quién conceder el rasgo",
			uuid -> DndsheetsMod.PACKET_HANDLER.sendToServer(new TraitListRequestMessage(uuid))));
		addRow(Component.literal("Dar objeto"), b -> PlayerPickerScreen.open("Elige a quién dar el objeto", GiveItemListScreen::open));
		addRow(Component.literal("Dar arma"), b -> PlayerPickerScreen.open("Elige a quién dar el arma", WeaponGiveListScreen::open));
		addRow(Component.literal("Enseñar/dar hechizo"), b -> PlayerPickerScreen.open("Elige a quién enseñar/dar el hechizo", SpellGiveListScreen::open));
		addRow(Component.literal("Ajustes de hoja"), b -> PlayerPickerScreen.open("Elige a quién ajustar la hoja",
			uuid -> DndsheetsMod.PACKET_HANDLER.sendToServer(new SheetSummaryRequestMessage(uuid))));
		addRow(Component.literal("Aplicar preset a jugador"), b -> PlayerPickerScreen.open("Elige a quién aplicar el preset",
			uuid -> DndsheetsMod.PACKET_HANDLER.sendToServer(new PresetListRequestMessage(uuid))));
		addRow(Component.literal("Mazmorras (piezas y generación)"),
			b -> DndsheetsMod.PACKET_HANDLER.sendToServer(new DungeonPieceListRequestMessage()));
		addRow(Component.literal("Compendio"), b -> CompendiumScreen.open());
		addRow(Component.literal("Crear contenido"), b -> ContentTypeListScreen.open());
		addRow(Component.translatable("gui.dndsheets.guide.button"), b -> GuideBook.open(true));
	}
}
