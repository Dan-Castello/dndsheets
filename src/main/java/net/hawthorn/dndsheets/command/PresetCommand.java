package net.hawthorn.dndsheets.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.DndPaths;
import net.hawthorn.dndsheets.PresetManager;
import net.hawthorn.dndsheets.PresetRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;

/**
 * <p>Carga presets de clase desde JSON en {@code <carpeta del mundo>/dndsheets/presets/<archivo>.json}
 * (ver {@link PresetRegistry} para el formato) y los aplica a la hoja de un jugador.</p>
 *
 * <p>Formato del JSON, un array de objetos:</p>
 * <pre>
 * [{
 *   "id": "fighter", "name": "Guerrero", "hitDiceType": "1d10",
 *   "abilities": { "str": 15, "dex": 13, "con": 14, "int": 8, "wis": 12, "cha": 10 },
 *   "startingWeapon": "minecraft:iron_sword",
 *   "startingGear": ["minecraft:chainmail_chestplate", "minecraft:shield"]
 * }]
 * </pre>
 */
@Mod.EventBusSubscriber
public class PresetCommand {
	private static final Path PRESETS_DIR = DndPaths.PRESETS_DIR;

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndpresets")
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("load")
				.then(Commands.argument("archivo", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DndPaths.jsonFileNames(PRESETS_DIR), builder))
					.executes(PresetCommand::load)))
			.then(Commands.literal("list").executes(PresetCommand::list))
			.then(Commands.literal("apply")
				.then(Commands.argument("jugadores", EntityArgument.players())
					.then(Commands.argument("presetId", ResourceLocationArgument.id())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(PresetRegistry.ids(), builder))
						.executes(PresetCommand::apply)))));
	}

	private static int load(CommandContext<CommandSourceStack> ctx) {
		String fileName = StringArgumentType.getString(ctx, "archivo");
		Path file = PRESETS_DIR.resolve(fileName + ".json");

		if (!Files.exists(file)) {
			ctx.getSource().sendFailure(Component.literal("No encontré " + file.toAbsolutePath()));
			return 0;
		}

		try {
			int count = PresetRegistry.loadFile(file);
			ctx.getSource().sendSuccess(() -> Component.literal("Cargados " + count + " presets desde " + fileName + ".json"), true);
			return count;
		} catch (IOException | RuntimeException e) {
			ctx.getSource().sendFailure(Component.literal("No pude leer " + fileName + ".json: " + e.getMessage()));
			return 0;
		}
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		Set<String> ids = PresetRegistry.ids();
		ctx.getSource().sendSuccess(() -> Component.literal("Presets cargados (" + ids.size() + "): " + String.join(", ", ids)), false);
		return ids.size();
	}

	private static int apply(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String presetId = ResourceLocationArgument.getId(ctx, "presetId").toString();
		if (PresetRegistry.get(presetId) == null) {
			ctx.getSource().sendFailure(Component.literal("No conozco el preset \"" + presetId + "\". Cárgalo con /dndpresets load."));
			return 0;
		}

		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "jugadores");
		for (ServerPlayer target : targets) {
			PresetManager.applyPreset(target, presetId);
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Preset aplicado a " + targets.size() + " jugador(es)."), true);
		return targets.size();
	}
}
