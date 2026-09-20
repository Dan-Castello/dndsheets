package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Campaign journal and handouts. The list that arrives is already filtered by the server: only
 * entries this player is allowed to read show up here, so the screen isn't hiding anything — it simply
 * never received it.</p>
 *
 * <p>The text is read in the same dialog as compendium entries: both are "a long piece of text with a
 * title", and a second screen for the same thing would just be one more to maintain.</p>
 */
public class JournalScreen extends ListPickerScreen {

	private final List<String> ids;
	private final List<Component> labels;

	private JournalScreen(List<String> ids, List<Component> labels) {
		super(Component.translatable("gui.dndsheets.journal.title"));
		this.ids = ids;
		this.labels = labels;
	}

	public static void open(List<String> ids, List<Component> labels) {
		Minecraft.getInstance().setScreen(new JournalScreen(ids, labels));
	}

	@Override
	protected boolean searchable() {
		return true; //A long campaign accumulates entries; searching by title is what keeps them navigable.
	}

	@Override
	protected int buttonWidth() {
		return 260;
	}

	@Override
	protected void buildRows() {
		for (int i = 0; i < ids.size(); i++) {
			String id = ids.get(i);
			addRow(labels.get(i).copy().withStyle(ChatFormatting.GRAY),
				b -> DndsheetsMod.PACKET_HANDLER.sendToServer(
					new BrowseActionMessage(BrowseActionMessage.Action.JOURNAL_DETAIL, id)));
		}
	}

	@Override
	protected Component emptyMessage() {
		return ids.isEmpty()
			? Component.translatable("gui.dndsheets.journal.empty")
			: null;
	}
}
