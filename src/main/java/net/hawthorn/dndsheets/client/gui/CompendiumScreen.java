package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * <p>Entry point of the compendium: pick a category. With 779 entries imported from the SRD, until now
 * the only way to look up a spell or a stat block was to remember its id and type a command, which is
 * about as good as not having them.</p>
 *
 * <p>Not to be confused with the Grimoire, which shows the spells a character <em>knows</em>. This is
 * reference material: everything that's loaded, whether the viewer knows it or not.</p>
 */
public class CompendiumScreen extends ListPickerScreen {

	private CompendiumScreen() {
		super(Component.translatable("gui.dndsheets.compendium.title"));
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new CompendiumScreen());
	}

	@Override
	protected void buildRows() {
		addRow(Component.translatable("gui.dndsheets.compendium.spells"), b -> request("spells"));
		addRow(Component.translatable("gui.dndsheets.compendium.monsters"), b -> request("monsters"));
		addRow(Component.translatable("gui.dndsheets.compendium.items"), b -> request("items"));
		addRow(Component.translatable("gui.dndsheets.compendium.weapons"), b -> request("weapons"));
		//Traits go last because it's the shortest category, but it's the only one the player couldn't
		//check any other way: it also marks which ones they currently have (see CompendiumQuery).
		addRow(Component.translatable("gui.dndsheets.compendium.traits"), b -> request("traits"));
	}

	private static void request(String category) {
		DndsheetsMod.PACKET_HANDLER.sendToServer(
			new BrowseActionMessage(BrowseActionMessage.Action.LIST_CONTENT, category));
	}
}
