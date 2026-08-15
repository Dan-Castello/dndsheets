package net.hawthorn.dndsheets;

import com.google.gson.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * <p>Diario de campaña y handouts. <b>Son la misma cosa</b>, y por eso hay una sola clase: una entrada
 * con título, cuerpo y visibilidad. Un apunte del diario es una entrada visible para el grupo; un handout
 * es una entrada visible solo para quien se la diste; una nota privada del DM es una entrada que no ve
 * nadie más. Separarlos en dos sistemas habría duplicado la persistencia, la GUI y el mensaje de red para
 * cambiar únicamente quién puede leerlas.</p>
 *
 * <p><b>El texto sale de un Libro y Pluma.</b> Minecraft ya trae un editor de texto multilínea, y el mod
 * ya reparte un "Cuaderno del DM" que es exactamente eso ({@code NotesCommand}). Escribir en el libro y
 * publicarlo aprovecha ese editor en vez de intentar meter párrafos por un argumento de comando o por una
 * caja de texto de una sola línea, que es lo único que da una GUI de Minecraft.</p>
 *
 * <p>Se guarda por instalación junto al resto del contenido, no por jugador: es material de la mesa. Todo
 * cambio escribe a disco en el momento — el mod ya perdió una vez cambios que solo vivían en memoria.</p>
 */
public class JournalManager {

	/**
	 * @param sharedWith UUIDs de quienes pueden leerla además del autor. Vacío y {@code party} false = solo
	 *                   el DM que la escribió.
	 * @param party      visible para todo el mundo.
	 */
	public record Entry(String id, String title, String body, String authorUuid,
	                    Set<String> sharedWith, boolean party) {

		public boolean canRead(ServerPlayer player) {
			if (party) return true;
			String uuid = player.getStringUUID();
			//El autor siempre puede leer lo suyo, y un operador ve todo: el DM tiene que poder repasar lo
			//que reparti� sin tener que compartírselo a sí mismo.
			return uuid.equals(authorUuid) || sharedWith.contains(uuid) || player.hasPermissions(2);
		}

		/** Etiqueta de a quién alcanza, para que el DM lo vea sin abrir la entrada. */
		public String visibilityLabel() {
			if (party) return "grupo";
			if (sharedWith.isEmpty()) return "privada";
			return sharedWith.size() + (sharedWith.size() == 1 ? " jugador" : " jugadores");
		}
	}

	private static final Path FILE = DndPaths.ROOT.resolve("journal.json");
	private static final Map<String, Entry> entries = new LinkedHashMap<>();
	private static boolean loaded = false;

	//--- Lectura y escritura ---------------------------------------------------------------------------

