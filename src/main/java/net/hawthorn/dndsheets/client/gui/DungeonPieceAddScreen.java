package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.DungeonPieceCaptureMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Captura una pieza de mazmorra nueva: el DM ya la escaneó en el mundo con un bloque de estructura
 * (modo SAVE) bajo el id que escribe en "Estructura" — este formulario solo copia ese .nbt al datapack
 * de la partida y la registra (ver {@link net.hawthorn.dndsheets.DungeonManager#capturePiece}).</p>
 *
 * <p>"Estructura" e "Id" se pueden prellenar (ver {@link #open(String, String)}) — clic derecho con la
 * Vara de DM sobre un bloque de estructura ya nombrado ({@link net.hawthorn.dndsheets.DungeonToolManager})
 * los lee directo del bloque en vez de obligar al DM a retipear el mismo id que ya escribió una vez al
 * guardar la estructura.</p>
 */
public class DungeonPieceAddScreen extends SmallFormScreen {
	private final String prefillStructureId, prefillId;
	private EditBox idBox, structureBox, poolBox, weightBox, tagsBox;

	private DungeonPieceAddScreen(String prefillStructureId, String prefillId, Screen parent) {
		super(Component.translatable("gui.dndsheets.dungeon_piece_add.title"), 3, parent);
		this.prefillStructureId = prefillStructureId;
		this.prefillId = prefillId;
	}

	public static void open() {
		open("", "");
	}

	public static void open(String prefillStructureId, String prefillId) {
		Minecraft.getInstance().setScreen(new DungeonPieceAddScreen(prefillStructureId, prefillId, Minecraft.getInstance().screen));
	}

	@Override
	protected void buildForm() {
		idBox = addField("Id", prefillId, 32);
		structureBox = addField("Estructura (namespace:ruta)", prefillStructureId, 64);
		poolBox = addField("Pool", "", 32);
		weightBox = addField("Peso (1-150)", "1", 4);
		tagsBox = addField("Tags", "", 64);
	}

	@Override
	protected void onConfirm() {
		String id = idBox.getValue().trim();
		String structure = structureBox.getValue().trim();
		String pool = poolBox.getValue().trim();
		if (id.isEmpty() || structure.isEmpty() || pool.isEmpty()) return;

		int weight = parseIntOr(weightBox.getValue(), 1);
		DndsheetsMod.PACKET_HANDLER.sendToServer(new DungeonPieceCaptureMessage(id, structure, pool, weight, tagsBox.getValue().trim()));
	}
}
