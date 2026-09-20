package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>Fixed two-row menu hanging off the sheet's "Presets" button: "Apply preset" (the usual behavior,
 * replaces class/hit die/ability scores) and "Multiclass" (levels up in a new class without touching
 * the rest). There was no room for a fifth page button in {@code CharacterSheetScreen} — the bottom
 * row already fills the 350px width with four (see the {@code BOTTOM_BUTTON_WIDTH} comment there) —
 * so the existing "Presets" button becomes the door to both actions instead of adding yet another
 * button.</p>
 *
 * <p>Both rows request the SAME preset list from the server ({@code BrowseActionMessage} with
 * LIST_PRESETS or LIST_PRESETS_MULTICLASS) and open the SAME {@link PresetScreen}; the only thing that
 * changes is the mode, which travels back and forth, so that screen knows which message to send when
 * a row is picked.</p>
 */
public class PresetActionMenuScreen extends ListPickerScreen {
	private PresetActionMenuScreen(Screen parent) {
		super(Component.translatable("gui.dndsheets.preset.menu_title"), parent);
	}

	public static void open(Screen parent) {
		Minecraft.getInstance().setScreen(new PresetActionMenuScreen(parent));
	}

	@Override
	protected void buildRows() {
		addRow(Component.translatable("gui.dndsheets.preset.menu_apply"),
			b -> DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(BrowseActionMessage.Action.LIST_PRESETS)));
		addRow(Component.translatable("gui.dndsheets.preset.menu_multiclass"),
			b -> DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(BrowseActionMessage.Action.LIST_PRESETS_MULTICLASS)));
	}
}
