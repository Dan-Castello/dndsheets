package net.hawthorn.dndsheets.dungeon;

import net.hawthorn.dndsheets.DndsheetsMod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Scanner;

/**
 * <p>Dungeon pieces (rooms scanned by the DM with the structure block, see
 * {@link DungeonManager}) registered in memory + a single JSON file on disk. Unlike
 * {@link DndPaths} (shared across games, under {@link SheetLoader#GAME_DIR}), this list lives
 * PER WORLD — under the current game's save folder, {@code server.getWorldPath(LevelResource.ROOT)}
 * — because pieces only make sense alongside that same game's datapack, where
 * {@link DungeonManager#publish} copies their .nbt.</p>
 */
@Mod.EventBusSubscriber
public class DungeonPieceRegistry {
	public record DungeonPiece(String id, String structureId, String pool, int weight, String tags) {
	}

	//Used to be called directly by DndPaths.onServerStarting (core). The core can no longer know
	//about this addon, so this class listens for the same Forge event on its own.
	@SubscribeEvent
	public static void onServerStarting(ServerStartingEvent event) {
		load(event.getServer());
	}

	private static final LinkedHashMap<String, DungeonPiece> PIECES = new LinkedHashMap<>();
	private static Path file;

	public static void register(DungeonPiece piece) {
		PIECES.put(piece.id(), piece);
	}

	public static void remove(String id) {
		PIECES.remove(id);
	}

	public static DungeonPiece get(String id) {
		return PIECES.get(id);
	}

	public static List<DungeonPiece> all() {
		return new ArrayList<>(PIECES.values());
	}

	public static void load(MinecraftServer server) {
		file = server.getWorldPath(LevelResource.ROOT).resolve("dndsheets").resolve("dungeon").resolve("pieces.json");
		PIECES.clear();
		if (!Files.exists(file)) return;

		try (InputStream in = Files.newInputStream(file)) {
			Scanner scanner = new Scanner(in).useDelimiter("\\A");
			String content = scanner.hasNext() ? scanner.next() : "[]";
			JsonArray array = JsonParser.parseString(content).getAsJsonArray();
			for (JsonElement el : array) {
				JsonObject json = el.getAsJsonObject();
				DungeonPiece piece = new DungeonPiece(
					json.get("id").getAsString(),
					json.get("structureId").getAsString(),
					json.get("pool").getAsString(),
					json.has("weight") ? json.get("weight").getAsInt() : 1,
					json.has("tags") ? json.get("tags").getAsString() : "");
				PIECES.put(piece.id(), piece);
			}
		} catch (Exception e) {
			//Same as SheetLoader.load: a corrupted file shouldn't prevent the server from starting, it
			//just leaves the list empty and logs a warning.
			DndsheetsMod.LOGGER.warn("dndsheets: could not read the saved dungeon pieces: {}", e.getMessage());
		}
	}

	public static void save(MinecraftServer server) {
		if (file == null) file = server.getWorldPath(LevelResource.ROOT).resolve("dndsheets").resolve("dungeon").resolve("pieces.json");

		JsonArray array = new JsonArray();
		for (DungeonPiece piece : PIECES.values()) {
			JsonObject json = new JsonObject();
			json.addProperty("id", piece.id());
			json.addProperty("structureId", piece.structureId());
			json.addProperty("pool", piece.pool());
			json.addProperty("weight", piece.weight());
			json.addProperty("tags", piece.tags());
			array.add(json);
		}

		try {
			Files.createDirectories(file.getParent());

			try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
				out.write(DndsheetsMod.PRETTY_GSON.toJson(array).getBytes());
			}
		} catch (IOException e) {
			DndsheetsMod.LOGGER.error("dndsheets: could not save the dungeon pieces.", e);
		}
	}
}
