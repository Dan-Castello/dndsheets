package net.hawthorn.dndsheets.client.gui;

import javax.annotation.Nullable;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>The entries of a compendium category, with a search box. The search box isn't decoration here: the
 * monster list has 330 rows, and without filtering it isn't browsable — this is exactly the case
 * {@link ListPickerScreen#searchable()} exists for.</p>
 *
 * <p>Clicking a row requests its full sheet from the server instead of having it preloaded with the list:
 * with 362 items, sending all the descriptions at once would be tens of kilobytes in a single packet.</p>
 *
 * <p>Ids arrive as {@code category|id} and are returned as-is, so this screen doesn't need to know which
 * registry each entry came from — it just has to render it.</p>
 */
public class CompendiumListScreen extends ListPickerScreen {

	private final List<String> ids;
	private final List<Component> labels;

	private CompendiumListScreen(Component title, List<String> ids, List<Component> labels, Screen parent) {
		super(title, parent);
		this.ids = ids;
		this.labels = labels;
	}

	public static void open(List<String> ids, List<Component> labels) {
		String category = ids.isEmpty() ? "" : ids.get(0).split("[|]", 2)[0];
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.setScreen(new CompendiumListScreen(
			Component.translatable("gui.dndsheets.compendium.category", category), ids, labels, minecraft.screen));
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected int buttonWidth() {
		return 260; //Each row carries a name and two or three data points; at 200px they got cut off.
	}

	@Override
	protected void buildRows() {
		for (int i = 0; i < ids.size(); i++) {
			String entryId = ids.get(i);
			addRow(labels.get(i).copy().withStyle(ChatFormatting.GRAY),
				b -> DndsheetsMod.PACKET_HANDLER.sendToServer(
					new BrowseActionMessage(BrowseActionMessage.Action.CONTENT_DETAIL, entryId)));
		}
	}

	@Nullable
	@Override
	protected Component emptyMessage() {
		return ids.isEmpty() ? Component.translatable("gui.dndsheets.compendium.empty") : null;
	}
}
