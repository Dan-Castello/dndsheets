package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.MonsterBindMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Choose which stat block to attach to the creature the DM just touched with the DM Wand. The
 * bestiary travels in the same {@link MonsterBindMessage} that opens this screen —resolved on the
 * server, which is where the registry actually lives— and isn't read from the client: a DM running as
 * a separate process (invited over LAN) would always see it empty.</p>
 *
 * <p>The only other thing that needs to travel here is WHO we're attaching it to, and that rides along
 * in the same message that later comes back with the response.</p>
 */
public class MonsterBindListScreen extends ListPickerScreen {

	private final int entityId;
	private final List<String> ids;

	private MonsterBindListScreen(int entityId, List<String> ids) {
		super(Component.translatable("gui.dndsheets.monster_bind.title"));
		this.entityId = entityId;
		this.ids = ids;
	}

	public static void open(int entityId, List<String> ids) {
		Minecraft.getInstance().setScreen(new MonsterBindListScreen(entityId, ids));
	}

	//330 SRD monsters: without a search box this is a list you scroll through until you give up.
	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		for (String monsterId : ids) {
			addRow(Component.literal(monsterId), b -> {
				DndsheetsMod.PACKET_HANDLER.sendToServer(new MonsterBindMessage(entityId, monsterId));
				this.onClose();
			});
		}
	}

	@Override
	protected Component emptyMessage() {
		return ids.isEmpty() ? Component.translatable("gui.dndsheets.monster_spawn.empty") : null;
	}
}
