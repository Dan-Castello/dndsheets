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
 * <p>Lee/escribe el archivo {@code dm_created.json} de un tipo de contenido (ver
 * {@link DndPaths#dmCreatedFile}) — el mismo formato que ya leen {@code Config.loadFile},
 * {@code JsonRegistryLoader} y {@code CharacterOptionsRegistry.loadFile}, solo que desde el lado de
 * ESCRITURA. Usado por el creador de contenido in-game: guardar/borrar una entrada es reescribir este
 * archivo entero y luego llamar al {@code loadFile} normal del tipo para recargarlo en caliente — no hay
 * ninguna persistencia nueva, es el mismo pipeline de siempre visto al revés.</p>
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
			DndsheetsMod.LOGGER.warn("dndsheets: no pude leer {}, se trata como vacío: {}", file, e.toString());
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
	 * <p>Añade {@code entry} al archivo, o reemplaza la entrada existente cuyo campo {@code idField}
	 * coincida (edición = mismo id, campos nuevos) — mismo criterio de "pisa si ya existe" que
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

	/** @return true si había una entrada con ese id (y se borró). */
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

	//Para razas/trasfondos/clases (CharacterOptionsRegistry.loadFile REEMPLAZA la categoría entera, no hay
	//"id" que fusionar) — escribe la lista completa tal cual, sin leer nada antes.
	public static void writeStringArray(Path file, List<String> values) throws IOException {
		JsonArray array = new JsonArray();
		for (String value : values) array.add(value);
		writeArray(file, array);
	}

	//Texto crudo del array (posiblemente vacío "[]") — lo que manda el servidor al cliente para listar
	//entradas ya creadas (ver BrowseListMessage kinds CONTENT_ENTRY/MANAGE_OPTIONS): el cliente no tiene
	//acceso al sistema de archivos del servidor, así que el JSON viaja entero por red en vez de un id suelto.
	public static String readArrayText(Path file) {
		return readArray(file).toString();
	}

	/**
	 * <p>Las entradas de los DEMÁS archivos del tipo —el pack del mod y cualquier .json que el DM haya
	 * dejado a mano— en un solo array, para poder enseñarlas junto a las suyas en el creador de contenido.</p>
	 *
	 * <p>Sin esto, ese menú solo listaba {@code dm_created.json}: un DM que abría "Encuentros" antes de
	 * crear ninguno veía una pantalla vacía pese a tener cinco encuentros cargados y jugables, y la lectura
	 * evidente de eso es "esto no gestiona nada". Se enseñan aparte y no mezclados porque no se comportan
	 * igual: {@code mod_defaults.json} se reescribe desde el jar en cada arranque, así que editar uno no es
	 * cambiar el archivo del pack sino <b>guardar tu versión</b> en el tuyo, que es la que gana al cargar
	 * (ver el orden de {@code DndPaths.autoLoadAll}).</p>
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
			DndsheetsMod.LOGGER.warn("dndsheets: no pude listar {}: {}", dir, e.toString());
		}
		return all.toString();
	}

}
