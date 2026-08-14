package net.hawthorn.dndsheets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
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
	//y corre /reload. Devuelve null en éxito, o un mensaje de error para mostrar al DM.
	public static String publish(ServerPlayer dm) {
		MinecraftServer server = dm.getServer();
		Path packRoot = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK_NAME);
		ensurePackMcmeta(packRoot);

		Map<String, JsonObject> pools = buildPoolJsons(DungeonPieceRegistry.all());
		if (pools.isEmpty()) return "No hay piezas registradas — añade alguna antes de publicar.";

		Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();
		for (Map.Entry<String, JsonObject> entry : pools.entrySet()) {
			Path poolFile = packRoot.resolve("data").resolve(POOL_NAMESPACE).resolve("worldgen").resolve("template_pool").resolve(entry.getKey() + ".json");
			try {
				Files.createDirectories(poolFile.getParent());
				try (OutputStream out = Files.newOutputStream(poolFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
					out.write(prettyGson.toJson(entry.getValue()).getBytes());
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

		Optional<Holder.Reference<StructureTemplatePool>> holder = dm.serverLevel().registryAccess()
			.registryOrThrow(Registries.TEMPLATE_POOL)
			.getHolder(ResourceKey.create(Registries.TEMPLATE_POOL, new ResourceLocation(POOL_NAMESPACE, poolName)));

		if (holder.isEmpty()) {
			dm.sendSystemMessage(Component.literal("No encontré el pool \"" + poolName + "\" tras publicar — revisa que alguna pieza lo use."));
			return false;
		}

		boolean success = JigsawPlacement.generateJigsaw(dm.serverLevel(), holder.get(), new ResourceLocation(START_JIGSAW_NAME), maxDepth, pos, false);
		if (!success) {
			dm.sendSystemMessage(Component.literal("La generación falló — revisa que la pieza de arranque tenga un jigsaw con Name=" + START_JIGSAW_NAME + "."));
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
				out.write(new GsonBuilder().setPrettyPrinting().create().toJson(root).getBytes());
			}
		} catch (IOException e) {
			DndsheetsMod.LOGGER.error("dndsheets: no pude crear pack.mcmeta para el datapack de mazmorras.", e);
		}
	}
}
