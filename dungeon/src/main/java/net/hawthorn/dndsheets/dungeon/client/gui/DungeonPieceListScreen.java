package net.hawthorn.dndsheets.dungeon.client.gui;
import javax.annotation.Nullable;
import net.hawthorn.dndsheets.client.gui.GuiStyle;
import net.hawthorn.dndsheets.client.gui.ListPickerScreen;

import net.hawthorn.dndsheets.dungeon.DungeonManager;
import net.hawthorn.dndsheets.dungeon.DungeonPieceRegistry;
import net.minecraft.ChatFormatting;
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
	private static final int NOTICE_HEIGHT = 12;

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

	//Room for the "no Structurize" notice above the search box (it used to sit on top of it).
	@Override
	protected int contentTop() {
		return DungeonManager.structurizeAvailable() ? super.contentTop() : super.contentTop() + NOTICE_HEIGHT;
	}

	@Override
	protected void buildRows() {
		//Actions first: at the bottom of a long list they were the rows nobody found.
		addRow(Component.translatable("gui.dndsheets.dungeon_pieces.add"), b -> DungeonPieceAddScreen.open());
		addRow(Component.translatable("gui.dndsheets.dungeon_pieces.generate"), b -> DungeonGenerateScreen.open());
		//Draw a room and paste it straight into the world, with no id or pool: not every room is a dungeon piece.
		addRow(Component.translatable("gui.dndsheets.dungeon_pieces.draw_paste"), b -> DungeonTraceScreen.open("", "", "", 1, "", this));
		//A whole dungeon from an online generator, opened in the same editor to change it before pasting.
		addRow(Component.translatable("gui.dndsheets.dungeon_pieces.import_map"), b -> DungeonMapPickScreen.open(this));
		//Grouped by pool under a header: a dungeon is generated from ONE pool, so that's how the DM reads them.
		List<Integer> order = new java.util.ArrayList<>();
		for (int i = 0; i < pieces.size(); i++) order.add(i);
		order.sort(java.util.Comparator.comparing((Integer i) -> pieces.get(i).pool()).thenComparing(i -> pieces.get(i).id()));
		String lastPool = null;
		for (int i : order) {
			DungeonPieceRegistry.DungeonPiece piece = pieces.get(i);
			if (!piece.pool().equals(lastPool)) {
				addHeader(Component.translatable("gui.dndsheets.dungeon_pieces.pool_header", piece.pool()));
				lastPool = piece.pool();
			}
			//The entry piece stands out in green, the weight goes dimmed: the id is what the DM looks for.
			Component row = Component.literal(piece.id()) //i18n-ok: DM-made id
				.append(Component.translatable("gui.dndsheets.dungeon_pieces.weight", piece.weight()).withStyle(ChatFormatting.DARK_GRAY));
			if (hasStart.get(i)) row = row.copy().append(Component.translatable("gui.dndsheets.dungeon_pieces.start_tag").withStyle(ChatFormatting.GREEN));
			//Deletion lives in DungeonPieceEditScreen (its own button) instead of a separate row here.
			addRow(row, b -> DungeonPieceEditScreen.open(piece));
		}
	}

	@Nullable
	@Override
	protected Component emptyMessage() {
		return pieces.isEmpty() ? Component.translatable("gui.dndsheets.dungeon_pieces.empty") : null;
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		super.render(guiGraphics, mouseX, mouseY, partialTicks);
		if (!DungeonManager.structurizeAvailable()) {
			Component notice = Component.translatable("gui.dndsheets.dungeon_pieces.no_structurize");
			int y = super.contentTop();
			guiGraphics.drawCenteredString(this.font, GuiStyle.fit(this.font, notice, buttonWidth()), listLeft() + buttonWidth() / 2, y, GuiStyle.MUTED_COLOR);
			if (this.font.width(notice) > buttonWidth() && mouseY >= y - 1 && mouseY < y + 9
				&& mouseX >= listLeft() && mouseX < listLeft() + buttonWidth()) guiGraphics.renderTooltip(this.font, notice, mouseX, mouseY);
		}
	}
}
