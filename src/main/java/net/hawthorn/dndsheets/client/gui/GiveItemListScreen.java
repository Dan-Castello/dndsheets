package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.GiveableItem;
import net.hawthorn.dndsheets.network.GiveItemMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

//Lista de ítems "fijos" entregables a un jugador ya elegido (ver GiveableItem) — abierta desde el Panel de
//DM vía PlayerPickerScreen.open("...", uuid -> GiveItemListScreen.open(uuid)).
public class GiveItemListScreen extends ListPickerScreen {
	private final String targetUuid;

	private GiveItemListScreen(String targetUuid, Screen parent) {
		super(Component.literal("Dar objeto"), parent);
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
			addRow(Component.literal(item.label()), b -> {
				DndsheetsMod.PACKET_HANDLER.sendToServer(new GiveItemMessage(item, targetUuid));
				this.onClose();
			});
		}
	}
}
