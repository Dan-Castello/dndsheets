package net.hawthorn.dndsheets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * <p>Reads/writes the {@code dm_created.json} file for a content type (see
 * {@link DndPaths#dmCreatedFile}) — the same format already read by {@code Config.loadFile},
 * {@code JsonRegistryLoader} and {@code CharacterOptionsRegistry.loadFile}, just from the WRITE side.
 * Used by the in-game content creator: saving/deleting an entry means rewriting this entire file and
 * then calling the type's normal {@code loadFile} to hot-reload it — there's no new persistence, it's
 * the same pipeline as always seen in reverse.</p>
 */
public final class ContentPackFile {
	private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

	private ContentPackFile() {
	}

	private static JsonArray readArray(Path file) {
		if (!Files.exists(file)) return new JsonArray();
		try {
			String json = Files.readString(file);
			if (json.isBlank()) return new JsonArray();
			return JsonParser.parseString(json).getAsJsonArray();
		} catch (IOException | RuntimeException e) {
			DndsheetsMod.LOGGER.warn("dndsheets: could not read {}, treating it as empty: {}", file, e.toString());
			return new JsonArray();
		}
	}

	private static void writeArray(Path file, JsonArray array) throws IOException {
		Files.createDirectories(file.getParent());
		try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
			out.write(PRETTY_GSON.toJson(array).getBytes());
		}
	}

	/**
	 * <p>Adds {@code entry} to the file, or replaces the existing entry whose {@code idField} field
	 * matches (edit = same id, new fields) — the same "overwrite if it already exists" criterion as
	 * {@link NamedRegistry#register}.</p>
	 */
	public static void upsert(Path file, String idField, JsonObject entry) throws IOException {
		String id = entry.get(idField).getAsString();
		JsonArray current = readArray(file);
		JsonArray updated = new JsonArray();
		for (JsonElement el : current) {
			JsonObject obj = el.getAsJsonObject();
			if (!(obj.has(idField) && obj.get(idField).getAsString().equals(id))) updated.add(obj);
		}
		updated.add(entry);
		writeArray(file, updated);
	}

	/** @return true if there was an entry with that id (and it was deleted). */
	public static boolean removeById(Path file, String idField, String id) throws IOException {
		JsonArray current = readArray(file);
		JsonArray updated = new JsonArray();
		boolean removed = false;
		for (JsonElement el : current) {
			JsonObject obj = el.getAsJsonObject();
			if (obj.has(idField) && obj.get(idField).getAsString().equals(id)) {
				removed = true;
			} else {
				updated.add(obj);
			}
		}
		if (removed) writeArray(file, updated);
		return removed;
	}

	//For races/backgrounds/classes (CharacterOptionsRegistry.loadFile REPLACES the entire category, there's
	//no "id" to merge on) — writes the full list as-is, without reading anything first.
	public static void writeStringArray(Path file, List<String> values) throws IOException {
		JsonArray array = new JsonArray();
		for (String value : values) array.add(value);
		writeArray(file, array);
	}

	//Raw array text (possibly empty "[]") — what the server sends to the client to list already-created
	//entries (see BrowseListMessage kinds CONTENT_ENTRY/MANAGE_OPTIONS): the client has no access to the
	//server's filesystem, so the whole JSON travels over the network instead of a bare id.
	public static String readArrayText(Path file) {
		return readArray(file).toString();
	}

	/**
	 * <p>The entries from the OTHER files of the type — the mod's pack and any .json the DM has left by
	 * hand — in a single array, so they can be shown alongside the DM's own in the content creator.</p>
	 *
	 * <p>Without this, that menu only listed {@code dm_created.json}: a DM opening "Encounters" before
	 * creating any would see an empty screen despite having five encounters loaded and playable, and the
	 * obvious reading of that is "this doesn't manage anything." They're shown separately, not merged,
	 * because they don't behave the same: {@code mod_defaults.json} gets rewritten from the jar on every
	 * startup, so editing one doesn't change the pack's file but instead <b>saves your version</b> into
	 * yours, which wins on load (see the ordering in {@code DndPaths.autoLoadAll}).</p>
	 */
	public static String readOtherArraysText(Path dir, Path exclude) {
		JsonArray all = new JsonArray();
		try (java.util.stream.Stream<Path> files = Files.list(dir)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".json") && !p.equals(exclude))
					.sorted(java.util.Comparator.comparing(p -> p.getFileName().toString()))
					.toList()) {
				all.addAll(readArray(file));
			}
		} catch (IOException e) {
			DndsheetsMod.LOGGER.warn("dndsheets: could not list {}: {}", dir, e.toString());
		}
		return all.toString();
	}

}
