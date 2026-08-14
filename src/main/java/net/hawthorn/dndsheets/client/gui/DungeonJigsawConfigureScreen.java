package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.DungeonJigsawConfigureMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * <p>Configura un jigsaw block sin pasar por su GUI vanilla: el DM solo elige a qué pool debería tirar
 * esa salida y si esta es la pieza de arranque de la mazmorra — Name/Target se fijan solos
 * ({@link net.hawthorn.dndsheets.DungeonManager#configureJigsaw}), en vez de tipear a mano los 3 strings
 * exactos con nuestro namespace. Se abre con clic derecho sobre un jigsaw block usando la Vara de DM
 * (ver {@link net.hawthorn.dndsheets.DungeonToolManager}).</p>
 */
public class DungeonJigsawConfigureScreen extends SmallFormScreen {
	private final BlockPos pos;
	private final String initialPool;
	private final boolean initialIsStart;
	private EditBox poolBox;
	private CycleField isStart;

	private DungeonJigsawConfigureScreen(BlockPos pos, String currentPool, boolean currentIsStart, Screen parent) {
		super(Component.literal("Configurar jigsaw"), 1, parent);
		this.pos = pos;
		this.initialPool = currentPool;
		this.initialIsStart = currentIsStart;
	}

	public static void open(BlockPos pos, String currentPool, boolean currentIsStart) {
		Minecraft.getInstance().setScreen(new DungeonJigsawConfigureScreen(pos, currentPool, currentIsStart, Minecraft.getInstance().screen));
	}

	@Override
	protected void buildForm() {
		poolBox = addField("Pool destino", initialPool, 32);
		isStart = addCycleButton("Pieza de inicio", initialIsStart ? new String[]{"Sí", "No"} : new String[]{"No", "Sí"});
	}

	@Override
	protected void onConfirm() {
		String pool = poolBox.getValue().trim();
		if (pool.isEmpty()) return;

		DndsheetsMod.PACKET_HANDLER.sendToServer(new DungeonJigsawConfigureMessage(pos, pool, "Sí".equals(isStart.value())));
	}
}
