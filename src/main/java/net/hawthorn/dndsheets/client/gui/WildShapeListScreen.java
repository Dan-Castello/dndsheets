package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.WildShapeMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>What beast the druid turns into. Rows aren't built by reading the bestiary on the client
 * —the in-memory registry only lives on the server, and a client running as a separate process (anyone
 * other than whoever opened the world) would always see it empty— but instead from the data already
 * carried by the {@code OPEN_PICKER} message itself from {@code WildShapeWatcher.openPicker}.</p>
 *
 * <p>Each row shows HP and AC, which is the only thing that actually matters when choosing a form — a wolf and a
 * brown bear aren't told apart by name when what you're deciding is whether you survive the next turn.</p>
 */
public class WildShapeListScreen extends ListPickerScreen {

	private final List<String> beastIds;
	private final List<String> beastNames;
	private final List<Integer> beastHps;
	private final List<Integer> beastAcs;

	private WildShapeListScreen(List<String> beastIds, List<String> beastNames, List<Integer> beastHps, List<Integer> beastAcs) {
		super(Component.translatable("gui.dndsheets.wildshape.title"));
		this.beastIds = beastIds;
		this.beastNames = beastNames;
		this.beastHps = beastHps;
		this.beastAcs = beastAcs;
	}

	public static void open(List<String> beastIds, List<String> beastNames, List<Integer> beastHps, List<Integer> beastAcs) {
		Minecraft.getInstance().setScreen(new WildShapeListScreen(beastIds, beastNames, beastHps, beastAcs));
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	//Name + HP + AC doesn't fit the standard width without getting cut off, same issue the party list had.
	@Override
	protected int buttonWidth() {
		return 260;
	}

	@Override
	protected void buildRows() {
		for (int i = 0; i < beastIds.size(); i++) {
			String beastId = beastIds.get(i);
			addRow(Component.translatable("gui.dndsheets.wildshape.row", net.hawthorn.dndsheets.ContentNames.of(beastNames.get(i)), beastHps.get(i), beastAcs.get(i)),
				b -> {
					DndsheetsMod.PACKET_HANDLER.sendToServer(
						new WildShapeMessage(WildShapeMessage.Kind.CHOOSE, Minecraft.getInstance().player.getUUID(), beastId));
					this.onClose();
				});
		}
	}

	@Override
	protected Component emptyMessage() {
		return beastIds.isEmpty() ? Component.translatable("gui.dndsheets.wildshape.empty") : null;
	}
}
