package net.hawthorn.dndsheets.client.gui;

import javax.annotation.Nullable;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.MulticlassMessage;
import net.hawthorn.dndsheets.network.PresetApplyMessage;
import net.hawthorn.dndsheets.network.PresetApplyToMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <p>Class preset selector: the list (ids + names) is sent by the server in
 * {@code BrowseListMessage} kind PRESET (requested with {@code BrowseActionMessage.LIST_PRESETS} when clicking "Presets" on the
 * sheet, or by a DM after picking another player in {@link PlayerPickerScreen}),
 * because the preset registry only lives in server memory. Picking one fills in the sheet's class, hit
 * die, and ability scores — you have to close and reopen it to see it. An empty {@code
 * targetUuid} means "apply it to my own sheet".</p>
 *
 * <p>{@code multiclass} is a different mode over the SAME list, chosen in {@code PresetActionMenuScreen}
 * ("Multiclass" instead of "Apply preset"): instead of replacing class/hit die/ability scores
 * (what {@code PresetApplyMessage} does), it levels up IN the chosen class ({@code
 * MulticlassMessage}, the same path {@code /dndsheet multiclass} already used). It only makes sense for
 * self ({@code targetUuid} empty) — the button that triggers it lives on the character's own sheet, not
 * in the DM Panel.</p>
 */
public class PresetScreen extends ListPickerScreen {
	private final String targetUuid;
	private final boolean multiclass;
	private final List<String> ids;
	private final List<String> names;

	private PresetScreen(String targetUuid, boolean multiclass, List<String> ids, List<String> names, Screen parent) {
		super(Component.translatable(multiclass ? "gui.dndsheets.multiclass.title" : "gui.dndsheets.preset.title"), parent);
		this.targetUuid = targetUuid;
		this.multiclass = multiclass;
		this.ids = ids;
		this.names = names;
	}

	public static void open(String targetUuid, boolean multiclass, List<String> ids, List<String> names) {
		Minecraft.getInstance().setScreen(new PresetScreen(targetUuid, multiclass, ids, names, Minecraft.getInstance().screen));
	}

	@Override
	protected boolean searchable() {
		return true;
	}

	@Override
	protected void buildRows() {
		for (int i = 0; i < names.size(); i++) {
			String presetId = ids.get(i);
			addRow(Component.literal(names.get(i)), b -> {
				if (multiclass) {
					DndsheetsMod.PACKET_HANDLER.sendToServer(new MulticlassMessage(presetId));
				} else if (targetUuid.isEmpty()) {
					DndsheetsMod.PACKET_HANDLER.sendToServer(new PresetApplyMessage(presetId));
				} else {
					DndsheetsMod.PACKET_HANDLER.sendToServer(new PresetApplyToMessage(targetUuid, presetId));
				}
				this.onClose();
			});
		}
	}

	@Nullable
	@Override
	protected Component emptyMessage() {
		return names.isEmpty() ? Component.translatable("gui.dndsheets.preset.empty") : null;
	}
}
