package net.hawthorn.dndsheets.dungeon.client.gui;
import net.minecraft.client.resources.language.I18n;
import net.hawthorn.dndsheets.client.gui.SmallFormScreen;

import net.hawthorn.dndsheets.dungeon.DndsheetsDungeonMod;
import net.hawthorn.dndsheets.dungeon.network.DungeonGenerateMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * <p>Publishes the pools and fires off jigsaw generation at the requested position (see
 * {@link net.hawthorn.dndsheets.dungeon.DungeonManager#generate}). X/Y/Z are prefilled with the player's
 * current position, the same way other forms in this mod prefill with the current value instead of leaving it blank.</p>
 */
public class DungeonGenerateScreen extends SmallFormScreen {
	private EditBox poolBox, maxDepthBox, xBox, yBox, zBox;

	private DungeonGenerateScreen(Screen parent) {
		super(Component.translatable("gui.dndsheets.dungeon_pieces.generate"), 3, parent);
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new DungeonGenerateScreen(Minecraft.getInstance().screen));
	}

	@Override
	protected void buildForm() {
		BlockPos pos = Minecraft.getInstance().player.blockPosition();
		poolBox = addField("Pool", "", 32);
		maxDepthBox = addField(I18n.get("gui.dndsheets.dungeon_generate.max_depth"), "7", 2);
		xBox = addField("X", String.valueOf(pos.getX()), 8);
		yBox = addField("Y", String.valueOf(pos.getY()), 8);
		zBox = addField("Z", String.valueOf(pos.getZ()), 8);
	}

	@Override
	protected void onConfirm() {
		String pool = poolBox.getValue().trim();
		if (pool.isEmpty()) return;

		BlockPos here = Minecraft.getInstance().player.blockPosition();
		int maxDepth = parseIntOr(maxDepthBox.getValue(), 7);
		int x = parseIntOr(xBox.getValue(), here.getX());
		int y = parseIntOr(yBox.getValue(), here.getY());
		int z = parseIntOr(zBox.getValue(), here.getZ());

		DndsheetsDungeonMod.PACKET_HANDLER.sendToServer(new DungeonGenerateMessage(pool, maxDepth, new BlockPos(x, y, z)));
	}
}
