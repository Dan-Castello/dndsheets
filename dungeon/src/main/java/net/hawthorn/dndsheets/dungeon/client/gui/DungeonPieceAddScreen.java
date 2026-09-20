package net.hawthorn.dndsheets.dungeon.client.gui;
import net.hawthorn.dndsheets.client.gui.SmallFormScreen;

import net.hawthorn.dndsheets.dungeon.DndsheetsDungeonMod;
import net.hawthorn.dndsheets.client.gui.components.TomeButton;
import net.hawthorn.dndsheets.dungeon.network.DungeonPieceCaptureMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Captures a new dungeon piece: the DM has already scanned it in the world with a structure block
 * (SAVE mode) under the id they type into "Structure" — this form just copies that .nbt to the game's
 * datapack and registers it (see {@link net.hawthorn.dndsheets.dungeon.DungeonManager#capturePiece}).</p>
 *
 * <p>"Structure" and "Id" can be prefilled (see {@link #open(String, String)}) — right-clicking with the
 * DM Wand on an already-named structure block ({@link net.hawthorn.dndsheets.dungeon.DungeonToolManager})
 * reads them directly from the block instead of forcing the DM to retype the same id they already wrote
 * once when saving the structure.</p>
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
		structureBox = addField(net.minecraft.client.resources.language.I18n.get("gui.dndsheets.form.structure_path"), prefillStructureId, 64);
		poolBox = addField("Pool", "", 32);
		weightBox = addField(net.minecraft.client.resources.language.I18n.get("gui.dndsheets.form.weight_range"), "1", 4);
		tagsBox = addField("Tags", "", 64);

		//Extra row before Confirm/Cancel (which SmallFormScreen adds on its own, see its init()): the same
		//"Structure" field above serves as the target whether it's hand-scanned or drawn, so the form
		//isn't duplicated, it just branches at the action button.
		int y = nextRowY();
		this.addRenderableWidget(TomeButton.of(Component.translatable("gui.dndsheets.dungeon_piece_add.trace_button"), b ->
			DungeonTraceScreen.open(idBox.getValue().trim(), structureBox.getValue().trim(), poolBox.getValue().trim(),
				parseIntOr(weightBox.getValue(), 1), tagsBox.getValue().trim(), this),
			centerX - formWidth() / 2, y, formWidth(), FIELD_HEIGHT));
	}

	@Override
	protected void onConfirm() {
		String id = idBox.getValue().trim();
		String structure = structureBox.getValue().trim();
		String pool = poolBox.getValue().trim();
		if (id.isEmpty() || structure.isEmpty() || pool.isEmpty()) return;

		int weight = parseIntOr(weightBox.getValue(), 1);
		DndsheetsDungeonMod.PACKET_HANDLER.sendToServer(new DungeonPieceCaptureMessage(id, structure, pool, weight, tagsBox.getValue().trim()));
	}
}
