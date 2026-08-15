package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.Config;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.WeaponGiveMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

//Lista de armas cargadas para entregar a un jugador ya elegido (equivalente en GUI a /dndweapons give) —
//abierta desde el Panel de DM vía PlayerPickerScreen.open("...", uuid -> WeaponGiveListScreen.open(uuid)).
public class WeaponGiveListScreen extends ListPickerScreen {
	private final String targetUuid;

	private WeaponGiveListScreen(String targetUuid, Screen parent) {
		super(Component.literal("Dar arma"), parent);
		this.targetUuid = targetUuid;
	}

	public static void open(String targetUuid) {
		Minecraft.getInstance().setScreen(new WeaponGiveListScreen(targetUuid, Minecraft.getInstance().screen));
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		for (String weaponId : Config.loadedWeaponIds()) {
			addRow(Component.literal(weaponId), b -> {
				DndsheetsMod.PACKET_HANDLER.sendToServer(new WeaponGiveMessage(targetUuid, weaponId));
				this.onClose();
			});
		}
	}

	@Override
	protected Component emptyMessage() {
		return Config.loadedWeaponIds().isEmpty() ? Component.literal("No hay armas cargadas.") : null;
	}
}
