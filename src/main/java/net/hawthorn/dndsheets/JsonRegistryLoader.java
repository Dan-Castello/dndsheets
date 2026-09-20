package net.hawthorn.dndsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;

//Pattern repeated in TraitRegistry/PresetRegistry/SpellRegistry/MonsterRegistry: read a JSON array from a
//file and, for each element, validate it has an "id", parse it, and register it — skipping (with a
//LOGGER warning, without aborting the whole file) any element missing "id" or that fails to parse/register.
//Lives in the root package (not in command/) so each registry can load itself from JSON without
//DndPaths (server startup) having to depend on the command layer/ M-ARQ-1. WeaponCommand doesn't use it: it validates several required fields at once and calls
//Config.registerWeapon with positional parameters instead of a parse()/register() pair over its own registry.
public class JsonRegistryLoader<T> {
	private final String kindName; //For the log messages, e.g. "trait", "preset", "spell", "monster".
	private final Function<JsonObject, T> parse;
	private final Consumer<T> register;

	public JsonRegistryLoader(String kindName, Function<JsonObject, T> parse, Consumer<T> register) {
		this.kindName = kindName;
		this.parse = parse;
		this.register = register;
	}

	public int loadFile(Path file) throws IOException {
		return loadJson(JsonParser.parseString(Files.readString(file)), file.getFileName().toString());
	}

	/**
	 * <p>Loads content from an already-parsed JSON, whether it comes from a world file or another mod's
	 * jar (see {@code ContentDatapackLoader}). Accepts an <b>array</b> of entries —like the hand-written
	 * packs— or a <b>bare object</b>, which is the datapack convention: one file, one entry.</p>
	 *
	 * @param source where it came from, only for the log warnings.
	 */
	public int loadJson(JsonElement root, String source) {
		return loadJson(root, source, id -> { });
	}

	/** @param onId called with the id of each loaded entry — see {@link ContentType#loadJson}. */
	public int loadJson(JsonElement root, String source, java.util.function.Consumer<String> onId) {
		JsonArray items;
		if (root.isJsonArray()) {
			items = root.getAsJsonArray();
		} else {
			items = new JsonArray();
			items.add(root);
		}
		int count = 0;
		//Per element, not per whole file: a malformed element in the middle of the list shouldn't silently
		//discard everything that came after it.
		int index = 0;
		for (JsonElement element : items) {
			index++;
			try {
				if (element.getAsJsonObject().has("id")) onId.accept(element.getAsJsonObject().get("id").getAsString());
				if (!element.getAsJsonObject().has("id")) {
					DndsheetsMod.LOGGER.warn("Skipping {} #{} in {}: missing the \"id\" field.", kindName, index, source);
					continue;
				}
				register.accept(parse.apply(element.getAsJsonObject()));
				count++;
			} catch (RuntimeException e) {
				DndsheetsMod.LOGGER.warn("Skipping {} #{} in {}: {}", kindName, index, source, e.toString());
			}
		}
		return count;
	}
}
