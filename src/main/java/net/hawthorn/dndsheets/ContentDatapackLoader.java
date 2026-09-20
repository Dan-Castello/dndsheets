package net.hawthorn.dndsheets;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;

/**
 * <p>Loads content from <b>datapacks and other mods</b>: any JSON at
 * {@code data/<whatever>/dndsheets/<type>/*.json} joins the registry on its own, with no commands and
 * without writing a line of Java.</p>
 *
 * <p><b>Why it exists.</b> Until now content could only come from two places: the pack the mod itself
 * ships with, and the files a DM places by hand in their world folder. Another mod wanting to add thirty
 * spells had to call the API from Java, compile against dndsheets, and get the startup timing right.
 * That's a barrier that decides whether an ecosystem exists at all: mods with hundreds of addons — Create
 * being the example — are that way because extending them means <em>putting data in a folder</em>, not
 * programming.</p>
 *
 * <p><b>What each side gains.</b> An addon mod drops its JSON in its jar and that's it: no dependency on
 * the API, nothing breaks if a signature changes, and it works with any dndsheets version that reads the
 * same schema. A DM with no modding knowledge can hand out their bestiary as a normal datapack. And
 * {@code /reload} reloads everything, the same workflow anyone who has touched recipes already knows.</p>
 *
 * <p><b>Order and priority.</b> Datapacks are loaded when the server starts up, BEFORE
 * {@code DndPaths} reads the world folder, so in an id clash whatever the DM wrote by hand wins. That's
 * the order we want: an addon's content is a starting point, and whoever is running the game has the
 * final word.</p>
 */
@Mod.EventBusSubscriber
public class ContentDatapackLoader extends SimpleJsonResourceReloadListener {

	private static final Gson GSON = new Gson();

	/** What to do with each JSON found. Signature shared by the six registries. */
	@FunctionalInterface
	private interface JsonLoader {
		int load(JsonElement root, String source, java.util.function.Consumer<String> onId);
	}

	private final String label;
	private final JsonLoader loader;

	private ContentDatapackLoader(String folder, JsonLoader loader) {
		super(GSON, "dndsheets/" + folder);
		this.label = folder;
		this.loader = loader;
	}

	@SubscribeEvent
	public static void onAddReloadListeners(AddReloadListenerEvent event) {
		//One folder per type, with the same name the world folder already uses: whoever knows how to
		//place a spell in <world>/dndsheets/spells/ doesn't have to learn a second path scheme.
		//Its own table instead of reusing ContentType: that enum means "what the in-game editor knows how
		//to edit" — magic items aren't in it — and widening it for this would have changed its contract.
		//The compiler said so the moment I tried, breaking an exhaustive switch in the editor screen.
		event.addListener(new ContentDatapackLoader("weapons", Config::loadJson));
		event.addListener(new ContentDatapackLoader("spells", SpellRegistry::loadJson));
		event.addListener(new ContentDatapackLoader("monsters", MonsterRegistry::loadJson));
		event.addListener(new ContentDatapackLoader("presets", PresetRegistry::loadJson));
		event.addListener(new ContentDatapackLoader("traits", TraitRegistry::loadJson));
		event.addListener(new ContentDatapackLoader("items", MagicItemRegistry::loadJson));
		event.addListener(new ContentDatapackLoader("encounters", EncounterRegistry::loadJson));
		event.addListener(new ContentDatapackLoader("feats", FeatRegistry::loadJson));
	}

	@Override
	protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
		//Who brought each id in THIS reload. Used to distinguish two things that look the same in the
		//registry but aren't: a datapack reloading over itself (normal, silent) versus two different
		//addons claiming the same id (a real clash, which must be reported with both names).
		Map<String, ResourceLocation> claimedHere = new HashMap<>();
		int loaded = 0;

		for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
			ResourceLocation source = entry.getKey();
			try {
				loaded += loader.load(entry.getValue(), source.toString(), id -> {
					ResourceLocation previous = claimedHere.put(id, source);
					if (previous != null) {
						DndsheetsMod.LOGGER.warn("dndsheets: two datapacks claim the id \"{}\" ({} and {}); the second one wins.",
							id, previous, source);
					}
				});
			} catch (RuntimeException e) {
				//Per file, not for the whole reload: a broken JSON from one addon can't leave everyone
				//else without content. Same criterion JsonRegistryLoader already applies per entry.
				DndsheetsMod.LOGGER.warn("dndsheets: no pude cargar {}: {}", source, e.toString());
			}
		}

		if (loaded > 0) DndsheetsMod.LOGGER.info("dndsheets: cargadas {} entradas de {} desde datapacks.", loaded, label);

		//A /reload registers the monsters again, with whatever model their JSON specifies: without this,
		//reloading would undo the appearance packs and the Ice and Fire dragon would go back to being a ravager.
		if ("monsters".equals(label)) MonsterSkins.reapplyIfStarted();
	}
}
