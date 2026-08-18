package net.hawthorn.dndsheets.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.hawthorn.dndsheets.DndPaths;
import net.hawthorn.dndsheets.EncounterRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * <p>{@code /dndencounters}: grupos de monstruos preparados antes de la sesión y soltados de una vez.</p>
 *
 * <p>El formato del archivo es el de siempre —un array de objetos con {@code id} en
 * {@code <mundo>/dndsheets/encounters/}, cargado solo al arrancar— y la composición se escribe como texto:</p>
 *
 * <pre>
 * {
 *   "id": "emboscada_goblin",
 *   "name": "Emboscada de goblins",
 *   "monsters": ["dndsheets:goblin x4", "dndsheets:wolf x2"]
 * }
 * </pre>
 *
 * <p>Sin {@code load} no hace falta tocar nada: la carpeta se lee entera al arrancar el servidor, igual que
 * el resto del contenido. {@code load} existe para recargar en caliente lo que acabas de editar.</p>
 */
@Mod.EventBusSubscriber
public class EncounterCommand {
	private static final Path ENCOUNTERS_DIR = DndPaths.ENCOUNTERS_DIR;

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndencounters")
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("load")
				.then(Commands.argument("archivo", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DndPaths.jsonFileNames(ENCOUNTERS_DIR), builder))
					.executes(EncounterCommand::load)))
			.then(Commands.literal("list").executes(EncounterCommand::list))
			.then(Commands.literal("spawn")
				.then(Commands.argument("encuentroId", ResourceLocationArgument.id())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(EncounterRegistry.ids(), builder))
					.executes(ctx -> spawn(ctx, ctx.getSource().getPosition()))
					//Con posición explícita: preparar la emboscada al otro lado de la puerta sin tener que ir
					//hasta allí, que es justo cuando un DM quiere un encuentro guardado.
					.then(Commands.argument("donde", Vec3Argument.vec3())
						.executes(ctx -> spawn(ctx, Vec3Argument.getVec3(ctx, "donde")))))));
	}

	private static int load(CommandContext<CommandSourceStack> ctx) {
		String fileName = StringArgumentType.getString(ctx, "archivo");
		Path file = ENCOUNTERS_DIR.resolve(fileName + ".json");

		if (!Files.exists(file)) {
			ctx.getSource().sendFailure(Component.literal("No encontré " + file.toAbsolutePath()));
			return 0;
		}

		try {
			int count = EncounterRegistry.loadFile(file);
			ctx.getSource().sendSuccess(() -> Component.literal("Cargados " + count + " encuentros desde " + fileName + ".json"), true);
			return count;
		} catch (IOException | RuntimeException e) {
			ctx.getSource().sendFailure(Component.literal("No pude leer " + fileName + ".json: " + e.getMessage()));
			return 0;
		}
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		Set<String> ids = EncounterRegistry.ids();
		if (ids.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.literal(
				"No hay encuentros. Créalos en el Panel de DM (Crear contenido > Encuentros) o en "
					+ ENCOUNTERS_DIR.toAbsolutePath() + "."), false);
			return 0;
		}
		for (String id : ids) {
			EncounterRegistry.Encounter encounter = EncounterRegistry.get(id);
			ctx.getSource().sendSuccess(() -> Component.literal(
				id + " — " + encounter.name() + ": " + EncounterRegistry.describe(encounter)), false);
		}
		return ids.size();
	}

	private static int spawn(CommandContext<CommandSourceStack> ctx, Vec3 where) {
		String id = ResourceLocationArgument.getId(ctx, "encuentroId").toString();
		EncounterRegistry.Encounter encounter = EncounterRegistry.get(id);
		if (encounter == null) {
			ctx.getSource().sendFailure(Component.literal("No conozco el encuentro \"" + id + "\". Míralos con /dndencounters list."));
			return 0;
		}

		ServerLevel level = ctx.getSource().getLevel();
		int spawned = EncounterRegistry.spawn(level, where, encounter);
		int total = encounter.total();

		if (spawned == 0) {
			ctx.getSource().sendFailure(Component.literal(
				"No se invocó nada: los monstruos de \"" + encounter.name() + "\" no existen. Míralos con /dndmonsters list."));
			return 0;
		}

		//Se dice cuántos faltan y no solo cuántos salieron: un encuentro al que le falta el jefe porque su id
		//está mal escrito se juega igual y nadie se entera hasta después.
		String missing = spawned < total ? " (faltan " + (total - spawned) + ", ids que no existen)" : "";
		ctx.getSource().sendSuccess(() -> Component.literal(
			encounter.name() + ": " + spawned + " monstruos" + missing + ". La iniciativa arranca sola con el primer golpe."), true);
		return spawned;
	}
}
