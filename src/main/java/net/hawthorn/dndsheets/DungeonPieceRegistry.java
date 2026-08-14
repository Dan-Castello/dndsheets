package net.hawthorn.dndsheets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

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
 * <p>Piezas de mazmorra (habitaciones escaneadas por el DM con el bloque de estructura, ver
 * {@link DungeonManager}) registradas en memoria + un único JSON en disco. A diferencia de
 * {@link DndPaths} (compartido entre partidas, bajo {@link SheetLoader#GAME_DIR}), esta lista vive
 * POR MUNDO — bajo la carpeta de guardado de la partida actual, {@code server.getWorldPath(LevelResource.ROOT)}
 * — porque las piezas solo tienen sentido junto al datapack de esa misma partida donde
 * {@link DungeonManager#publish} copia sus .nbt.</p>
 */
public class DungeonPieceRegistry {
	public record DungeonPiece(String id, String structureId, String pool, int weight, String tags) {
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
			//Igual que SheetLoader.load: un archivo corrupto no debe impedir que el servidor arranque, solo
			//deja la lista vacía y lo avisa por log.
			DndsheetsMod.LOGGER.warn("dndsheets: no pude leer las piezas de mazmorra guardadas: {}", e.getMessage());
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
			Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();
			try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
				out.write(prettyGson.toJson(array).getBytes());
			}
		} catch (IOException e) {
			DndsheetsMod.LOGGER.error("dndsheets: no pude guardar las piezas de mazmorra.", e);
		}
	}
}
