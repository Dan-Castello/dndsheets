package net.hawthorn.dndsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>Lista de opciones de Clase del jugador: era texto libre en {@link
 * net.hawthorn.dndsheets.client.gui.CharacterSheetScreen} (un jugador nuevo no tenía forma de adivinar
 * qué escribir, y encima importaba de verdad — ver {@link Config#hitDieFor},
 * {@link WarlockPactMagicManager}, {@link WizardArcaneRecoveryManager}, que comparan por subcadena).
 * Ahora se elige con un GUI de lista (ver {@code BrowseActionMessage.CHARACTER_OPTIONS} y
 * {@code client.gui.CharacterOptionListScreen}), en vez de escribirla a mano.</p>
 *
 * <p><b>Raza y Trasfondo ya no viven acá</b>: se mudaron al addon {@code dndsheets_species} (modid
 * separado, dependencia dura de Origins) — ver Modularity Map / Library Audit. Origins elige, ese addon
 * aplica el Aumento de Característica/competencias del SRD con sus propios {@code RaceRegistry}/
 * {@code BackgroundRegistry}, mismo molde que este.</p>
 *
 * <p>Trae valores por defecto (SRD 5e en español; son exactamente los que ya reconocen por subcadena
 * {@code Config.hitDieFor} y los managers de brujo/mago) para que funcione sin ningún JSON de por medio.
 * Un pack en {@code dndsheets/classes/*.json} (ver {@code command.CharacterOptionsCommand}) REEMPLAZA la
 * lista completa, no la extiende — no hay "id" separado del texto aquí, el valor elegido es literalmente
 * lo que se escribe en la hoja, así que no hay nada que fusionar entre archivos.</p>
 */
public class CharacterOptionsRegistry {
	public static final String CLASS = "class";
	//Raza y trasfondo se mudaron al addon species (Origins elige, el addon aplica las mecánicas), pero
	//el core solo también es una configuración soportada y sin estas listas sus pasos de creación
	//mandaban un comando que no existe. Son el RESPALDO nombre-solo (SRD 5.1: 9 razas; el trasfondo del
	//SRD 5.1 es únicamente Acólito) — sin Aumento de Característica ni rasgos, que son cosa del addon.
	public static final String RACE = "race";
	public static final String BACKGROUND = "background";

	private static final Map<String, List<String>> OPTIONS = new LinkedHashMap<>();

	static {
		OPTIONS.put(CLASS, List.of(
			"Bárbaro", "Bardo", "Clérigo", "Druida", "Guerrero", "Monje",
			"Paladín", "Explorador", "Pícaro", "Hechicero", "Brujo", "Mago"
		));
		OPTIONS.put(RACE, List.of(
			"Enano", "Elfo", "Mediano", "Humano", "Dracónido", "Gnomo", "Semielfo", "Semiorco", "Tiefling"
		));
		OPTIONS.put(BACKGROUND, List.of("Acólito"));
	}

	public static boolean isValidCategory(String category) {
		return OPTIONS.containsKey(category);
	}

	public static List<String> get(String category) {
		return OPTIONS.getOrDefault(category, List.of());
	}

	//Público: también lo usa CharacterOptionsCommand al cargar un archivo de la categoría.
	public static void replace(String category, List<String> values) {
		OPTIONS.put(category, values);
	}

	//Público: usado por CharacterOptionsCommand (/dndoptions load) y por DndPaths para precargar solo
	//todos los .json de la carpeta al arrancar el servidor, sin que DndPaths tenga que depender de la capa
	//de comandos. REEMPLAZA la lista completa de la categoría, no la
	//extiende (ver comentario de clase).
	public static int loadFile(String category, Path file) throws IOException {
		String json = Files.readString(file);
		JsonArray array = JsonParser.parseString(json).getAsJsonArray();
		List<String> values = new ArrayList<>();
		for (var element : array) values.add(element.getAsString());
		replace(category, values);
		return values.size();
	}
}
