package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.SpellGiveMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

//Lista de hechizos cargados, dos filas cada uno (equivalente en GUI a /dndspells learn y /dndspells
//staff), para un jugador ya elegido — abierta vía SpellGiveListRequestMessage/SpellGiveListMessage. Los
//ids vienen resueltos del servidor y no del registro del cliente: el registro solo vive en el servidor, y
//un DM que sea un proceso aparte (invitado por LAN) lo vería siempre vacío.
public class SpellGiveListScreen extends ListPickerScreen {
	private final String targetUuid;
	private final List<String> ids;

	private SpellGiveListScreen(String targetUuid, List<String> ids, Screen parent) {
		super(Component.translatable("gui.dndsheets.spell_give.title"), parent);
		this.targetUuid = targetUuid;
		this.ids = ids;
	}

	public static void open(String targetUuid, List<String> ids) {
		Minecraft.getInstance().setScreen(new SpellGiveListScreen(targetUuid, ids, Minecraft.getInstance().screen));
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		for (String spellId : ids) {
			addRow(Component.translatable("gui.dndsheets.spell_give.learn", spellId), b -> send(spellId, false));
			addRow(Component.translatable("gui.dndsheets.spell_give.staff", spellId), b -> send(spellId, true));
		}
	}

	private void send(String spellId, boolean asStaff) {
		DndsheetsMod.PACKET_HANDLER.sendToServer(new SpellGiveMessage(targetUuid, spellId, asStaff));
		this.onClose();
	}

	@Override
	protected Component emptyMessage() {
		return ids.isEmpty() ? Component.translatable("gui.dndsheets.spell_give.empty") : null;
	}
}
