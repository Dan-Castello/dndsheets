package net.hawthorn.dndsheets.dungeon.client.gui;
import net.hawthorn.dndsheets.client.gui.GuiStyle;
import net.hawthorn.dndsheets.client.gui.ListPickerScreen;

import net.hawthorn.dndsheets.dungeon.DungeonManager;
import net.hawthorn.dndsheets.dungeon.DungeonPieceRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Entry point for dungeons from the DM Panel: lists the already-captured pieces (see
 * {@link DungeonManager}) and gives access to adding a new one or generating a dungeon. The list is
 * sent by the server in {@code DungeonPieceListMessage} (requested via
 * {@code DungeonPieceListRequestMessage}) because the registry only lives in the server's memory —
 * same pattern as {@link TraitGrantScreen}/{@link PresetScreen}.</p>
 */
public class DungeonPieceListScreen extends ListPickerScreen {
	private static final int SUBTITLE_Y = 30;

	private final List<DungeonPieceRegistry.DungeonPiece> pieces;
	//DungeonManager.hasStartJigsaw per piece, same order as "pieces" — see DungeonPieceListMessage. Lets
	//you see at a glance which pieces have the starting jigsaw, so the real problem behind intermittent
	//failures — mixing the entry piece with regular pieces in the same pool — can be spotted BEFORE generating.
	private final List<Boolean> hasStart;

	private DungeonPieceListScreen(List<DungeonPieceRegistry.DungeonPiece> pieces, List<Boolean> hasStart, Screen parent) {
		super(Component.translatable("gui.dndsheets.dungeon_pieces.title"), parent);
		this.pieces = pieces;
		this.hasStart = hasStart;
	}

	public static void open(List<DungeonPieceRegistry.DungeonPiece> pieces, List<Boolean> hasStart) {
		Minecraft.getInstance().setScreen(new DungeonPieceListScreen(pieces, hasStart, Minecraft.getInstance().screen));
	}

	@Override
	protected int listTop() {
		return DungeonManager.structurizeAvailable() ? super.listTop() : SUBTITLE_Y + 14;
	}

	@Override
	protected void buildRows() {
		for (int i = 0; i < pieces.size(); i++) {
			DungeonPieceRegistry.DungeonPiece piece = pieces.get(i);
			Component suffix = hasStart.get(i) ? Component.translatable("gui.dndsheets.dungeon_pieces.start_tag") : Component.empty();
			//Deletion lives in DungeonPieceEditScreen (its own button) instead of a separate row here.
			addRow(Component.translatable("gui.dndsheets.dungeon_pieces.row", piece.id(), piece.pool(), piece.weight(), suffix),
				b -> DungeonPieceEditScreen.open(piece));
		}
		addRow(Component.translatable("gui.dndsheets.dungeon_pieces.add"), b -> DungeonPieceAddScreen.open());
		addRow(Component.translatable("gui.dndsheets.dungeon_pieces.generate"), b -> DungeonGenerateScreen.open());
	}

	@Override
	protected Component emptyMessage() {
		return pieces.isEmpty() ? Component.translatable("gui.dndsheets.dungeon_pieces.empty") : null;
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
		if (!DungeonManager.structurizeAvailable()) {
			guiGraphics.drawCenteredString(this.font,
				Component.translatable("gui.dndsheets.dungeon_pieces.no_structurize"),
				this.width / 2, SUBTITLE_Y, GuiStyle.MUTED_COLOR);
		}
	}
}
