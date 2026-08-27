package net.hawthorn.dndsheets.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Lista genérica cuyas filas disparan un comando de chat al pulsarlas. Existe para darle camino GUI
 * a los comandos del DM que ya funcionan por chat sin escribirle una pantalla a cada uno: la fila del
 * Panel de DM (o el servidor, vía {@code BrowseListMessage}) arma las parejas etiqueta→comando y esta
 * pantalla solo las pinta y las manda — el permiso y el efecto siguen viviendo donde siempre, en el
 * comando del lado del servidor.</p>
 *
 * <p>Se cierra al elegir: la respuesta del comando llega por chat, y dejarla abierta encima la taparía.</p>
 */
public class CommandListScreen extends ListPickerScreen {

	public record Row(Component label, String command) {}

	private final List<Row> rows;

	private CommandListScreen(Component title, List<Row> rows, Screen parent) {
		super(title, parent);
		this.rows = rows;
	}

	public static void open(Component title, List<Row> rows) {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.setScreen(new CommandListScreen(title, rows, minecraft.screen));
	}

	@Override
	protected void buildRows() {
		for (Row row : rows) {
			addRow(row.label(), button -> {
				Minecraft.getInstance().player.connection.sendCommand(row.command());
				Minecraft.getInstance().setScreen(null);
			});
		}
	}
}
