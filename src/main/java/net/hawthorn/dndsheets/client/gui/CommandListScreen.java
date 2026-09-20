package net.hawthorn.dndsheets.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Generic list whose rows fire a chat command when clicked. It exists to give a GUI path to DM
 * commands that already work over chat, without writing a dedicated screen for each one: the DM Panel
 * row (or the server, via {@code BrowseListMessage}) assembles the label→command pairs and this screen
 * just renders them and sends them — the permission check and the effect still live where they always
 * have, in the server-side command.</p>
 *
 * <p>Closes on selection: the command's response arrives via chat, and leaving this open on top of it
 * would cover it.</p>
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
