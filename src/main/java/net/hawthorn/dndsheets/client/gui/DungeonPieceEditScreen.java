package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.DungeonPieceRegistry;
import net.hawthorn.dndsheets.network.DungeonPieceUpdateMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Edita pool/peso/tags de una pieza ya capturada. Sin botón de borrar a propósito: es una acción rara
 * de operador y {@link SmallFormScreen#init()} es {@code final} (solo Confirmar/Cancelar) — para borrar
 * sigue haciendo falta {@code /dnddungeon piece remove}, igual que cualquier otra limpieza de {@code /dnd*}.</p>
 */
public class DungeonPieceEditScreen extends SmallFormScreen {
	//No se relee de DungeonPieceRegistry en el cliente: ese registro solo vive en memoria del servidor (ver
	//DungeonPieceRegistry), así que los valores para prellenar el formulario vienen del propio objeto que
	//ya mandó DungeonPieceListMessage, no de una relectura local que estaría siempre vacía.
	private final DungeonPieceRegistry.DungeonPiece piece;
	private EditBox poolBox, weightBox, tagsBox;

	private DungeonPieceEditScreen(DungeonPieceRegistry.DungeonPiece piece, Screen parent) {
		super(Component.literal("Editar pieza: " + piece.id()), 2, parent);
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
}
