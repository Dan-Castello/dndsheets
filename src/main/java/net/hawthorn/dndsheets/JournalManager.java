package net.hawthorn.dndsheets;

import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
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
 * <p>Campaign journal and handouts. <b>They're the same thing</b>, which is why there's a single class:
 * an entry with a title, a body, and a visibility. A journal note is an entry visible to the whole
 * party; a handout is an entry visible only to whoever it was given to; a private DM note is an entry
 * nobody else sees. Splitting them into two systems would have duplicated the persistence, the GUI, and
 * the network message just to change who can read them.</p>
 *
 * <p><b>The text comes from a Book and Quill.</b> Minecraft already ships a multiline text editor, and
 * the mod already hands out a "DM Notebook" that is exactly that ({@code NotesCommand}). Writing in the
 * book and publishing it makes use of that editor instead of trying to cram paragraphs through a
 * command argument or a single-line text box, which is the only thing a Minecraft GUI offers.</p>
 *
 * <p>Saved per installation alongside the rest of the content, not per player: it's table material.
 * Every change writes to disk immediately — the mod has already lost changes once that only lived in
 * memory.</p>
 */
public class JournalManager {

	/**
	 * @param sharedWith UUIDs of who can read it besides the author. Empty with {@code party} false =
	 *                   only the DM who wrote it.
	 * @param party      visible to everyone.
	 */
	public record Entry(String id, String title, String body, String authorUuid,
	                    Set<String> sharedWith, boolean party) {

		public boolean canRead(ServerPlayer player) {
			if (party) return true;
			String uuid = player.getStringUUID();
			//The author can always read their own, and an operator sees everything: the DM needs to be able
			//to review what they handed out without having to share it with themselves.
			return uuid.equals(authorUuid) || sharedWith.contains(uuid) || DndsheetsMod.canActAsDm(player);
		}

		/** Label showing who it reaches, so the DM can see it without opening the entry. */
		public Component visibilityLabel() {
			if (party) return Component.translatable("gui.dndsheets.journal.visibility_party");
			if (sharedWith.isEmpty()) return Component.translatable("gui.dndsheets.journal.visibility_private");
			return Component.translatable(sharedWith.size() == 1
				? "gui.dndsheets.journal.visibility_one"
				: "gui.dndsheets.journal.visibility_many", sharedWith.size());
		}
	}

	private static final Path FILE = DndPaths.ROOT.resolve("journal.json");
	private static final Map<String, Entry> entries = new LinkedHashMap<>();
	private static boolean loaded = false;

	//--- Reading and writing ----------------------------------------------------------------------------

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
					json.has("title") ? json.get("title").getAsString() : "(untitled)",
					json.has("body") ? json.get("body").getAsString() : "",
					json.has("author") ? json.get("author").getAsString() : "",
					shared,
					json.has("party") && json.get("party").getAsBoolean());
				entries.put(entry.id(), entry);
			}
		} catch (Exception e) {
			//Per-file, not per-entry, unlike the content packs: here a corrupt JSON is the whole journal,
			//and continuing with half of it would be worse than warning and starting empty.
			DndsheetsMod.LOGGER.error("Could not read the campaign journal; starting empty.", e);
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
			Files.writeString(FILE, DndsheetsMod.PRETTY_GSON.toJson(array));
		} catch (IOException e) {
			DndsheetsMod.LOGGER.error("Could not save the campaign journal.", e);
		}
	}

	//--- Operations --------------------------------------------------------------------------------------

	/**
	 * <p>Converts the Book and Quill the player is holding into a journal entry. Returns
	 * {@code null} if they're not holding one or if it's blank.</p>
	 */
	@Nullable
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
	 * <p>Pages of a Book and Quill, joined together. An UNSIGNED book stores its pages as plain text; a
	 * signed one stores them as JSON components. The unsigned one is accepted, since that's the one the
	 * mod hands out and the only one the player can keep editing.</p>
	 */
	@Nullable
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
		if (slug.isEmpty()) slug = "nota"; //A title entirely in non-Latin characters shouldn't produce an empty id.
		String candidate = slug;
		for (int n = 2; entries.containsKey(candidate); n++) candidate = slug + "-" + n;
		return candidate;
	}

	@Nullable
	public static Entry get(String id) {
		ensureLoaded();
		return entries.get(id);
	}

	/** The entries that player can read, in creation order. */
	public static List<Entry> readableBy(ServerPlayer player) {
		ensureLoaded();
		List<Entry> visible = new ArrayList<>();
		for (Entry entry : entries.values()) {
			if (entry.canRead(player)) visible.add(entry);
		}
		return visible;
	}

	/** Shares with specific players (handout). Doesn't touch {@code party}. */
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

	/** Publishes to the whole party, or reverts to private while leaving specific shares intact. */
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
