package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.MonsterRegistry;
import net.hawthorn.dndsheets.network.MonsterSpawnMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

//Lista de monstruos cargados para invocar en la posición del DM (equivalente en GUI a /dndmonsters spawn
//<id>) — abierta desde el Panel de DM. Para un NPC en blanco sigue estando "Invocar NPC genérico"
//(SpawnGenericScreen), sin cambios.
public class MonsterSpawnListScreen extends ListPickerScreen {
	private MonsterSpawnListScreen(Screen parent) {
		super(Component.translatable("gui.dndsheets.dm_panel.spawn_monster"), parent);
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new MonsterSpawnListScreen(Minecraft.getInstance().screen));
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		for (String monsterId : MonsterRegistry.ids()) {
			addRow(Component.literal(monsterId), b -> {
				DndsheetsMod.PACKET_HANDLER.sendToServer(new MonsterSpawnMessage(monsterId));
				this.onClose();
			});
		}
	}

	@Override
	protected Component emptyMessage() {
		return MonsterRegistry.ids().isEmpty() ? Component.translatable("gui.dndsheets.monster_spawn.empty") : null;
	}
}
