package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>The subclasses this character can choose <b>right now</b>. The list is sent by the server
 * ({@code BrowseListMessage.Kind.SUBCLASS}) because it depends on two things the client doesn't decide:
 * which preset is applied and what level the character is.</p>
 *
 * <p>An empty list isn't an error, it's the correct answer to "not yet": a level 1 fighter doesn't
 * choose an archetype until level 3. That's why the empty message states the reason instead of leaving
 * a blank panel, which is what gets reported as a bug.</p>
 */
public class SubclassScreen extends ListPickerScreen {

	private final List<String> ids;
	private final List<Component> labels;

	private SubclassScreen(List<String> ids, List<Component> labels) {
		super(Component.translatable("gui.dndsheets.subclass.title"), Minecraft.getInstance().screen);
		this.ids = ids;
		this.labels = labels;
	}

	public static void open(List<String> ids, List<Component> labels) {
		Minecraft.getInstance().setScreen(new SubclassScreen(ids, labels));
	}

	@Override
	protected void buildRows() {
		for (int i = 0; i < ids.size(); i++) {
			String subclassId = ids.get(i);
			addRow(labels.get(i), button -> {
				DndsheetsMod.PACKET_HANDLER.sendToServer(
					new BrowseActionMessage(BrowseActionMessage.Action.SUBCLASS_CHOOSE, subclassId));
				this.onClose();
			});
		}
	}

	@Override
	protected Component emptyMessage() {
		return ids.isEmpty()
			? Component.translatable("gui.dndsheets.subclass.empty")
			: null;
	}
}
