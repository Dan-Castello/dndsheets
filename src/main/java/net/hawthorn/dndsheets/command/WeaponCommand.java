package net.hawthorn.dndsheets.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.Config;
import net.hawthorn.dndsheets.DndPaths;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

/**
 * <p>Lets you hot-load weapon packs (e.g. 50 D&amp;D weapons at once) from a JSON in
 * {@code &lt;world folder&gt;/dndsheets/weapons/&lt;file&gt;.json}, without touching dndsheets-common.toml or
 * restarting the server, and hand them to players as loot.</p>
 *
 * <p>JSON format, an array of objects:</p>
 * <pre>
 * [
 *   { "id": "dndsheets:dagger", "dice": "1d4", "ability": "dex", "name": "Daga", "item": "minecraft:iron_sword" }
 * ]
 * </pre>
 */
@Mod.EventBusSubscriber
public class WeaponCommand {
	private static final Path WEAPONS_DIR = DndPaths.WEAPONS_DIR;

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndweapons")
			.requires(source -> DndsheetsMod.canActAsDm(source))
			.then(Commands.literal("load")
				.then(Commands.argument("file", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DndPaths.jsonFileNames(WEAPONS_DIR), builder))
					.executes(WeaponCommand::load)))
			.then(Commands.literal("list").executes(WeaponCommand::list))
			.then(Commands.literal("give")
				.then(Commands.argument("players", EntityArgument.players())
					.then(Commands.argument("weaponId", ResourceLocationArgument.id())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(Config.loadedWeaponIds(), builder))
						.executes(ctx -> give(ctx, 1))
						.then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
							.executes(ctx -> give(ctx, IntegerArgumentType.getInteger(ctx, "amount"))))))));
	}

	private static int load(CommandContext<CommandSourceStack> ctx) {
		String fileName = StringArgumentType.getString(ctx, "file");
		Path file = WEAPONS_DIR.resolve(fileName + ".json");

		if (!Files.exists(file)) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.content.file_not_found", file.toAbsolutePath().toString()));
			return 0;
		}

		try {
			int count = Config.loadFile(file);
			ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.weapon.loaded", count, fileName), true);
			return count;
		} catch (IOException | RuntimeException e) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.content.read_failed", fileName, e.getMessage()));
			return 0;
		}
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		java.util.Set<String> ids = Config.loadedWeaponIds();
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.weapon.configured_list", ids.size(), String.join(", ", ids)), false);
		return ids.size();
	}

	private static int give(CommandContext<CommandSourceStack> ctx, int count) throws CommandSyntaxException {
		String weaponId = ResourceLocationArgument.getId(ctx, "weaponId").toString();
		if (Config.weaponDefaultFor(weaponId) == null) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.weapon.no_such_load_hint", weaponId));
			return 0;
		}

		ItemStack stack = Config.buildWeaponStack(weaponId, count);
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players");
		for (ServerPlayer target : targets) {
			target.getInventory().add(stack.copy());
		}

		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.weapon.given", stack.getHoverName().getString(), targets.size()), true);
		return targets.size();
	}
}
