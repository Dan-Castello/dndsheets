package net.hawthorn.dndsheets.client.gui;

import javax.annotation.Nullable;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.SpellGiveMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

//List of loaded spells, two rows each (GUI equivalent of /dndspells learn and /dndspells
//staff), for an already-chosen player — opened via BrowseActionMessage.GIVE_SPELLS/BrowseListMessage. The
//ids come resolved from the server, not from the client registry: the registry only lives on the server, and
//a DM running as a separate process (invited over LAN) would always see it empty.
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

	@Nullable
	@Override
	protected Component emptyMessage() {
		return ids.isEmpty() ? Component.translatable("gui.dndsheets.spell_give.empty") : null;
	}
}
