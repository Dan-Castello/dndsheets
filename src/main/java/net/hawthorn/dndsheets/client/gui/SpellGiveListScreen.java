package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SpellRegistry;
import net.hawthorn.dndsheets.network.SpellGiveMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

//Lista de hechizos cargados, dos filas cada uno (equivalente en GUI a /dndspells learn y /dndspells
//staff), para un jugador ya elegido — abierta vía PlayerPickerScreen.open("...", uuid -> SpellGiveListScreen.open(uuid)).
public class SpellGiveListScreen extends ListPickerScreen {
	private final String targetUuid;

	private SpellGiveListScreen(String targetUuid, Screen parent) {
		super(Component.literal("Enseñar / dar hechizo"), parent);
		this.targetUuid = targetUuid;
	}

	public static void open(String targetUuid) {
		Minecraft.getInstance().setScreen(new SpellGiveListScreen(targetUuid, Minecraft.getInstance().screen));
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		for (String spellId : SpellRegistry.ids()) {
			addRow(Component.literal("Aprender: " + spellId), b -> send(spellId, false));
			addRow(Component.literal("Báculo: " + spellId), b -> send(spellId, true));
		}
	}

	private void send(String spellId, boolean asStaff) {
		DndsheetsMod.PACKET_HANDLER.sendToServer(new SpellGiveMessage(targetUuid, spellId, asStaff));
		this.onClose();
	}

	@Override
	protected Component emptyMessage() {
		return SpellRegistry.ids().isEmpty() ? Component.literal("No hay hechizos cargados.") : null;
	}
}
