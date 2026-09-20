package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.GiveableItem;
import net.hawthorn.dndsheets.network.GiveItemMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

//List of "fixed" items that can be given to a player already chosen (see GiveableItem) — opened from the
//DM Panel via PlayerPickerScreen.open("...", uuid -> GiveItemListScreen.open(uuid)).
public class GiveItemListScreen extends ListPickerScreen {
	private final String targetUuid;

	private GiveItemListScreen(String targetUuid, Screen parent) {
		super(Component.translatable("gui.dndsheets.dm_panel.give_item"), parent);
		this.targetUuid = targetUuid;
	}

	public static void open(String targetUuid) {
		Minecraft.getInstance().setScreen(new GiveItemListScreen(targetUuid, Minecraft.getInstance().screen));
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		for (GiveableItem item : GiveableItem.values()) {
			addRow(net.hawthorn.dndsheets.ContentNames.of(item.label()), b -> {
				DndsheetsMod.PACKET_HANDLER.sendToServer(new GiveItemMessage(item, targetUuid));
				this.onClose();
			});
		}
	}
}
