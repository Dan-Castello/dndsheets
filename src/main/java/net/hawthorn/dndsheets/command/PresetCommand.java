package net.hawthorn.dndsheets.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.DndPaths;
import net.hawthorn.dndsheets.DndsheetsMod;
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

import java.nio.file.Path;
import java.util.Collection;

/**
 * <p>Loads class presets from JSON at {@code <world folder>/dndsheets/presets/<file>.json}
 * (see {@link PresetRegistry} for the format) and applies them to a player's sheet.</p>
 *
 * <p>JSON format, an array of objects:</p>
 * <pre>
 * [{
 *   "id": "fighter", "name": "Fighter", "hitDiceType": "1d10",
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
			.requires(source -> DndsheetsMod.canActAsDm(source))
			.then(ContentCommands.loadBranch(PRESETS_DIR, PresetRegistry::loadFile, "presets"))
			.then(ContentCommands.listBranch(PresetRegistry::ids, "Presets"))
			.then(Commands.literal("apply")
				.then(Commands.argument("players", EntityArgument.players())
					.then(Commands.argument("presetId", ResourceLocationArgument.id())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(PresetRegistry.ids(), builder))
						.executes(PresetCommand::apply)))));
	}



	private static int apply(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		//The "minecraft:" that ResourceLocationArgument prepends to a namespace-less id ("fighter") is
		//resolved by NamedRegistry.get, which is what every content lookup goes through — the retry that
		//used to live loose in this command is no longer needed here.
		String presetId = ResourceLocationArgument.getId(ctx, "presetId").toString();
		if (PresetRegistry.get(presetId) == null) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.preset.no_such", presetId));
			return 0;
		}

		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players");
		for (ServerPlayer target : targets) {
			PresetManager.applyPreset(target, presetId);
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.preset.applied_to_players", targets.size()), true);
		return targets.size();
	}
}
