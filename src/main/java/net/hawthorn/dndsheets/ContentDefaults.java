package net.hawthorn.dndsheets;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * <p>Mantiene al día el pack de contenido que trae el mod dentro de la carpeta del mundo.</p>
 *
 * <p>Antes esto era una siembra <b>única</b>, y solo si la carpeta estaba vacía ({@code seedDefaultsIfEmpty}).
 * El efecto era que la copia del mundo se congelaba en la versión del día en que se creó la partida: los
 * hechizos nuevos, las resistencias añadidas a un monstruo o el escalado por nivel de espacio no llegaban
 * <b>nunca</b> a un mundo que ya existía. Se descubrió por el síntoma: subir el nivel de un conjuro no hacía
 * nada en una partida en curso, porque el servidor seguía cargando un pack anterior a esa regla.</p>
 *
 * <p>Ahora el pack del mod tiene nombre propio ({@link #FILE}) y se reescribe en cada arranque. Lo que el DM
 * escriba en cualquier otro archivo de la carpeta se carga <b>después</b> (ver {@code DndPaths.autoLoadAll})
 * y pisa por id lo que traiga el nuestro — ese orden es lo que hace seguro reescribirlo.</p>
 *
 * <p>Vive fuera de {@link DndPaths} por una razón concreta: {@code DndPaths} resuelve sus rutas contra
 * {@code SheetLoader.GAME_DIR}, que solo existe dentro del juego, así que su inicialización estática revienta
 * fuera de él. Separado, esto se comprueba de verdad en el self-test con una carpeta temporal, que es lo
 * mínimo que merece la lógica que decide qué archivo de contenido gana.</p>
 */
public final class ContentDefaults {

	/**
	 * <p>Nombre reservado del pack del mod dentro de cada carpeta de contenido. <b>Se reescribe en cada
	 * arranque</b>, así que no es sitio para escribir nada a mano: cualquier otro {@code .json} de la
	 * carpeta es del DM y no se toca nunca.</p>
	 */
	public static final String FILE = "mod_defaults.json";

	private ContentDefaults() {
	}

	/**
	 * <p>Deja el pack del mod al día en {@code dir}, apartando antes la copia que sembró la versión
	 * anterior si todavía está ahí.</p>
	 *
	 * <p>No registra nada en el log a propósito: tocar {@code DndsheetsMod.LOGGER} inicializa la clase
	 * entera del mod, y con ella el canal de red de Forge, que fuera del juego no existe. Quien llama pone
	 * el aviso (ver {@code DndPaths.refreshDefaultsLogging}).</p>
	 *
	 * @return el pack antiguo que se ha apartado, o {@code null} si no había ninguno.
	 */
	public static Path refresh(Path dir, String resourceFileName) throws IOException {
		Path retired = retireLegacySeed(dir, resourceFileName);

		try (InputStream in = ContentDefaults.class.getResourceAsStream("/dndsheets/defaults/" + resourceFileName)) {
			if (in != null) Files.copy(in, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING);
		}
		return retired;
	}

	/**
	 * <p>Aparta la copia sembrada por la versión antigua ({@code spells.json} y compañía). Sin esto seguiría
	 * cargándose <b>después</b> del pack nuevo y lo pisaría entero por id: justo el contenido viejo que
	 * veníamos a arreglar, ahora ganando a propósito.</p>
	 *
	 * <p>Se renombra en vez de borrarse, y solo la primera vez (mientras no exista aún el pack con nombre
	 * propio). Un DM que hubiera escrito a mano un archivo con ese nombre no pierde nada: sigue ahí, con
	 * extensión {@code .old} para que deje de autocargarse, y el aviso del log dice dónde está y qué hacer.
	 * Pasada esa primera vez, un archivo con ese nombre es del DM y no se vuelve a tocar.</p>
	 */
	private static Path retireLegacySeed(Path dir, String resourceFileName) throws IOException {
		Path legacy = dir.resolve(resourceFileName);
		if (Files.exists(dir.resolve(FILE)) || !Files.exists(legacy)) return null;

		Path retired = dir.resolve(resourceFileName + ".old");
		Files.move(legacy, retired, StandardCopyOption.REPLACE_EXISTING);
		return retired;
	}
}
