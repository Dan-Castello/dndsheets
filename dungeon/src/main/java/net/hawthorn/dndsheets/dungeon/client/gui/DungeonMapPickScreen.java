package net.hawthorn.dndsheets.dungeon.client.gui;

import net.hawthorn.dndsheets.DndPaths;
import net.hawthorn.dndsheets.client.gui.ListPickerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * <p>The maps from online generators (donjon, Universal VTT, Dungeon Scrawl) in this computer's
 * {@code dndsheets/dungeon_maps} folder: the DM downloads them on their own PC, so they're read here, not on
 * the server. Picking one asks for its scale and opens it in the room editor (DungeonMapImportScreen).</p>
 */
public class DungeonMapPickScreen extends ListPickerScreen {
	public static final Path MAPS_DIR = DndPaths.ROOT.resolve("dungeon_maps");

	private boolean none;

	private DungeonMapPickScreen(Screen parent) {
		super(Component.translatable("gui.dndsheets.dungeon_map.pick_title"), parent);
	}

	public static void open(Screen parent) {
		Minecraft.getInstance().setScreen(new DungeonMapPickScreen(parent));
	}

	@Override
	protected void buildRows() {
		List<Path> files = files();
		none = files.isEmpty();
		for (Path file : files) {
			addRow(Component.literal(file.getFileName().toString()), b -> Minecraft.getInstance().setScreen(new DungeonMapImportScreen(file, this)));
		}
	}

	private static List<Path> files() {
		try {
			Files.createDirectories(MAPS_DIR);
			try (Stream<Path> files = Files.list(MAPS_DIR)) {
				return files.filter(Files::isRegularFile).sorted().toList();
			}
		} catch (IOException e) {
			return List.of();
		}
	}

	@Override
	protected Component emptyMessage() {
		return !none ? null : Component.translatable("gui.dndsheets.dungeon_map.empty",MAPS_DIR.toAbsolutePath().normalize().toString());
	}
}
