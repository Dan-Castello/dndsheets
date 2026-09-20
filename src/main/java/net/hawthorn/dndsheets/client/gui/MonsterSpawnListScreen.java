package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.MonsterSpawnMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

//List of loaded monsters to spawn at the DM's position (GUI equivalent of /dndmonsters spawn
//<id>) — opened from the DM Panel via BrowseActionMessage.SPAWN_MONSTERS. For a blank NPC, "Spawn
//generic NPC" (SpawnGenericScreen) still handles it, unchanged. The ids come resolved from the server
//(see BrowseListMessage kind SPAWN_MONSTER), not from the client registry: the registry only lives on the server, and a
//DM running as a separate process (invited over LAN) would always see it empty.
public class MonsterSpawnListScreen extends ListPickerScreen {
	private final List<String> ids;

	private MonsterSpawnListScreen(List<String> ids, Screen parent) {
		super(Component.translatable("gui.dndsheets.dm_panel.spawn_monster"), parent);
		this.ids = ids;
	}

	public static void open(List<String> ids) {
		Minecraft.getInstance().setScreen(new MonsterSpawnListScreen(ids, Minecraft.getInstance().screen));
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		for (String monsterId : ids) {
			addRow(Component.literal(monsterId), b -> {
				DndsheetsMod.PACKET_HANDLER.sendToServer(new MonsterSpawnMessage(monsterId));
				this.onClose();
			});
		}
	}

	@Override
	protected Component emptyMessage() {
		return ids.isEmpty() ? Component.translatable("gui.dndsheets.monster_spawn.empty") : null;
	}
}
