package net.hawthorn.dndsheets.dungeon.client.gui;
import net.minecraft.client.resources.language.I18n;
import net.hawthorn.dndsheets.client.gui.SmallFormScreen;

import net.hawthorn.dndsheets.dungeon.DndsheetsDungeonMod;
import net.hawthorn.dndsheets.dungeon.network.DungeonJigsawConfigureMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * <p>Configures a jigsaw block bypassing its vanilla GUI: the DM only picks which pool that exit
 * should connect to and whether this is the dungeon's starting piece — Name/Target are set
 * automatically ({@link net.hawthorn.dndsheets.dungeon.DungeonManager#configureJigsaw}), instead of
 * hand-typing the 3 exact strings with our namespace. Opened by right-clicking a jigsaw block with the
 * DM Wand (see {@link net.hawthorn.dndsheets.dungeon.DungeonToolManager}).</p>
 */
public class DungeonJigsawConfigureScreen extends SmallFormScreen {
	private final BlockPos pos;
	private final String initialPool;
	private final boolean initialIsStart;
	private EditBox poolBox;
	private CycleField isStart;

	private DungeonJigsawConfigureScreen(BlockPos pos, String currentPool, boolean currentIsStart, Screen parent) {
		super(Component.translatable("gui.dndsheets.dungeon_jigsaw.title"), 1, parent);
		this.pos = pos;
		this.initialPool = currentPool;
		this.initialIsStart = currentIsStart;
	}

	public static void open(BlockPos pos, String currentPool, boolean currentIsStart) {
		Minecraft.getInstance().setScreen(new DungeonJigsawConfigureScreen(pos, currentPool, currentIsStart, Minecraft.getInstance().screen));
	}

	@Override
	protected void buildForm() {
		poolBox = addField(I18n.get("gui.dndsheets.dungeon_jigsaw.pool"), initialPool, 32);
		String[] values = initialIsStart ? new String[]{"yes", "no"} : new String[]{"no", "yes"};
		isStart = addCycleButton(I18n.get("gui.dndsheets.dungeon_jigsaw.is_start"), values,
			new String[]{I18n.get("gui.dndsheets.common." + values[0]), I18n.get("gui.dndsheets.common." + values[1])});
	}

	@Override
	protected void onConfirm() {
		String pool = poolBox.getValue().trim();
		if (pool.isEmpty()) return;

		DndsheetsDungeonMod.PACKET_HANDLER.sendToServer(new DungeonJigsawConfigureMessage(pos, pool, "yes".equals(isStart.value())));
	}
}
