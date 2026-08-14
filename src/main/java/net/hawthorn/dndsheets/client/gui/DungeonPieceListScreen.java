package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DungeonManager;
import net.hawthorn.dndsheets.DungeonPieceRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Punto de entrada de las mazmorras desde el Panel de DM: lista las piezas ya capturadas (ver
 * {@link DungeonManager}) y da acceso a añadir una nueva o generar una mazmorra. La lista la manda el
 * servidor en {@code DungeonPieceListMessage} (pedida por {@code DungeonPieceListRequestMessage}) porque
 * el registro solo vive en memoria del servidor — mismo patrón que {@link TraitGrantScreen}/{@link PresetScreen}.</p>
 */
public class DungeonPieceListScreen extends ListPickerScreen {
	private static final int SUBTITLE_Y = 30;

	private final List<DungeonPieceRegistry.DungeonPiece> pieces;

	private DungeonPieceListScreen(List<DungeonPieceRegistry.DungeonPiece> pieces, Screen parent) {
		super(Component.literal("Mazmorras"), parent);
		this.pieces = pieces;
	}

	public static void open(List<DungeonPieceRegistry.DungeonPiece> pieces) {
		Minecraft.getInstance().setScreen(new DungeonPieceListScreen(pieces, Minecraft.getInstance().screen));
	}

	@Override
	protected int listTop() {
		return DungeonManager.structurizeAvailable() ? super.listTop() : SUBTITLE_Y + 14;
	}

	@Override
	protected void buildRows() {
		for (DungeonPieceRegistry.DungeonPiece piece : pieces) {
			addRow(Component.literal(piece.id() + " — " + piece.pool() + " (peso " + piece.weight() + ")"),
				b -> DungeonPieceEditScreen.open(piece));
		}
		addRow(Component.literal("+ Añadir pieza"), b -> DungeonPieceAddScreen.open());
		addRow(Component.literal("Generar mazmorra"), b -> DungeonGenerateScreen.open());
	}

	@Override
	protected Component emptyMessage() {
		return pieces.isEmpty() ? Component.literal("Sin piezas todavía. Escanea una sala con un bloque de estructura y añádela.") : null;
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
		if (!DungeonManager.structurizeAvailable()) {
			guiGraphics.drawCenteredString(this.font,
				Component.literal("Structurize + BlockUI no detectados — usando el flujo vanilla (bloque de estructura + jigsaw)."),
				this.width / 2, SUBTITLE_Y, GuiStyle.MUTED_COLOR);
		}
	}
}
