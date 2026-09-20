package net.hawthorn.dndsheets;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.ModList;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * <p><b>Skin packs.</b> If you have a creature mod installed, its models get used automatically: the red
 * dragon becomes the Ice and Fire dragon, the minotaur becomes Twilight Forest's, the guard becomes a real
 * Guard Villagers guard. With nothing installed, everything stays exactly as it is now.</p>
 *
 * <p><b>Why this exists.</b> Reported while playing: "to the players everything is the same thing with
 * different names." Vanilla Minecraft has 41 creature models and the SRD has 330 monsters, so no matter
 * how they're distributed, an ancient dragon and a wyrmling will share a body. That's the real edge that
 * Roll20 or Foundry get from their token catalogs. The answer isn't to draw 300 models — that's not
 * feasible, and third-party art can't be redistributed — but rather that the catalog already exists: it's
 * the Minecraft creature-mod ecosystem. The only missing piece was the translation between an SRD id and
 * theirs.</p>
 *
 * <p><b>Only the model changes.</b> A skin pack doesn't touch HP, AC, attacks, or type: the dragon is still
 * the SRD dragon with its numbers, it just looks like the Ice and Fire one. It also doesn't inherit that
 * mod's AI — the mod's monsters are spawned with {@code setNoAi} — so a dragon from another mod won't fly
 * off mid-combat.</p>
 *
 * <p><b>Nothing is mandatory and nothing breaks.</b> A pack is only applied if its mod is loaded, and each
 * line only if the entity actually exists ({@link MonsterRegistry#reskin}). A wrong id, a mod that renames
 * its entities between versions, or a pack for a mod you don't have don't leave a monster worse off than
 * it was: they leave it exactly as it was. That's why packs can be written from a mod's documentation
 * without having all eight installed.</p>
 *
 * <p><b>A DM can write their own</b> in {@code <world>/dndsheets/skins/whatever.json}, using the same
 * format. The mod's own packs live inside the jar and aren't copied to the folder — there's nothing there
 * a DM would want to edit, and a per-world copy would go stale — the DM's own packs load afterward, so on
 * a conflict over a monster, the DM wins.</p>
 */
public final class MonsterSkins {

	/**
	 * <p>The packs shipped with the mod. This is a hand-written list because listing a folder inside the
	 * jar is considerably more fragile than maintaining seven names here.</p>
	 */
	private static final List<String> SHIPPED = List.of(
		"iceandfire", "twilightforest", "alexsmobs", "naturalist", "mowziesmobs", "guardvillagers", "cataclysm");

	private static final Gson GSON = new Gson();

	/**
	 * <p>Datapacks also get loaded <b>during</b> startup, before the server reads the world folder. Without
	 * this flag, every startup would write the same report to the log twice, and the first one would be
	 * incomplete.</p>
	 */
	private static boolean started = false;

	private MonsterSkins() {
	}

	/** After a {@code /reload}: monsters get re-registered with their vanilla model and need to be repainted. */
	static void reapplyIfStarted() {
		if (started) applyAll();
	}

	/**
	 * <p>Applies everything that can be applied. Called on server startup and again after every datapack
	 * reload, because a reload re-registers monsters with their vanilla model.</p>
	 */
	public static void applyAll() {
		started = true;
		for (String pack : SHIPPED) {
			try (InputStream in = MonsterSkins.class.getResourceAsStream("/dndsheets/skins/" + pack + ".json")) {
				if (in != null) apply(GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class), pack + ".json");
			} catch (IOException | RuntimeException e) {
				DndsheetsMod.LOGGER.warn("dndsheets: could not read the skin pack {}: {}", pack, e.toString());
			}
		}
		applyFolder(DndPaths.SKINS_DIR);
	}

	/** The DM's packs, after the mod's own, so they can override any decision we made. */
	private static void applyFolder(Path dir) {
		if (!Files.isDirectory(dir)) return;
		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
				try {
					apply(GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class),
						file.getFileName().toString());
				} catch (IOException | RuntimeException e) {
					DndsheetsMod.LOGGER.warn("dndsheets: could not read {}: {}", file.getFileName(), e.toString());
				}
			}
		} catch (IOException e) {
			DndsheetsMod.LOGGER.warn("dndsheets: could not list {}", dir);
		}
	}

	private static void apply(JsonObject pack, String source) {
		if (pack == null || !pack.has("mod") || !pack.has("skins")) return;
		String modId = pack.get("mod").getAsString();
		//"minecraft" allows a pack that only rearranges vanilla models, without depending on anything.
		if (!"minecraft".equals(modId) && !ModList.get().isLoaded(modId)) return;

		int applied = 0;
		List<String> missed = new ArrayList<>();
		for (Map.Entry<String, com.google.gson.JsonElement> entry : pack.getAsJsonObject("skins").entrySet()) {
			if (MonsterRegistry.reskin(entry.getKey(), entry.getValue().getAsString())) applied++;
			else missed.add(entry.getKey());
		}

		String name = pack.has("name") ? pack.get("name").getAsString() : modId;
		DndsheetsMod.LOGGER.info("dndsheets: {} detected, {} monsters use its models ({}).", name, applied, source);
		if (!missed.isEmpty()) {
			//We report which entries didn't take and why that might be. It's the only clue someone would
			//have whose dragon still looks like a ravager with the mod installed, and what lets them fix it by hand.
			DndsheetsMod.LOGGER.warn("dndsheets: {} entries from {} were not applied (the entity does not exist in this version "
				+ "of the mod, or the monster is not in the bestiary): {}", missed.size(), source, String.join(", ", missed));
		}
	}
}
