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
 * <p><b>Packs de aspecto.</b> Si tienes instalado un mod de criaturas, sus modelos se usan solos: el dragón
 * rojo pasa a ser el dragón de Ice and Fire, el minotauro el de Twilight Forest, el guardia un guardia de
 * verdad de Guard Villagers. Sin instalar nada, todo sigue exactamente igual que ahora.</p>
 *
 * <p><b>Por qué existe.</b> Reportado jugando: «para los jugadores todo son lo mismo con diferentes
 * nombres». Minecraft vanilla tiene 41 modelos de criatura y el SRD tiene 330 monstruos, así que por mucho
 * que se repartan, un dragón anciano y una cría van a compartir cuerpo. Esa es la ventaja real que sacan
 * Roll20 o Foundry con sus catálogos de fichas. La respuesta no es dibujar 300 modelos —no se puede, y el
 * arte de terceros no se puede redistribuir— sino que el catálogo ya existe: es el ecosistema de mods de
 * criaturas de Minecraft. Lo único que faltaba era la traducción entre un id del SRD y el suyo.</p>
 *
 * <p><b>Solo cambia el modelo.</b> Un pack de aspecto no toca PG, CA, ataques ni tipo: el dragón sigue
 * siendo el dragón del SRD con sus números, solo que se ve como el de Ice and Fire. Tampoco hereda su IA
 * —los monstruos del mod se invocan con {@code setNoAi}— así que un dragón de otro mod no se va a ir
 * volando a mitad del combate.</p>
 *
 * <p><b>Nada es obligatorio y nada se rompe.</b> Se aplica un pack solo si su mod está cargado, y cada
 * línea solo si la entidad existe de verdad ({@link MonsterRegistry#reskin}). Un id equivocado, un mod que
 * renombra sus entidades entre versiones o un pack para un mod que no tienes no dejan un monstruo peor de
 * como estaba: lo dejan como estaba. Por eso los packs se pueden escribir a partir de la documentación de
 * cada mod sin tener los ocho instalados.</p>
 *
 * <p><b>Un DM puede escribir el suyo</b> en {@code <mundo>/dndsheets/skins/loquesea.json}, con el mismo
 * formato. Los del mod viven dentro del jar y no se copian a la carpeta —no hay nada que un DM quiera
 * editar ahí, y una copia por mundo se quedaría vieja—; los suyos se cargan después, así que en un choque
 * de monstruo gana el DM.</p>
 */
public final class MonsterSkins {

	/**
	 * <p>Los packs que trae el mod. Es una lista escrita a mano porque listar una carpeta dentro del jar es
	 * bastante más frágil que mantener siete nombres aquí.</p>
	 */
	private static final List<String> SHIPPED = List.of(
		"iceandfire", "twilightforest", "alexsmobs", "naturalist", "mowziesmobs", "guardvillagers", "cataclysm");

	private static final Gson GSON = new Gson();

	/**
	 * <p>Los datapacks se cargan también <b>durante</b> el arranque, antes de que el servidor lea la carpeta
	 * del mundo. Sin esta bandera, cada arranque escribiría dos veces el mismo informe en el log y el
	 * primero sería incompleto.</p>
	 */
	private static boolean started = false;

	private MonsterSkins() {
	}

	/** Tras un {@code /reload}: los monstruos vuelven a registrarse con su modelo vanilla y hay que repintar. */
	static void reapplyIfStarted() {
		if (started) applyAll();
	}

	/**
	 * <p>Aplica todo lo aplicable. Se llama al arrancar el servidor y otra vez tras cada recarga de
	 * datapacks, porque una recarga vuelve a registrar los monstruos con su modelo vanilla.</p>
	 */
	public static void applyAll() {
		started = true;
		for (String pack : SHIPPED) {
			try (InputStream in = MonsterSkins.class.getResourceAsStream("/dndsheets/skins/" + pack + ".json")) {
				if (in != null) apply(GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class), pack + ".json");
			} catch (IOException | RuntimeException e) {
				DndsheetsMod.LOGGER.warn("dndsheets: no pude leer el pack de aspecto {}: {}", pack, e.toString());
			}
		}
		applyFolder(DndPaths.SKINS_DIR);
	}

	/** Los del DM, después de los del mod, para que pueda pisar cualquier decisión nuestra. */
	private static void applyFolder(Path dir) {
		if (!Files.isDirectory(dir)) return;
		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
				try {
					apply(GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class),
						file.getFileName().toString());
				} catch (IOException | RuntimeException e) {
					DndsheetsMod.LOGGER.warn("dndsheets: no pude leer {}: {}", file.getFileName(), e.toString());
				}
			}
		} catch (IOException e) {
			DndsheetsMod.LOGGER.warn("dndsheets: no pude listar {}", dir);
		}
	}

	private static void apply(JsonObject pack, String source) {
		if (pack == null || !pack.has("mod") || !pack.has("skins")) return;
		String modId = pack.get("mod").getAsString();
		//"minecraft" deja escribir un pack que solo reordena modelos vanilla, sin depender de nada.
		if (!"minecraft".equals(modId) && !ModList.get().isLoaded(modId)) return;

		int applied = 0;
		List<String> missed = new ArrayList<>();
		for (Map.Entry<String, com.google.gson.JsonElement> entry : pack.getAsJsonObject("skins").entrySet()) {
			if (MonsterRegistry.reskin(entry.getKey(), entry.getValue().getAsString())) applied++;
			else missed.add(entry.getKey());
		}

		String name = pack.has("name") ? pack.get("name").getAsString() : modId;
		DndsheetsMod.LOGGER.info("dndsheets: {} detectado, {} monstruos usan sus modelos ({}).", name, applied, source);
		if (!missed.isEmpty()) {
			//Se dicen los que no entraron y por qué puede ser. Es la única pista que tendría alguien cuyo
			//dragón sigue siendo un devastador con el mod instalado, y lo que permite arreglarlo a mano.
			DndsheetsMod.LOGGER.warn("dndsheets: {} entradas de {} no se aplicaron (la entidad no existe en esta versión "
				+ "del mod, o el monstruo no está en el bestiario): {}", missed.size(), source, String.join(", ", missed));
		}
	}
}
