package net.hawthorn.dndsheets.dungeon.client.gui;

import net.hawthorn.dndsheets.client.gui.SmallFormScreen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

/**
 * <p>The id/pool form for a room drawn "just to paste" that the DM decides to keep as a dungeon piece
 * after all: the same fields as {@link DungeonPieceAddScreen}, and the drawing goes with them.</p>
 */
public class DungeonTraceSaveScreen extends SmallFormScreen {
	private final DungeonTraceScreen trace;
	private EditBox idBox, structureBox, poolBox, weightBox, tagsBox;

	DungeonTraceSaveScreen(DungeonTraceScreen trace) {
		super(Component.translatable("gui.dndsheets.dungeon_trace_save.title"), 3, trace);
		this.trace = trace;
	}

	@Override
	protected void buildForm() {
		idBox = addField(I18n.get("gui.dndsheets.form.id"), "", 32);
		structureBox = addField(I18n.get("gui.dndsheets.form.structure_path"), "", 64);
		poolBox = addField(I18n.get("gui.dndsheets.form.pool"), "", 32);
		weightBox = addField(I18n.get("gui.dndsheets.form.weight_range"), "1", 4);
		tagsBox = addField(I18n.get("gui.dndsheets.form.tags"), "", 64);
	}

	@Override
	protected void onConfirm() {
		String id = idBox.getValue().trim();
		String pool = poolBox.getValue().trim();
		if (id.isEmpty() || pool.isEmpty()) return;
		//No structure typed: name it after the piece, the usual choice anyway.
		String structure = structureBox.getValue().trim();
		if (structure.isEmpty()) structure = "dndsheets:" + id;
		trace.saveAs(id, structure, pool, parseIntOr(weightBox.getValue(), 1), tagsBox.getValue().trim());
	}
}
