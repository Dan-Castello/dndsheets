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
 * <p>Carga contenido de <b>datapacks y de otros mods</b>: cualquier JSON en
 * {@code data/<loquesea>/dndsheets/<tipo>/*.json} entra al registro solo, sin comandos y sin escribir una
 * línea de Java.</p>
 *
 * <p><b>Por qué existe.</b> Hasta ahora el contenido solo podía venir de dos sitios: el pack que trae el
 * propio mod, y los archivos que un DM pone a mano en la carpeta de su mundo. Otro mod que quisiera añadir
 * treinta conjuros tenía que llamar a la API desde Java, compilar contra dndsheets y acertar con el momento
 * del arranque. Eso es una barrera que decide si hay ecosistema o no: los mods con cientos de addons —Create
 * es el ejemplo— lo son porque extenderlos es <em>poner datos en una carpeta</em>, no programar.</p>
 *
 * <p><b>Qué gana cada uno.</b> Un mod addon mete sus JSON en su jar y ya está: no depende de la API, no se
 * rompe si cambia una firma, y funciona con cualquier versión de dndsheets que lea el mismo esquema. Un DM
 * sin conocimientos de mods puede repartir su bestiario como un datapack normal. Y {@code /reload} recarga
 * todo, que es el ciclo de trabajo que ya conoce cualquiera que haya tocado recetas.</p>
 *
 * <p><b>Orden y prioridad.</b> Los datapacks se cargan al preparar el servidor, ANTES de que
 * {@code DndPaths} lea la carpeta del mundo, así que en un choque de ids gana lo que el DM escribió a mano.
 * Es el orden que se quiere: el contenido de un addon es un punto de partida, y la última palabra la tiene
 * quien lleva la partida.</p>
 */
@Mod.EventBusSubscriber
public class ContentDatapackLoader extends SimpleJsonResourceReloadListener {

	private static final Gson GSON = new Gson();

	/** Qué hacer con cada JSON encontrado. Firma común a los seis registros. */
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
		//Una carpeta por tipo, con el mismo nombre que ya usa la carpeta del mundo: quien sepa poner un
		//hechizo en <mundo>/dndsheets/spells/ no tiene que aprender un segundo esquema de rutas.
		//Su propia tabla en vez de reusar ContentType: ese enum significa "lo que el editor in-game sabe
		//editar" —los objetos mágicos no están— y ensancharlo para esto habría cambiado su contrato. El
		//compilador lo dijo en cuanto lo intenté, rompiendo un switch exhaustivo de la pantalla del editor.
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
		//Quién trajo cada id EN ESTA recarga. Sirve para distinguir dos cosas que se ven igual en el registro
		//y no lo son: que un datapack se recargue sobre sí mismo (normal, silencioso) y que dos addons
		//distintos reclamen el mismo id (un choque de verdad, que hay que decir y con los dos nombres).
		Map<String, ResourceLocation> claimedHere = new HashMap<>();
		int loaded = 0;

		for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
			ResourceLocation source = entry.getKey();
			try {
				loaded += loader.load(entry.getValue(), source.toString(), id -> {
					ResourceLocation previous = claimedHere.put(id, source);
					if (previous != null) {
						DndsheetsMod.LOGGER.warn("dndsheets: dos datapacks reclaman el id \"{}\" ({} y {}); gana el segundo.",
							id, previous, source);
					}
				});
			} catch (RuntimeException e) {
				//Por archivo, no por recarga entera: un JSON roto de un addon no puede dejar sin contenido a
				//los demás. Mismo criterio que JsonRegistryLoader ya aplica por entrada.
				DndsheetsMod.LOGGER.warn("dndsheets: no pude cargar {}: {}", source, e.toString());
			}
		}

		if (loaded > 0) DndsheetsMod.LOGGER.info("dndsheets: cargadas {} entradas de {} desde datapacks.", loaded, label);

		//Un /reload registra los monstruos otra vez, con el modelo que diga su JSON: sin esto, recargar
		//deshace los packs de aspecto y el dragón de Ice and Fire vuelve a ser un devastador.
		if ("monsters".equals(label)) MonsterSkins.reapplyIfStarted();
	}
}
