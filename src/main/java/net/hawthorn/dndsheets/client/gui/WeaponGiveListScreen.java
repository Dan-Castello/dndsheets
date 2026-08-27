package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.WeaponGiveMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

//Lista de armas cargadas para entregar a un jugador ya elegido (equivalente en GUI a /dndweapons give) —
//abierta vía BrowseActionMessage.GIVE_WEAPONS/BrowseListMessage. Los ids vienen resueltos del servidor y
//no del registro del cliente: el registro solo vive en el servidor, y un DM que sea un proceso aparte
//(invitado por LAN) lo vería siempre vacío.
public class WeaponGiveListScreen extends ListPickerScreen {
	private final String targetUuid;
	private final List<String> ids;

	private WeaponGiveListScreen(String targetUuid, List<String> ids, Screen parent) {
		super(Component.translatable("gui.dndsheets.dm_panel.give_weapon"), parent);
		this.targetUuid = targetUuid;
		this.ids = ids;
	}

	public static void open(String targetUuid, List<String> ids) {
		Minecraft.getInstance().setScreen(new WeaponGiveListScreen(targetUuid, ids, Minecraft.getInstance().screen));
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		for (String weaponId : ids) {
			addRow(Component.literal(weaponId), b -> {
				DndsheetsMod.PACKET_HANDLER.sendToServer(new WeaponGiveMessage(targetUuid, weaponId));
				this.onClose();
			});
		}
	}

	@Override
	protected Component emptyMessage() {
		return ids.isEmpty() ? Component.translatable("gui.dndsheets.weapon_give.empty") : null;
	}
}
