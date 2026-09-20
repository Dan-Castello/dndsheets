package net.hawthorn.dndsheets.client.gui;

import javax.annotation.Nullable;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.TraitGrantMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Final step of granting a trait from the DM Panel: the list (ids + names) is sent by the
 * server in {@code BrowseListMessage} kind GRANT_TRAIT (requested with {@code BrowseActionMessage.GRANT_TRAITS} after picking the
 * target player in {@link PlayerPickerScreen}), because the trait registry only lives in the server's
 * memory — same pattern as {@link PresetScreen}.</p>
 */
public class TraitGrantScreen extends ListPickerScreen {
	private final String targetUuid;
	private final List<String> ids;
	private final List<String> names;

	private TraitGrantScreen(String targetUuid, List<String> ids, List<String> names, Screen parent) {
		super(Component.translatable("gui.dndsheets.trait_grant.title"), parent);
		this.targetUuid = targetUuid;
		this.ids = ids;
		this.names = names;
	}

	public static void open(String targetUuid, List<String> ids, List<String> names) {
		Minecraft.getInstance().setScreen(new TraitGrantScreen(targetUuid, ids, names, Minecraft.getInstance().screen));
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		for (int i = 0; i < names.size(); i++) {
			String traitId = ids.get(i);
			addRow(net.hawthorn.dndsheets.ContentNames.of(names.get(i)), b -> {
				DndsheetsMod.PACKET_HANDLER.sendToServer(new TraitGrantMessage(targetUuid, traitId));
				this.onClose();
			});
		}
	}

	@Nullable
	@Override
	protected Component emptyMessage() {
		return names.isEmpty() ? Component.translatable("gui.dndsheets.trait_grant.empty") : null;
	}
}
