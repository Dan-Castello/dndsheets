package net.hawthorn.dndsheets.dungeon;

import javax.annotation.Nullable;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.DndPaths;
import net.hawthorn.dndsheets.SheetLoader;

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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.pools.JigsawPlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.util.RandomSource;
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
 * <p>Everything needed to turn dungeon pieces ({@link DungeonPieceRegistry}) into a playable
 * dungeon, relying 100% on the vanilla jigsaw system instead of a custom graphical editor:</p>
 * <ul>
 * <li>The DM scans each room with the vanilla structure block (SAVE mode) and marks the connections
 * with vanilla jigsaw blocks by hand — regular connectors with {@code Name=dndsheets:connector,
 * Target=dndsheets:connector}, and the starting piece additionally with {@code Name=} {@link #START_JIGSAW_NAME}.</li>
 * <li>{@link #capturePiece} copies the already-scanned .nbt to the current game's datapack.</li>
 * <li>{@link #publish} groups the pieces by pool, writes a {@code template_pool} JSON per group and
 * runs {@code /reload} so the dynamic world registry picks them up — with no custom Codec/ReloadListener,
 * Minecraft's own datapack pipeline already does that work.</li>
 * <li>{@link #generate} fires {@link JigsawPlacement#generateJigsaw} at the requested position.</li>
 * </ul>
 */
public class DungeonManager {
	public static final String PACK_NAME = "dndsheets_dungeon";
	public static final String POOL_NAMESPACE = DndsheetsMod.MODID;
	public static final String START_JIGSAW_NAME = "dndsheets:dungeon_start";
	public static final String CONNECTOR_NAME = "dndsheets:connector";

	//Same charset as a vanilla ResourceLocation's path ([a-z0-9_.-] + '/' as separator), but also
	//rejects "." / ".." segments — the pool name ends up in a new ResourceLocation(...) (which already
	//validates the charset) and in a file-writing Path.resolve() (publish()), which validates NOTHING:
	//without this rejection ".." allows escaping the game's datapack (path traversal). Single
	//validation point: every network handler that receives a pool name from the client goes through
	//here before touching DungeonManager.
	private static final Pattern POOL_NAME_CHARSET = Pattern.compile("[a-z0-9_./-]+");

	public static boolean isValidPoolName(String poolName) {
		if (poolName == null || !POOL_NAME_CHARSET.matcher(poolName).matches()) return false;
		for (String segment : poolName.split("/", -1)) {
			if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
		}
		return true;
	}

	//Message shared by the ~6 places that reject an invalid pool (command and network) — by far the
	//most common mistake is writing the STRUCTURE's namespace (e.g. "dndsheets_dm:dungeon", which the
	//DM chose freely when naming the structure block) in the
	//POOL field, which auto-namespaces itself to "dndsheets:X" and should never have a hand-typed ":".
	public static Component poolNameError(String poolName) {
		return Component.translatable("chat.dndsheets.dungeon.bad_pool_name",
			poolName, POOL_NAMESPACE, POOL_NAMESPACE + ":" + poolName);
	}

	//Purely informational (see the DM's decision): no reflective calls into the Structurize API, just
	//to show a warning in the GUI if it isn't installed — the flow with the vanilla structure block
	//works the same with or without this.
	public static boolean structurizeAvailable() {
		return ModList.get().isLoaded("structurize");
	}

	//Writes Name/Target/Pool/Joint directly into the jigsaw block entity, bypassing its vanilla GUI —
	//the DM no longer has to hand-type the 3 exact strings with our namespace (see DungeonToolManager).
	//Target is always CONNECTOR_NAME: every regular connector uses that same Name, so any jigsaw that
	//points to it fits any other one. Joint is fixed at ALIGNED (not ROLLABLE): the common case for
	//hand-built rooms with a fixed opening; ponytail: joint isn't exposed as an option, add a toggle
	//if some DM needs freely-rotating pieces.
	public static void configureJigsaw(JigsawBlockEntity jigsaw, String poolName, boolean isStart) {
		jigsaw.setName(new ResourceLocation(isStart ? START_JIGSAW_NAME : CONNECTOR_NAME));
		jigsaw.setTarget(new ResourceLocation(CONNECTOR_NAME));
		jigsaw.setPool(ResourceKey.create(Registries.TEMPLATE_POOL, new ResourceLocation(POOL_NAMESPACE, poolName)));
		jigsaw.setJoint(JigsawBlockEntity.JointType.ALIGNED);
		jigsaw.setChanged();

		//setChanged() only marks the chunk for saving to disk — it doesn't push the change to the client.
		//Without this the DM would see the jigsaw as "empty" until closing and reloading the world (which
		//does re-read from disk). Same pattern the vanilla StructureBlockEntity itself uses after changing
		//its data.
		if (jigsaw.getLevel() != null) {
			BlockState state = jigsaw.getBlockState();
			jigsaw.getLevel().sendBlockUpdated(jigsaw.getBlockPos(), state, state, 3);
		}
	}

	//Copies <world>/generated/<ns>/structures/<path>.nbt -> <world>/datapacks/dndsheets_dungeon/data/<ns>/structures/<path>.nbt
	//(same relative layout StructureTemplateManager uses for both roots, only the base changes) and
	//registers the piece. Optional.empty() = success; with a message = a clear failure to show in chat/GUI.
	public static Optional<String> capturePiece(MinecraftServer server, DungeonPieceRegistry.DungeonPiece piece) {
		ResourceLocation structureId = ResourceLocation.tryParse(piece.structureId());
		if (structureId == null) {
			return Optional.of("\"" + piece.structureId() + "\" is not a valid id (use the namespace:path format).");
		}

		Path src = server.getWorldPath(LevelResource.GENERATED_DIR)
			.resolve(structureId.getNamespace()).resolve("structures").resolve(structureId.getPath() + ".nbt");
		if (!Files.exists(src)) {
			return Optional.of("No scanned structure found as " + structureId
				+ " — save it first with a structure block (SAVE mode).");
		}

		Path dst = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK_NAME)
			.resolve("data").resolve(structureId.getNamespace()).resolve("structures").resolve(structureId.getPath() + ".nbt");
		try {
			Files.createDirectories(dst.getParent());
			Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			DndsheetsMod.LOGGER.error("dndsheets: could not copy the structure of piece {}.", piece.id(), e);
			return Optional.of("Could not copy the structure file: " + e.getMessage());
		}

		DungeonPieceRegistry.register(piece);
		DungeonPieceRegistry.save(server);
		return Optional.empty();
	}

	//Does this piece have, INSIDE its already-captured .nbt, a jigsaw with Name=START_JIGSAW_NAME? Same
	//check vanilla does — JigsawPlacement.addPieces looks for that jigsaw only inside the piece the RNG
	//picked to start with, see the large comment in generate() — but here, BEFORE generating, so we can
	//warn precisely instead of waiting for vanilla to fail and just log a generic message.
	public static boolean hasStartJigsaw(ServerLevel level, DungeonPieceRegistry.DungeonPiece piece) {
		ResourceLocation structureId = ResourceLocation.tryParse(piece.structureId());
		if (structureId == null) return false;

		Optional<StructureTemplate> template = level.getStructureManager().get(structureId);
		return template.isPresent() && jigsawNames(template.get()).contains(START_JIGSAW_NAME);
	}

	/**
	 * <p>The names of the jigsaws found inside a .nbt. Two things look at this for different reasons:
	 * {@link #hasStartJigsaw} to know whether a piece can open a dungeon, and the import flow so it can
	 * tell the DM whether what was just brought in can connect to anything.</p>
	 */
	public static List<String> jigsawNames(StructureTemplate template) {
		List<String> names = new ArrayList<>();
		for (StructureTemplate.StructureBlockInfo info : template.filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), Blocks.JIGSAW)) {
			if (info.nbt() != null) names.add(info.nbt().getString("name"));
		}
		return names;
	}

	// --- Bringing in outside builds ------------------------------------------------------------

	/**
	 * <p>Namespace for everything the DM imports. Kept separate from their own so it's obvious at a
	 * glance what came from this game and what came from outside.</p>
	 */
	public static final String IMPORT_NAMESPACE = "dndsheets_import";

	/** What's known about a freshly imported .nbt — exactly what needs to be told to the DM. */
	public record Imported(ResourceLocation structureId, int width, int height, int depth, List<String> jigsaws) {
		public boolean canConnect() {
			return !jigsaws.isEmpty();
		}

		public boolean canStart() {
			return jigsaws.contains(START_JIGSAW_NAME);
		}
	}

	/**
	 * <p>Any arbitrary file name turned into a valid {@link ResourceLocation} path: lowercase, no
	 * accents, and nothing outside {@code [a-z0-9_-]}.</p>
	 *
	 * <p>Needed because downloaded files are named things like "Casa Grande (v2).nbt", and a
	 * ResourceLocation with a space or an accent in it doesn't just fail cleanly:
	 * {@code ResourceLocation.tryParse} returns null and the import dies with a message about ids when
	 * the DM only copied a file. Same problem, and same fix, as the
	 * {@code npc-capit-n} case in {@code CharacterRules.npcIdFor}: accents are stripped BEFORE filtering.</p>
	 */
	public static String structureNameFor(String fileName) {
		String withoutAccents = java.text.Normalizer.normalize(fileName == null ? "" : fileName,
			java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
		String slug = withoutAccents.toLowerCase(java.util.Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "_")
			.replaceAll("(^_|_$)", "");
		return slug.isEmpty() ? "structure" : slug;
	}

	/**
	 * <p>Copies a {@code .nbt} from the shared library ({@link DndPaths#STRUCTURES_DIR}) into this
	 * game's {@code generated/} folder, which is where vanilla reads saved structures from and where
	 * {@link #capturePiece} already knows to look for them. That way, a build brought in from outside
	 * enters the existing dungeon flow without that flow ever needing to know.</p>
	 *
	 * <p>The file's contents aren't touched: a structure {@code .nbt} is already Minecraft's own
	 * format. Litematica and map editors export to it, so translating other formats —.schem,
	 * .litematic— would mean writing a converter just to reach the same place their own export
	 * button already reaches.</p>
	 */
	public static Optional<Imported> importStructure(ServerLevel level, String fileName, Consumer<String> onError) {
		Path source = DndPaths.STRUCTURES_DIR.resolve(fileName + ".nbt");
		if (!Files.exists(source)) {
			onError.accept("Could not find " + source.toAbsolutePath() + ". Copy the .nbt there and try again.");
			return Optional.empty();
		}

		ResourceLocation structureId = new ResourceLocation(IMPORT_NAMESPACE, structureNameFor(fileName));
		Path destination = level.getServer().getWorldPath(LevelResource.GENERATED_DIR)
			.resolve(structureId.getNamespace()).resolve("structures").resolve(structureId.getPath() + ".nbt");
		try {
			Files.createDirectories(destination.getParent());
			Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			DndsheetsMod.LOGGER.error("dndsheets: could not copy the imported structure {}.", fileName, e);
			onError.accept("Could not copy the file: " + e.getMessage());
			return Optional.empty();
		}

		//The template manager also caches failures: if something named this id before the file existed,
		//without this it would keep the "doesn't exist" result forever and the import would appear to do nothing.
		level.getStructureManager().remove(structureId);

		Optional<StructureTemplate> template = level.getStructureManager().get(structureId);
		if (template.isEmpty()) {
			onError.accept("\"" + fileName + ".nbt\" is not a valid Minecraft structure "
				+ "(is it a .schem or a .litematic? export it to a vanilla structure first).");
			return Optional.empty();
		}

		Vec3i size = template.get().getSize();
		return Optional.of(new Imported(structureId, size.getX(), size.getY(), size.getZ(),
			jigsawNames(template.get())));
	}

	/** Pastes an already-imported structure into the world, so it can be entered and given jigsaws. */
	public static boolean place(ServerLevel level, ResourceLocation structureId, BlockPos at) {
		Optional<StructureTemplate> template = level.getStructureManager().get(structureId);
		return template.isPresent()
			&& template.get().placeInWorld(level, at, at, new StructurePlaceSettings(), level.getRandom(), 2);
	}

	public static void removePiece(MinecraftServer server, String id) {
		DungeonPieceRegistry.remove(id);
		DungeonPieceRegistry.save(server);
	}

	//Groups by pool + builds the JSON in the StructureTemplatePool.DIRECT_CODEC format. Kept separate
	//from publish() so it can be tested without a real MinecraftServer (see
	//JsonContentSelfTest.checkDungeonPools) — it's the only logic with real branches in this feature,
	//the rest are thin calls into vanilla APIs.
	public static Map<String, JsonObject> buildPoolJsons(Collection<DungeonPieceRegistry.DungeonPiece> pieces) {
		Map<String, List<DungeonPieceRegistry.DungeonPiece>> byPool = new LinkedHashMap<>();
		for (DungeonPieceRegistry.DungeonPiece piece : pieces) {
			//A piece with a corrupted/malformed structureId: skip it, don't take down the rest of the pool.
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
				//weight is clamped to [1,150] by StructureTemplatePool.DIRECT_CODEC: outside that range
				//the whole file fails to parse on reload, so it's clamped here before writing it.
				wrapper.addProperty("weight", Math.max(1, Math.min(150, piece.weight())));
				elements.add(wrapper);
			}
			pool.add("elements", elements);
			result.put(entry.getKey(), pool);
		}
		return result;
	}

	//Writes pack.mcmeta (if missing) + one template_pool JSON per pool into the game's local datapack,
	//and runs /reload — which ONLY serves here to get the freshly created datapack onto the world's list
	//of "known packs" (see ReloadCommand.discoverNewPacks), NOT to make the pools available:
	//Registries.TEMPLATE_POOL is a "worldgen" registry, and /reload never touches it (see the comment in
	//generate() about ReloadableServerResources.listeners()). A new or edited pool only becomes visible
	//after a real world reload (leave and rejoin, or restart the server) — generate() warns about this
	//if the pool still doesn't show up. Returns null on success (of the WRITE, not of the pool already
	//being ready to generate), or an error message to show the DM.
	@Nullable
	public static String publish(ServerPlayer dm) {
		MinecraftServer server = dm.getServer();
		Path packRoot = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK_NAME);
		ensurePackMcmeta(packRoot);

		Map<String, JsonObject> pools = buildPoolJsons(DungeonPieceRegistry.all());
		if (pools.isEmpty()) return "No pieces registered — add some before publishing.";

		Path poolDir = packRoot.resolve("data").resolve(POOL_NAMESPACE).resolve("worldgen").resolve("template_pool");
		//Deletes pools published in a PREVIOUS pass that no longer have any pieces (all of them were
		//removed, or all had their pool changed) — without this, an orphaned pool.json would stay in the
		//datapack forever, ready to confuse the next time someone reused that pool name.
		try (var existing = Files.list(poolDir)) {
			for (Path file : existing.filter(p -> p.toString().endsWith(".json")).toList()) {
				String name = file.getFileName().toString().replace(".json", "");
				if (!pools.containsKey(name)) Files.deleteIfExists(file);
			}
		} catch (IOException ignored) {
			//poolDir doesn't exist yet (first publish) — nothing to clean up.
		}


		for (Map.Entry<String, JsonObject> entry : pools.entrySet()) {
			Path poolFile = poolDir.resolve(entry.getKey() + ".json");
			try {
				Files.createDirectories(poolFile.getParent());
				try (OutputStream out = Files.newOutputStream(poolFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
					out.write(DndsheetsMod.PRETTY_GSON.toJson(entry.getValue()).getBytes());
				}
			} catch (IOException e) {
				DndsheetsMod.LOGGER.error("dndsheets: could not write pool {}.", entry.getKey(), e);
				return "Could not write pool \"" + entry.getKey() + "\": " + e.getMessage();
			}
		}

		//Blocks until done: called from the server's main thread (command or
		//NetworkUtil.handleOnServer), and MinecraftServer#reloadResources does a managedBlock in that
		//case — no callbacks/CompletableFuture involved.
		server.getCommands().performPrefixedCommand(dm.createCommandSourceStack(), "reload");
		return null;
	}

	//publish() + JigsawPlacement.generateJigsaw at the requested position. false = failure (message already sent to the DM).
	public static boolean generate(ServerPlayer dm, String poolName, int maxDepth, BlockPos pos) {
		String publishError = publish(dm);
		if (publishError != null) {
			dm.sendSystemMessage(Component.literal(publishError));
			return false;
		}

		//JigsawPlacement.addPieces (vanilla, confirmed by reading its source) picks ONE random piece —
		//weighted, not the first one — from the WHOLE pool and looks for the starting jigsaw ONLY inside
		//that one. If the pool mixes the entry piece with regular pieces that don't have that jigsaw,
		//generation has a real chance of failing depending on which one gets picked — this isn't an
		//intermittent config error, it's literally a dice roll with real data. Validated HERE, using the
		//already-captured .nbt files, instead of letting vanilla discover it and just log a message with
		//no context.
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
			//NOT "check that some piece uses it" — publish() already wrote the pool's JSON to disk
			//correctly in that case. The problem is that /reload (see publish()) never repopulates
			//Registries.TEMPLATE_POOL: ReloadableServerResources.listeners() only reloads tags/loot/
			//recipes/functions/advancements, none of them "worldgen" — structure pools are read ONLY when
			//the world loads. A new (or edited) pool ends up written to the datapack but invisible to the
			//live registry until the world is really reloaded.
			dm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.pool_not_loaded", poolName));
			return false;
		}

		//JigsawPlacement.generateJigsaw isn't called (it does exactly this but keeps the pieces to
		//itself) because EncounterPopulator needs the real bounding box of each room to populate it — and
		//since jigsaw placement is random, it's IMPOSSIBLE to recompute that layout afterward with a
		//second call: it would produce a different dungeon than the one that was actually just planted in
		//the world. So its body is replicated here (confirmed by reading its mapped source) using the
		//same public JigsawPlacement.addPieces, instead of duplicating the generation.
		ServerLevel level = dm.serverLevel();
		ChunkGenerator chunkGenerator = level.getChunkSource().getGenerator();
		StructureTemplateManager structureTemplateManager = level.getStructureManager();
		StructureManager structureManager = level.structureManager();
		RandomSource random = level.getRandom();
		Structure.GenerationContext context = new Structure.GenerationContext(level.registryAccess(), chunkGenerator,
			chunkGenerator.getBiomeSource(), level.getChunkSource().randomState(), structureTemplateManager,
			level.getSeed(), new ChunkPos(pos), level, biome -> true);

		Optional<Structure.GenerationStub> stub = JigsawPlacement.addPieces(context, holder.get(),
			Optional.of(new ResourceLocation(START_JIGSAW_NAME)), maxDepth, pos, false, Optional.empty(), 128);
		if (stub.isEmpty()) {
			dm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.generation_failed", START_JIGSAW_NAME));
			return false;
		}

		StructurePiecesBuilder piecesBuilder = stub.get().getPiecesBuilder();
		List<BoundingBox> roomBounds = new ArrayList<>();
		for (StructurePiece piece : piecesBuilder.build().pieces()) {
			if (!(piece instanceof PoolElementStructurePiece poolPiece)) continue;
			poolPiece.place(level, structureManager, chunkGenerator, random, BoundingBox.infinite(), pos, false);
			roomBounds.add(poolPiece.getBoundingBox());
		}

		//The generating player's character is the only level reference available — a group without a DM
		//that generates its own dungeon normally does so with its own active character.
		int playerLevel = SheetLoader.characterLevelOf(SheetLoader.getServerSheet(dm.getStringUUID()), dm);
		EncounterPopulator.populate(level, roomBounds, playerLevel);

		return true;
	}

	private static void ensurePackMcmeta(Path packRoot) {
		Path mcmeta = packRoot.resolve("pack.mcmeta");
		if (Files.exists(mcmeta)) return;

		try {
			Files.createDirectories(packRoot);
			JsonObject packSection = new JsonObject();
			packSection.addProperty("pack_format", 15);
			packSection.addProperty("description", "Dungeon pieces from the DM (dndsheets)");
			JsonObject root = new JsonObject();
			root.add("pack", packSection);

			try (OutputStream out = Files.newOutputStream(mcmeta, StandardOpenOption.CREATE)) {
				out.write(DndsheetsMod.PRETTY_GSON.toJson(root).getBytes());
			}
		} catch (IOException e) {
			DndsheetsMod.LOGGER.error("dndsheets: could not create pack.mcmeta for the dungeon datapack.", e);
		}
	}
}
