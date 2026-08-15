package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.CharacterOptionsRegistry;
import net.hawthorn.dndsheets.ContentType;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.ContentEntryListRequestMessage;
import net.hawthorn.dndsheets.network.OptionsListRequestMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>"Crear contenido" del Panel de DM: elige qué crear/editar/borrar sin escribir JSON a mano fuera del
 * juego — armas/hechizos/presets vía {@link ContentEntryListScreen}, raza/trasfondo/clase vía
 * {@link OptionsManageScreen}. Rasgos y monstruos todavía no están acá — rasgos necesitan un editor de
 * listas de nivel/dado propio, monstruos se crean capturando un NPC ya configurado (ver la nota pendiente
 * en {@code MonsterActionScreen}) — ambos quedan para una pasada siguiente.</p>
 */
public class ContentTypeListScreen extends ListPickerScreen {
	private ContentTypeListScreen(Screen parent) {
		super(Component.literal("Crear contenido"), parent);
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new ContentTypeListScreen(Minecraft.getInstance().screen));
	}

	@Override
	protected void buildRows() {
		addRow(Component.literal("Armas"), b -> request(ContentType.WEAPON));
		addRow(Component.literal("Hechizos"), b -> request(ContentType.SPELL));
		addRow(Component.literal("Presets de clase"), b -> request(ContentType.PRESET));
		addRow(Component.literal("Rasgos"), b -> request(ContentType.TRAIT));
		addRow(Component.literal("Razas"), b -> requestOptions(CharacterOptionsRegistry.RACE));
		addRow(Component.literal("Trasfondos"), b -> requestOptions(CharacterOptionsRegistry.BACKGROUND));
		addRow(Component.literal("Clases"), b -> requestOptions(CharacterOptionsRegistry.CLASS));
	}

	private static void request(ContentType type) {
		DndsheetsMod.PACKET_HANDLER.sendToServer(new ContentEntryListRequestMessage(type));
	}

	private static void requestOptions(String category) {
		DndsheetsMod.PACKET_HANDLER.sendToServer(new OptionsListRequestMessage(category));
	}
}
