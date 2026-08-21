package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.DungeonGenerateMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * <p>Publica los pools y dispara la generación jigsaw en la posición pedida (ver
 * {@link net.hawthorn.dndsheets.DungeonManager#generate}). X/Y/Z se prellenan con la posición actual del
 * jugador, igual que otros formularios de este mod prellenan con el valor vigente en vez de dejarlo en blanco.</p>
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
		maxDepthBox = addField("Profundidad máx. (1-7)", "7", 2);
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

		DndsheetsMod.PACKET_HANDLER.sendToServer(new DungeonGenerateMessage(pool, maxDepth, new BlockPos(x, y, z)));
	}
}
