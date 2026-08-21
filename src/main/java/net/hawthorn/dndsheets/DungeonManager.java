package net.hawthorn.dndsheets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.ModList;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * <p>Todo lo que hace falta para convertir piezas de mazmorra ({@link DungeonPieceRegistry}) en un
 * dungeon jugable, apoyándose 100% en el sistema jigsaw vanilla en vez de un editor gráfico propio:</p>
 * <ul>
 * <li>El DM escanea cada sala con el bloque de estructura vanilla (modo SAVE) y marca las conexiones
 * con bloques jigsaw vanilla a mano — conectores normales con {@code Name=dndsheets:connector,
 * Target=dndsheets:connector}, y la pieza de arranque además con {@code Name=} {@link #START_JIGSAW_NAME}.</li>
 * <li>{@link #capturePiece} copia el .nbt ya escaneado al datapack de la partida actual.</li>
 * <li>{@link #publish} agrupa las piezas por pool, escribe un {@code template_pool} JSON por grupo y
 * corre {@code /reload} para que el registro dinámico de mundo los recoja — sin Codec/ReloadListener
 * propios, el pipeline de datapacks de Minecraft ya hace ese trabajo.</li>
 * <li>{@link #generate} dispara {@link JigsawPlacement#generateJigsaw} en la posición pedida.</li>
 * </ul>
 */
public class DungeonManager {
	public static final String PACK_NAME = "dndsheets_dungeon";
	public static final String POOL_NAMESPACE = DndsheetsMod.MODID;
	public static final String START_JIGSAW_NAME = "dndsheets:dungeon_start";
	public static final String CONNECTOR_NAME = "dndsheets:connector";

	//Mismo charset que el path de un ResourceLocation vanilla ([a-z0-9_.-] + '/' como separador), pero
	//además rechaza segmentos "." / ".." — el nombre de pool acaba en un new ResourceLocation(...) (que ya
	//valida el charset) y en un Path.resolve() de escritura de archivo (publish()), que NO valida nada:
	//sin este rechazo ".." permite escapar del datapack de la partida (path traversal). Único punto de
	//validación: todo handler de red que reciba un nombre de pool del cliente pasa por acá antes de
	//tocar DungeonManager.
	private static final Pattern POOL_NAME_CHARSET = Pattern.compile("[a-z0-9_./-]+");

	public static boolean isValidPoolName(String poolName) {
		if (poolName == null || !POOL_NAME_CHARSET.matcher(poolName).matches()) return false;
		for (String segment : poolName.split("/", -1)) {
			if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
		}
		return true;
	}

	//Mensaje compartido por los ~6 sitios que rechazan un pool inválido (comando y red) — el error más
	//común con diferencia es escribir el espacio de nombres de la ESTRUCTURA (p.ej. "dndsheets_dm:dungeon",
	//que el DM eligió libremente al nombrar el bloque de estructura, ver DUNGEON_GUIDE.md paso 1) en el
	//campo de POOL, que se autonamespacea solo a "dndsheets:X" y nunca debería llevar ":" escrito a mano.
	public static Component poolNameError(String poolName) {
		return Component.translatable("chat.dndsheets.dungeon.bad_pool_name",
			poolName, POOL_NAMESPACE, POOL_NAMESPACE + ":" + poolName);
	}

	//Puramente informativo (ver decisión del DM): sin llamadas reflectivas a la API de Structurize, solo
	//para mostrar un aviso en la GUI si no está instalado — el flujo con el bloque de estructura vanilla
	//funciona igual con o sin esto.
	public static boolean structurizeAvailable() {
		return ModList.get().isLoaded("structurize");
	}

	//Escribe Name/Target/Pool/Joint directo en el block entity del jigsaw, sin pasar por su GUI vanilla —
	//el DM ya no tiene que tipear a mano los 3 strings exactos con nuestro namespace (ver DungeonToolManager).
	//Target siempre es CONNECTOR_NAME: todo conector normal usa ese mismo Name, así que cualquier jigsaw
	//que lo apunte encaja con cualquier otro. Joint fijo en ALIGNED (no ROLLABLE): para salas hechas a mano
	//con una abertura fija es el caso común; ponytail: sin exponer el joint como opción, añadir un toggle
	//si algún DM necesita piezas que roten libremente.
	public static void configureJigsaw(JigsawBlockEntity jigsaw, String poolName, boolean isStart) {
		jigsaw.setName(new ResourceLocation(isStart ? START_JIGSAW_NAME : CONNECTOR_NAME));
		jigsaw.setTarget(new ResourceLocation(CONNECTOR_NAME));
		jigsaw.setPool(ResourceKey.create(Registries.TEMPLATE_POOL, new ResourceLocation(POOL_NAMESPACE, poolName)));
		jigsaw.setJoint(JigsawBlockEntity.JointType.ALIGNED);
		jigsaw.setChanged();

		//setChanged() solo marca el chunk para guardar a disco — no empuja el cambio al cliente. Sin esto
		//el DM veía el jigsaw "vacío" hasta cerrar y recargar el mundo (que sí relee de disco). Mismo patrón
		//que usa el propio StructureBlockEntity vanilla tras cambiar sus datos.
		if (jigsaw.getLevel() != null) {
			BlockState state = jigsaw.getBlockState();
			jigsaw.getLevel().sendBlockUpdated(jigsaw.getBlockPos(), state, state, 3);
		}
	}

	//Copia <mundo>/generated/<ns>/structures/<ruta>.nbt -> <mundo>/datapacks/dndsheets_dungeon/data/<ns>/structures/<ruta>.nbt
	//(mismo layout relativo que usa StructureTemplateManager para ambas raíces, solo cambia la base) y
	//registra la pieza. Optional.empty() = éxito; con mensaje = fallo claro para mostrar en chat/GUI.
	public static Optional<String> capturePiece(MinecraftServer server, DungeonPieceRegistry.DungeonPiece piece) {
		ResourceLocation structureId = ResourceLocation.tryParse(piece.structureId());
		if (structureId == null) {
			return Optional.of("\"" + piece.structureId() + "\" no es un id válido (usa el formato espacioDeNombres:ruta).");
		}

		Path src = server.getWorldPath(LevelResource.GENERATED_DIR)
			.resolve(structureId.getNamespace()).resolve("structures").resolve(structureId.getPath() + ".nbt");
		if (!Files.exists(src)) {
			return Optional.of("No encontré una estructura escaneada como " + structureId
				+ " — guárdala primero con un bloque de estructura (modo SAVE).");
		}

		Path dst = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK_NAME)
			.resolve("data").resolve(structureId.getNamespace()).resolve("structures").resolve(structureId.getPath() + ".nbt");
		try {
			Files.createDirectories(dst.getParent());
			Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			DndsheetsMod.LOGGER.error("dndsheets: no pude copiar la estructura de la pieza {}.", piece.id(), e);
			return Optional.of("No pude copiar el archivo de estructura: " + e.getMessage());
		}

		DungeonPieceRegistry.register(piece);
		DungeonPieceRegistry.save(server);
		return Optional.empty();
	}

	//¿Esta pieza tiene, DENTRO de su .nbt ya capturado, un jigsaw con Name=START_JIGSAW_NAME? Mismo chequeo
	//que hace vanilla — JigsawPlacement.addPieces busca ese jigsaw solo dentro de la pieza que el RNG haya
	//elegido para arrancar, ver el comentario grande en generate() — pero acá, ANTES de generar, para poder
	//avisar con precisión en vez de esperar a que vanilla falle y solo loguee un mensaje genérico.
	public static boolean hasStartJigsaw(ServerLevel level, DungeonPieceRegistry.DungeonPiece piece) {
		ResourceLocation structureId = ResourceLocation.tryParse(piece.structureId());
		if (structureId == null) return false;

		Optional<StructureTemplate> template = level.getStructureManager().get(structureId);
		return template.isPresent() && jigsawNames(template.get()).contains(START_JIGSAW_NAME);
	}

	/**
	 * <p>Los nombres de los jigsaw que hay dentro de un .nbt. Lo miran dos cosas por motivos distintos:
	 * {@link #hasStartJigsaw} para saber si una pieza puede abrir una mazmorra, y la importación para poder
	 * decirle al DM si lo que acaba de traer se puede conectar con algo.</p>
	 */
	public static List<String> jigsawNames(StructureTemplate template) {
		List<String> names = new ArrayList<>();
		for (StructureTemplate.StructureBlockInfo info : template.filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), Blocks.JIGSAW)) {
			if (info.nbt() != null) names.add(info.nbt().getString("name"));
		}
		return names;
	}

	// --- Traer construcciones de fuera ------------------------------------------------------------

	/**
	 * <p>Espacio de nombres de todo lo que el DM importa. Separado del suyo propio para que se vea de un
	 * vistazo qué salió de esta partida y qué vino de fuera.</p>
	 */
	public static final String IMPORT_NAMESPACE = "dndsheets_import";

	/** Lo que se sabe de un .nbt recién traído, que es justo lo que hay que contarle al DM. */
	public record Imported(ResourceLocation structureId, int width, int height, int depth, List<String> jigsaws) {
		public boolean canConnect() {
			return !jigsaws.isEmpty();
		}

		public boolean canStart() {
			return jigsaws.contains(START_JIGSAW_NAME);
		}
	}

	/**
	 * <p>Un nombre de archivo cualquiera convertido en ruta válida de {@link ResourceLocation}: minúsculas,
	 * sin acentos y sin nada fuera de {@code [a-z0-9_-]}.</p>
	 *
	 * <p>Hace falta porque los archivos que se descargan se llaman "Casa Grande (v2).nbt", y un
	 * ResourceLocation con un espacio o una tilde dentro no es que falle: es que
	 * {@code ResourceLocation.tryParse} devuelve null y la importación muere con un mensaje que habla de
	 * ids cuando el DM solo ha copiado un archivo. Mismo problema, y misma solución, que el
	 * {@code npc-capit-n} de {@code CharacterRules.npcIdFor}: los acentos se quitan ANTES de filtrar.</p>
	 */
	public static String structureNameFor(String fileName) {
		String withoutAccents = java.text.Normalizer.normalize(fileName == null ? "" : fileName,
			java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
		String slug = withoutAccents.toLowerCase(java.util.Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "_")
			.replaceAll("(^_|_$)", "");
		return slug.isEmpty() ? "estructura" : slug;
	}

	/**
	 * <p>Copia un {@code .nbt} de la biblioteca compartida ({@link DndPaths#STRUCTURES_DIR}) a la carpeta
	 * {@code generated/} de esta partida, que es de donde vanilla lee las estructuras guardadas y donde
	 * {@link #capturePiece} ya sabe buscarlas. Con eso, una construcción traída de fuera entra en el flujo
	 * de mazmorras existente sin que ese flujo tenga que enterarse.</p>
	 *
	 * <p>No se toca el archivo por dentro: un {@code .nbt} de estructura ya es el formato de Minecraft.
	 * Litematica y los editores de mapas exportan a él, así que traducir formatos ajenos —.schem,
	 * .litematic— sería escribir un conversor para llegar al mismo sitio al que su propio botón de
	 * exportar llega.</p>
	 */
	public static Optional<Imported> importStructure(ServerLevel level, String fileName, Consumer<String> onError) {
		Path source = DndPaths.STRUCTURES_DIR.resolve(fileName + ".nbt");
		if (!Files.exists(source)) {
			onError.accept("No encontré " + source.toAbsolutePath() + ". Copia ahí el .nbt y vuelve a intentarlo.");
			return Optional.empty();
		}

		ResourceLocation structureId = new ResourceLocation(IMPORT_NAMESPACE, structureNameFor(fileName));
		Path destination = level.getServer().getWorldPath(LevelResource.GENERATED_DIR)
			.resolve(structureId.getNamespace()).resolve("structures").resolve(structureId.getPath() + ".nbt");
		try {
			Files.createDirectories(destination.getParent());
			Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			DndsheetsMod.LOGGER.error("dndsheets: no pude copiar la estructura importada {}.", fileName, e);
			onError.accept("No pude copiar el archivo: " + e.getMessage());
			return Optional.empty();
		}

		//El gestor de plantillas cachea también los fallos: si alguien nombró este id antes de que el archivo
		//existiera, sin esto se queda con el "no existe" para siempre y la importación parece no haber pasado.
		level.getStructureManager().remove(structureId);

		Optional<StructureTemplate> template = level.getStructureManager().get(structureId);
		if (template.isEmpty()) {
			onError.accept("\"" + fileName + ".nbt\" no es una estructura de Minecraft válida "
				+ "(¿es un .schem o un .litematic? expórtalo a estructura de vanilla primero).");
			return Optional.empty();
		}

		Vec3i size = template.get().getSize();
		return Optional.of(new Imported(structureId, size.getX(), size.getY(), size.getZ(),
			jigsawNames(template.get())));
	}

	/** Pega una estructura ya importada en el mundo, para poder entrar en ella y ponerle los jigsaw. */
	public static boolean place(ServerLevel level, ResourceLocation structureId, BlockPos at) {
		Optional<StructureTemplate> template = level.getStructureManager().get(structureId);
		return template.isPresent()
			&& template.get().placeInWorld(level, at, at, new StructurePlaceSettings(), level.getRandom(), 2);
	}

	public static void removePiece(MinecraftServer server, String id) {
		DungeonPieceRegistry.remove(id);
		DungeonPieceRegistry.save(server);
	}

	//Agrupa por pool + arma el JSON del formato StructureTemplatePool.DIRECT_CODEC. Separado de publish()
	//para poder probarlo sin un MinecraftServer real (ver JsonContentSelfTest.checkDungeonPools) — es la
	//única lógica con ramas reales de esta feature, el resto son llamadas finas a APIs vanilla.
	public static Map<String, JsonObject> buildPoolJsons(Collection<DungeonPieceRegistry.DungeonPiece> pieces) {
		Map<String, List<DungeonPieceRegistry.DungeonPiece>> byPool = new LinkedHashMap<>();
		for (DungeonPieceRegistry.DungeonPiece piece : pieces) {
			//Pieza con structureId corrupto/mal escrito: se salta, no tumba el resto del pool.
			if (ResourceLocation.tryParse(piece.structureId()) == null) continue;
			byPool.computeIfAbsent(piece.pool(), key -> new ArrayList<>()).add(piece);
		}

		Map<String, JsonObject> result = new LinkedHashMap<>();
		for (Map.Entry<String, List<DungeonPieceRegistry.DungeonPiece>> entry : byPool.entrySet()) {
			JsonObject pool = new JsonObject();
			pool.addProperty("fallback", "minecraft:empty");

			JsonArray elements = new JsonArray();
			for (DungeonPieceRegistry.DungeonPiece piece : entry.getValue()) {
				JsonObject element = new JsonObject();
				element.addProperty("element_type", "minecraft:single_pool_element");
				element.addProperty("location", piece.structureId());
				element.addProperty("processors", "minecraft:empty");
				element.addProperty("projection", "rigid");

				JsonObject wrapper = new JsonObject();
				wrapper.add("element", element);
				//weight está acotado a [1,150] por StructureTemplatePool.DIRECT_CODEC: fuera de ese rango
				//el archivo entero falla a parsear en el reload, así que se acota acá antes de escribirlo.
				wrapper.addProperty("weight", Math.max(1, Math.min(150, piece.weight())));
				elements.add(wrapper);
			}
			pool.add("elements", elements);
			result.put(entry.getKey(), pool);
		}
		return result;
	}

	//Escribe pack.mcmeta (si falta) + un template_pool JSON por pool en el datapack local de la partida,
	//y corre /reload — que SOLO sirve acá para que el datapack recién creado quede en la lista de "packs
	//conocidos" del mundo (ver ReloadCommand.discoverNewPacks), NO para que los pools queden disponibles:
	//Registries.TEMPLATE_POOL es un registro "worldgen", y /reload nunca lo toca (ver el comentario en
	//generate() sobre ReloadableServerResources.listeners()). Un pool nuevo o editado solo queda visible
	//tras recargar el mundo de verdad (salir y volver a entrar, o reiniciar el servidor) — generate()
	//avisa de esto si el pool todavía no aparece. Devuelve null en éxito (de la ESCRITURA, no de que el
	//pool ya esté listo para generar), o un mensaje de error para mostrar al DM.
	public static String publish(ServerPlayer dm) {
		MinecraftServer server = dm.getServer();
		Path packRoot = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK_NAME);
		ensurePackMcmeta(packRoot);

		Map<String, JsonObject> pools = buildPoolJsons(DungeonPieceRegistry.all());
		if (pools.isEmpty()) return "No hay piezas registradas — añade alguna antes de publicar.";

		Path poolDir = packRoot.resolve("data").resolve(POOL_NAMESPACE).resolve("worldgen").resolve("template_pool");
		//Borra pools publicados en una pasada ANTERIOR que ya no tiene ninguna pieza (se le quitaron todas,
		//o se le cambió el pool a todas) — sin esto, un pool.json huérfano se quedaba para siempre en el
		//datapack, listo para confundir la próxima vez que alguien reutilizara ese nombre de pool.
		try (var existing = Files.list(poolDir)) {
			for (Path file : existing.filter(p -> p.toString().endsWith(".json")).toList()) {
				String name = file.getFileName().toString().replace(".json", "");
				if (!pools.containsKey(name)) Files.deleteIfExists(file);
			}
		} catch (IOException ignored) {
			//poolDir no existe todavía (primera publicación) — nada que limpiar.
		}


		for (Map.Entry<String, JsonObject> entry : pools.entrySet()) {
			Path poolFile = poolDir.resolve(entry.getKey() + ".json");
			try {
				Files.createDirectories(poolFile.getParent());
				try (OutputStream out = Files.newOutputStream(poolFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
					out.write(DndsheetsMod.PRETTY_GSON.toJson(entry.getValue()).getBytes());
				}
			} catch (IOException e) {
				DndsheetsMod.LOGGER.error("dndsheets: no pude escribir el pool {}.", entry.getKey(), e);
				return "No pude escribir el pool \"" + entry.getKey() + "\": " + e.getMessage();
			}
		}

		//Bloquea hasta terminar: se llama desde el hilo principal del servidor (comando o
		//NetworkUtil.handleOnServer), y MinecraftServer#reloadResources hace managedBlock en ese caso —
		//sin callbacks/CompletableFuture de por medio.
		server.getCommands().performPrefixedCommand(dm.createCommandSourceStack(), "reload");
		return null;
	}

	//publish() + JigsawPlacement.generateJigsaw en la posición pedida. false = fallo (mensaje ya enviado al DM).
	public static boolean generate(ServerPlayer dm, String poolName, int maxDepth, BlockPos pos) {
		String publishError = publish(dm);
		if (publishError != null) {
			dm.sendSystemMessage(Component.literal(publishError));
			return false;
		}

		//JigsawPlacement.addPieces (vanilla, confirmado leyendo su fuente) elige UNA pieza al azar —
		//pesada, no la primera— de TODO el pool y busca el jigsaw de inicio SOLO adentro de esa. Si el pool
		//mezcla la pieza de entrada con piezas normales que no tienen ese jigsaw, la generación tiene una
		//probabilidad real de fallar según a cuál le toque — no es un error de configuración intermitente,
		//es literalmente una tirada de dados con datos reales. Se valida ACÁ, con los .nbt ya capturados,
		//en vez de dejar que vanilla lo descubra y solo loguee un mensaje sin contexto.
		List<DungeonPieceRegistry.DungeonPiece> poolPieces = DungeonPieceRegistry.all().stream()
			.filter(piece -> piece.pool().equals(poolName))
			.toList();
		List<String> missingStart = new ArrayList<>();
		int withStart = 0;
		for (DungeonPieceRegistry.DungeonPiece piece : poolPieces) {
			if (hasStartJigsaw(dm.serverLevel(), piece)) withStart++;
			else missingStart.add(piece.id());
		}
		if (withStart == 0) {
			dm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.no_start_piece", poolName, START_JIGSAW_NAME));
			return false;
		}
		if (!missingStart.isEmpty()) {
			dm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.mixed_pool", poolName, missingStart.size(), String.join(", ", missingStart), poolPieces.size()));
			return false;
		}

		Optional<Holder.Reference<StructureTemplatePool>> holder = dm.serverLevel().registryAccess()
			.registryOrThrow(Registries.TEMPLATE_POOL)
			.getHolder(ResourceKey.create(Registries.TEMPLATE_POOL, new ResourceLocation(POOL_NAMESPACE, poolName)));

		if (holder.isEmpty()) {
			//NO es "revisá que alguna pieza lo use" — publish() ya escribió el JSON del pool en disco
			//correctamente en ese caso. El problema es que /reload (ver publish()) jamás repuebla
			//Registries.TEMPLATE_POOL: ReloadableServerResources.listeners() solo recarga tags/loot/recetas/
			//funciones/logros, ninguno de ellos "worldgen" — los pools de estructura se leen SOLO al
			//cargar el mundo. Un pool nuevo (o uno editado) queda escrito en el datapack pero invisible
			//para el registro en vivo hasta que el mundo se recarga de verdad.
			dm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.pool_not_loaded", poolName));
			return false;
		}

		boolean success = JigsawPlacement.generateJigsaw(dm.serverLevel(), holder.get(), new ResourceLocation(START_JIGSAW_NAME), maxDepth, pos, false);
		if (!success) {
			dm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.generation_failed", START_JIGSAW_NAME));
		}
		return success;
	}

	private static void ensurePackMcmeta(Path packRoot) {
		Path mcmeta = packRoot.resolve("pack.mcmeta");
		if (Files.exists(mcmeta)) return;

		try {
			Files.createDirectories(packRoot);
			JsonObject packSection = new JsonObject();
			packSection.addProperty("pack_format", 15);
			packSection.addProperty("description", "Piezas de mazmorra del DM (dndsheets)");
			JsonObject root = new JsonObject();
			root.add("pack", packSection);

			try (OutputStream out = Files.newOutputStream(mcmeta, StandardOpenOption.CREATE)) {
				out.write(DndsheetsMod.PRETTY_GSON.toJson(root).getBytes());
			}
		} catch (IOException e) {
			DndsheetsMod.LOGGER.error("dndsheets: no pude crear pack.mcmeta para el datapack de mazmorras.", e);
		}
	}
}
