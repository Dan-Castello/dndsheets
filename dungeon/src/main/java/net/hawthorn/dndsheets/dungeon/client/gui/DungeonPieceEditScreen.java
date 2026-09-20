package net.hawthorn.dndsheets.dungeon.client.gui;
import net.hawthorn.dndsheets.client.gui.SmallFormScreen;

import net.hawthorn.dndsheets.dungeon.DndsheetsDungeonMod;
import net.hawthorn.dndsheets.dungeon.DungeonPieceRegistry;
import net.hawthorn.dndsheets.dungeon.network.DungeonPieceRemoveMessage;
import net.hawthorn.dndsheets.dungeon.network.DungeonPieceUpdateMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Edits pool/weight/tags of an already-captured piece, with its own "Delete piece" button (see
 * {@link SmallFormScreen#showDeleteButton()}) — deletion used to live as a separate "Delete: id" row in
 * {@link DungeonPieceListScreen}, which doubled the list's height for every piece.</p>
 */
public class DungeonPieceEditScreen extends SmallFormScreen {
	//Not re-read from DungeonPieceRegistry on the client: that registry only lives in the server's
	//memory (see DungeonPieceRegistry), so the values used to prefill the form come from the object
	//already sent by DungeonPieceListMessage, not from a local re-read that would always be empty.
	private final DungeonPieceRegistry.DungeonPiece piece;
	private EditBox poolBox, weightBox, tagsBox;

	private DungeonPieceEditScreen(DungeonPieceRegistry.DungeonPiece piece, Screen parent) {
		super(Component.translatable("gui.dndsheets.dungeon_piece_edit.title", piece.id()), 2, parent);
		this.piece = piece;
	}

	public static void open(DungeonPieceRegistry.DungeonPiece piece) {
		Minecraft.getInstance().setScreen(new DungeonPieceEditScreen(piece, Minecraft.getInstance().screen));
	}

	@Override
	protected void buildForm() {
		poolBox = addField("Pool", piece.pool(), 32);
		weightBox = addField(net.minecraft.client.resources.language.I18n.get("gui.dndsheets.form.weight_range"), String.valueOf(piece.weight()), 4);
		tagsBox = addField("Tags", piece.tags(), 64);
	}

	@Override
	protected void onConfirm() {
		String pool = poolBox.getValue().trim();
		if (pool.isEmpty()) return;

		int weight = parseIntOr(weightBox.getValue(), 1);
		DndsheetsDungeonMod.PACKET_HANDLER.sendToServer(new DungeonPieceUpdateMessage(piece.id(), pool, weight, tagsBox.getValue().trim()));
	}

	@Override
	protected boolean showDeleteButton() {
		return true;
	}

	@Override
	protected Component deleteButtonLabel() {
		return Component.translatable("gui.dndsheets.dungeon_piece_edit.delete");
	}

	@Override
	protected void onDelete() {
		DndsheetsDungeonMod.PACKET_HANDLER.sendToServer(new DungeonPieceRemoveMessage(piece.id()));
	}
}
