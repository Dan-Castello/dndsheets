package net.hawthorn.dndsheets.client.gui;

import net.hawthorn.dndsheets.CharacterOptionsRegistry;
import net.hawthorn.dndsheets.ContentType;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.network.BrowseActionMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * <p>"Create content" from the DM Panel: choose what to create/edit/delete without hand-writing JSON
 * outside the game — weapons/spells/presets via {@link ContentEntryListScreen}, race/background/class via
 * {@link OptionsManageScreen}. Traits and monsters aren't here yet — traits need their own level/die list
 * editor, monsters are created by capturing an already-configured NPC (see the pending note in
 * {@code MonsterActionScreen}) — both are left for a later pass.</p>
 */
public class ContentTypeListScreen extends ListPickerScreen {
	private ContentTypeListScreen(Screen parent) {
		super(Component.translatable("gui.dndsheets.content_type.title"), parent);
	}

	public static void open() {
		Minecraft.getInstance().setScreen(new ContentTypeListScreen(Minecraft.getInstance().screen));
	}

	@Override
	protected void buildRows() {
		addRow(Component.translatable("gui.dndsheets.content_type.weapons"), b -> request(ContentType.WEAPON));
		addRow(Component.translatable("gui.dndsheets.content_type.spells"), b -> request(ContentType.SPELL));
		addRow(Component.translatable("gui.dndsheets.content_type.presets"), b -> request(ContentType.PRESET));
		addRow(Component.translatable("gui.dndsheets.content_type.traits"), b -> request(ContentType.TRAIT));
		addRow(Component.translatable("gui.dndsheets.content_type.encounters"), b -> request(ContentType.ENCOUNTER));
		addRow(Component.translatable("gui.dndsheets.content_type.feats"), b -> request(ContentType.FEAT));
		addRow(Component.translatable("gui.dndsheets.content_type.magic_items"), b -> request(ContentType.MAGIC_ITEM));
		//Race and Background no longer have an in-game editor: Origins picks them, /dndspecies load/loadbackground
		//homebrews them via file (see dndsheets_species). No row here so as not to promise screens that no longer exist.
		addRow(Component.translatable("gui.dndsheets.content_type.classes"), b -> requestOptions(CharacterOptionsRegistry.CLASS));
	}

	private static void request(ContentType type) {
		DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(BrowseActionMessage.Action.CONTENT_ENTRIES, type.name()));
	}

	private static void requestOptions(String category) {
		DndsheetsMod.PACKET_HANDLER.sendToServer(new BrowseActionMessage(BrowseActionMessage.Action.MANAGE_OPTIONS, category));
	}
}