	private static void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		if (!Files.exists(FILE)) return;
		try {
			JsonArray array = JsonParser.parseString(Files.readString(FILE)).getAsJsonArray();
			for (JsonElement element : array) {
				JsonObject json = element.getAsJsonObject();
				Set<String> shared = new LinkedHashSet<>();
				if (json.has("sharedWith")) {
					for (JsonElement uuid : json.getAsJsonArray("sharedWith")) shared.add(uuid.getAsString());
				}
				Entry entry = new Entry(
					json.get("id").getAsString(),
					json.has("title") ? json.get("title").getAsString() : "(sin título)",
					json.has("body") ? json.get("body").getAsString() : "",
					json.has("author") ? json.get("author").getAsString() : "",
					shared,
					json.has("party") && json.get("party").getAsBoolean());
				entries.put(entry.id(), entry);
			}
		} catch (Exception e) {
			//Por archivo y no por entrada, a diferencia de los packs de contenido: aquí un JSON corrupto es
			//el diario entero, y seguir con la mitad sería peor que avisar y arrancar vacío.
			DndsheetsMod.LOGGER.error("No se pudo leer el diario de campaña; se arranca vacío.", e);
		}
	}

	private static void save() {
		JsonArray array = new JsonArray();
		for (Entry entry : entries.values()) {
			JsonObject json = new JsonObject();
			json.addProperty("id", entry.id());
			json.addProperty("title", entry.title());
			json.addProperty("body", entry.body());
			json.addProperty("author", entry.authorUuid());
			json.addProperty("party", entry.party());
			JsonArray shared = new JsonArray();
			for (String uuid : entry.sharedWith()) shared.add(uuid);
			json.add("sharedWith", shared);
			array.add(json);
		}
		try {
			Files.createDirectories(DndPaths.ROOT);
			Files.writeString(FILE, new GsonBuilder().setPrettyPrinting().create().toJson(array));
		} catch (IOException e) {
			DndsheetsMod.LOGGER.error("No se pudo guardar el diario de campaña.", e);
		}
	}

	//--- Operaciones -----------------------------------------------------------------------------------

	/**
	 * <p>Convierte el Libro y Pluma que el jugador lleva en la mano en una entrada del diario. Devuelve
	 * {@code null} si no lleva ninguno o si está en blanco.</p>
	 */
	public static Entry publishFromBook(ServerPlayer author, ItemStack book, String title) {
		String body = readPages(book);
		if (body == null || body.isBlank()) return null;

		ensureLoaded();
		String id = nextId(title);
		Entry entry = new Entry(id, title, body, author.getStringUUID(), new LinkedHashSet<>(), false);
		entries.put(id, entry);
		save();
		return entry;
	}

	/**
	 * <p>Páginas de un Libro y Pluma, unidas. Un libro SIN firmar guarda sus páginas como texto plano; uno
	 * firmado las guarda como componentes JSON. Se acepta el sin firmar, que es el que reparte el mod y el
	 * único que el jugador puede seguir editando.</p>
	 */
	private static String readPages(ItemStack book) {
		CompoundTag tag = book.getTag();
		if (tag == null || !tag.contains("pages")) return null;
		ListTag pages = tag.getList("pages", Tag.TAG_STRING);
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < pages.size(); i++) {
			if (i > 0) text.append("\n");
			text.append(pages.getString(i));
		}
		return text.toString();
	}

	private static String nextId(String title) {
		String slug = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
			.replaceAll("\\p{M}+", "")
			.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");
		if (slug.isEmpty()) slug = "nota"; //Un título entero en caracteres no latinos no debe dar un id vacío.
		String candidate = slug;
		for (int n = 2; entries.containsKey(candidate); n++) candidate = slug + "-" + n;
		return candidate;
	}

	public static Entry get(String id) {
		ensureLoaded();
		return entries.get(id);
	}

	/** Las entradas que ese jugador puede leer, en orden de creación. */
	public static List<Entry> readableBy(ServerPlayer player) {
		ensureLoaded();
		List<Entry> visible = new ArrayList<>();
		for (Entry entry : entries.values()) {
			if (entry.canRead(player)) visible.add(entry);
		}
		return visible;
	}

	/** Comparte con jugadores concretos (handout). No toca {@code party}. */
	public static boolean share(String id, Collection<ServerPlayer> targets) {
		ensureLoaded();
		Entry entry = entries.get(id);
		if (entry == null) return false;
		Set<String> shared = new LinkedHashSet<>(entry.sharedWith());
		for (ServerPlayer target : targets) shared.add(target.getStringUUID());
		entries.put(id, new Entry(entry.id(), entry.title(), entry.body(), entry.authorUuid(), shared, entry.party()));
		save();
		return true;
	}

	/** Publica para todo el grupo, o lo revierte a privada dejando los compartidos concretos intactos. */
	public static boolean setParty(String id, boolean party) {
		ensureLoaded();
		Entry entry = entries.get(id);
		if (entry == null) return false;
		entries.put(id, new Entry(entry.id(), entry.title(), entry.body(), entry.authorUuid(), entry.sharedWith(), party));
		save();
		return true;
	}

	public static boolean delete(String id) {
		ensureLoaded();
		if (entries.remove(id) == null) return false;
		save();
		return true;
	}
}
