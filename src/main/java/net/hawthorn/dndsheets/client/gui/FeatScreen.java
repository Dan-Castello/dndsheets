package net.hawthorn.dndsheets.client.gui;

import javax.annotation.Nullable;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>The loaded feats, with a mark on the ones this character already has. Reached from the Ability
 * Score Improvement screen, because a feat <b>spends that same improvement</b>: they're two faces of
 * the same 5e choice and that's why they're picked in the same place.</p>
 *
 * <p>The server sends the list, since it holds the registry; whether a feat can be taken or not is also
 * decided by the server when it's picked. No row is grayed out here: a grayed-out row with no explanation
 * reads as a bug, and the server can actually say why.</p>
 */
public class FeatScreen extends ListPickerScreen {

	private final List<String> ids;
	private final List<Component> labels;

	private FeatScreen(List<String> ids, List<Component> labels) {
		super(Component.translatable("gui.dndsheets.feat.title"), Minecraft.getInstance().screen);
		this.ids = ids;
		this.labels = labels;
	}

	public static void open(List<String> ids, List<Component> labels) {
		Minecraft.getInstance().setScreen(new FeatScreen(ids, labels));
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		for (int i = 0; i < ids.size(); i++) {
			String featId = ids.get(i);
			addRow(labels.get(i), button -> {
				DndsheetsMod.PACKET_HANDLER.sendToServer(
					new BrowseActionMessage(BrowseActionMessage.Action.FEAT_CHOOSE, featId));
				this.onClose();
			});
		}
	}

	@Nullable
	@Override
	protected Component emptyMessage() {
		return ids.isEmpty()
			? Component.translatable("gui.dndsheets.feat.empty")
			: null;
	}
}
