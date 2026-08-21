package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.DungeonPieceRegistry;
import net.hawthorn.dndsheets.network.DungeonPieceRemoveMessage;
import net.hawthorn.dndsheets.network.DungeonPieceUpdateMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Edita pool/peso/tags de una pieza ya capturada, con un botón "Borrar pieza" propio (ver
 * {@link SmallFormScreen#showDeleteButton()}) — antes borrar vivía como una fila "Borrar: id" aparte en
 * {@link DungeonPieceListScreen}, que duplicaba el alto de la lista por cada pieza.</p>
 */
public class DungeonPieceEditScreen extends SmallFormScreen {
	//No se relee de DungeonPieceRegistry en el cliente: ese registro solo vive en memoria del servidor (ver
	//DungeonPieceRegistry), así que los valores para prellenar el formulario vienen del propio objeto que
	//ya mandó DungeonPieceListMessage, no de una relectura local que estaría siempre vacía.
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
		weightBox = addField("Peso (1-150)", String.valueOf(piece.weight()), 4);
		tagsBox = addField("Tags", piece.tags(), 64);
	}

	@Override
	protected void onConfirm() {
		String pool = poolBox.getValue().trim();
		if (pool.isEmpty()) return;

		int weight = parseIntOr(weightBox.getValue(), 1);
		DndsheetsMod.PACKET_HANDLER.sendToServer(new DungeonPieceUpdateMessage(piece.id(), pool, weight, tagsBox.getValue().trim()));
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
		DndsheetsMod.PACKET_HANDLER.sendToServer(new DungeonPieceRemoveMessage(piece.id()));
	}
}
