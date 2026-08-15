package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * <p>Punto de entrada del compendio: elige categoría. Con 779 entradas importadas del SRD, hasta ahora
 * la única forma de mirar un hechizo o un bloque de estadísticas era recordar su id y escribir un
 * comando, que es tanto como no tenerlas.</p>
 *
 * <p>No confundir con el Grimorio, que muestra los hechizos que un personaje <em>conoce</em>. Esto es
 * material de referencia: todo lo cargado, lo sepa quien lo mire o no.</p>
 */
public class CompendiumScreen extends ListPickerScreen {

	private CompendiumScreen() {
		super(Component.literal("Compendio"));
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new CompendiumScreen());
	}

	@Override
	protected void buildRows() {
		addRow(Component.literal("Hechizos"), b -> request("spells"));
		addRow(Component.literal("Monstruos"), b -> request("monsters"));
		addRow(Component.literal("Objetos mágicos"), b -> request("items"));
		addRow(Component.literal("Armas"), b -> request("weapons"));
	}

	private static void request(String category) {
		DndsheetsMod.PACKET_HANDLER.sendToServer(
			new BrowseActionMessage(BrowseActionMessage.Action.LIST_CONTENT, category));
	}
}
