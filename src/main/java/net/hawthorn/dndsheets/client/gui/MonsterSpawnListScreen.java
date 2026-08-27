package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.MonsterSpawnMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

//Lista de monstruos cargados para invocar en la posición del DM (equivalente en GUI a /dndmonsters spawn
//<id>) — abierta desde el Panel de DM vía BrowseActionMessage.SPAWN_MONSTERS. Para un NPC en blanco sigue
//estando "Invocar NPC genérico" (SpawnGenericScreen), sin cambios. Los ids vienen resueltos del servidor
//(ver BrowseListMessage kind SPAWN_MONSTER) y no del registro del cliente: el registro solo vive en el servidor, y un
//DM que sea un proceso aparte (invitado por LAN) lo vería siempre vacío.
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
