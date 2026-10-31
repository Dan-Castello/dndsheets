package net.hawthorn.dndsheets.dungeon.client.gui;

import net.hawthorn.dndsheets.client.gui.SmallFormScreen;
import net.hawthorn.dndsheets.dungeon.DungeonMapImporter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * <p>Scale and wall height for a generator's map, then it opens in the room editor. Scale = blocks per map
 * square: at 3 a one-square corridor is 2 blocks wide (the wall takes the third).</p>
 */
public class DungeonMapImportScreen extends SmallFormScreen {
	//The editor's limit too (see DungeonTraceCaptureMessage).
	private static final int MAX_SIDE = 256;

	private final Path file;
	private final Screen list;
	private EditBox scaleBox, heightBox;

	DungeonMapImportScreen(Path file, Screen list) {
		super(Component.translatable("gui.dndsheets.dungeon_map.import_title", file.getFileName().toString()), 2, list);
		this.file = file;
		this.list = list;
	}

	@Override
	protected void buildForm() {
		scaleBox = addField(I18n.get("gui.dndsheets.dungeon_map.scale"), "3", 1);
		heightBox = addField(I18n.get("gui.dndsheets.dungeon_map.height"), "4", 2);
	}

	@Override
	protected void onConfirm() {
		int scale = Math.max(1, Math.min(6, parseIntOr(scaleBox.getValue(), 3)));
		int height = Math.max(2, Math.min(10, parseIntOr(heightBox.getValue(), 4)));
		DungeonMapImporter.Result map;
		try {
			map = DungeonMapImporter.read(file, scale);
		} catch (Exception e) {
			say(Component.translatable("chat.dndsheets.dungeon.map_failed", file.getFileName().toString(), String.valueOf(e.getMessage())));
			return;
		}
		int side = Math.max(map.grid().length, map.grid()[0].length);
		if (side > MAX_SIDE) {
			say(Component.translatable("gui.dndsheets.dungeon_map.too_big", side, MAX_SIDE));
			return;
		}
		//Back from the editor = the dungeon pieces list, not this form or the file list.
		Screen pieces = list instanceof DungeonMapPickScreen ? ((DungeonMapPickScreen) list).parentScreen() : list;
		DungeonTraceScreen.openImported(map, height, pieces);
	}

	private static void say(Component message) {
		if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.displayClientMessage(message, false);
	}
}
