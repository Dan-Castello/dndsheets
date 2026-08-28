package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>El espejo de {@link DmPanelScreen} para quien juega. Existe porque el lado del jugador era el
 * único sin puerta: el DM tenía diecisiete acciones en un panel con buscador, y el jugador cuatro
 * botones al pie de la ficha — todo lo demás suyo (mejora de característica, diario, compendio,
 * objetos mágicos, registro de tiradas) solo se abría tecleando el comando, que es tanto como no
 * existir para quien no se los sabe.</p>
 *
 * <p>Ninguna fila estrena red ni pantalla: cada una dispara el mensaje o el comando que ya había
 * detrás del atajo correspondiente. Las secciones y el buscador automático a partir de catorce filas
 * los pone {@link ListPickerScreen} solo.</p>
 */
public class PlayerPanelScreen extends ListPickerScreen {
	private PlayerPanelScreen(Screen parent) {
		super(Component.translatable("gui.dndsheets.player_panel.title"), parent);
	}

	public static void open(Screen parent) {
		Minecraft.getInstance().setScreen(new PlayerPanelScreen(parent));
	}

	@Override
	protected void buildRows() {
		addHeader(Component.translatable("gui.dndsheets.player_panel.section_character"));
		addRow(Component.translatable("gui.dndsheets.character_sheet.characters"),
			b -> DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(BrowseActionMessage.Action.LIST_MINE)));
		//Raza, clase, trasfondo, subclase y competencias en un sitio. Estaba a dos clics de aquí
		//(Personajes -> Configurar), o sea escondido detrás de una lista que se abre para otra cosa.
		addRow(Component.translatable("gui.dndsheets.player_panel.setup"), b -> CharacterSetupScreen.open(this));
		addRow(Component.translatable("gui.dndsheets.character_sheet.presets"), b -> PresetActionMenuScreen.open(this));
		//La Mejora de Característica la elige quien lleva el personaje (ver CharacterCommand "mejora"):
		//el comando no pide permiso y el servidor ya comprueba que quedara alguna pendiente.
		addRow(Component.translatable("gui.dndsheets.player_panel.improvement"), b -> command("dndchar mejora"));

		addHeader(Component.translatable("gui.dndsheets.player_panel.section_magic"));
		addRow(Component.translatable("gui.dndsheets.character_sheet.grimoire"), b -> GrimoireScreen.open(this));

		addHeader(Component.translatable("gui.dndsheets.player_panel.section_table"));
		addRow(Component.translatable("gui.dndsheets.dm_panel.journal"), b -> command("dndjournal"));
		addRow(Component.translatable("gui.dndsheets.dm_panel.compendium"), b -> CompendiumScreen.open());
		//Sale por chat, no en pantalla: /dnditems no tiene GUI propia. Aun así entra, porque sintonizar
		//objetos es del jugador y hasta ahora no había forma de descubrir que la mecánica existía.
		addRow(Component.translatable("gui.dndsheets.player_panel.items"), b -> command("dnditems list"));
		addRow(Component.translatable("gui.dndsheets.dm_panel.roll_log"), b -> command("dndrolls"));
		addRow(Component.translatable("gui.dndsheets.guide.button"),
			b -> GuideBook.open(this.minecraft.player != null && this.minecraft.player.hasPermissions(2)));

		//Sin gatear por permisos, por el mismo motivo que la tecla del Panel de DM (ver
		//DndsheetsModKeyMappings.DM_PANEL): el cliente no sabe si el modo solo está encendido, y quien
		//dirige en solo no es operador. Abrirlo no concede nada — cada acción de dentro la gatea el servidor.
		addHeader(Component.translatable("gui.dndsheets.player_panel.section_dm"));
		addRow(Component.translatable("gui.dndsheets.dm_panel.title"), b -> DmPanelScreen.open(this));
	}

	private static void command(String command) {
		Minecraft.getInstance().player.connection.sendCommand(command);
		Minecraft.getInstance().setScreen(null);
	}
}
