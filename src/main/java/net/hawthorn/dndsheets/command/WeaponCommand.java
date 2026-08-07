package net.hawthorn.dndsheets.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.Config;
import net.hawthorn.dndsheets.DndPaths;
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
 * <p>Permite cargar en caliente packs de armas (p.ej. 50 armas de D&amp;D de golpe) desde un JSON en
 * {@code &lt;carpeta del mundo&gt;/dndsheets/weapons/&lt;archivo&gt;.json}, sin tocar dndsheets-common.toml ni
 * reiniciar el servidor, y entregarlas a jugadores como loot.</p>
 *
 * <p>Formato del JSON, un array de objetos:</p>
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
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("load")
				.then(Commands.argument("archivo", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DndPaths.jsonFileNames(WEAPONS_DIR), builder))
					.executes(WeaponCommand::load)))
			.then(Commands.literal("list").executes(WeaponCommand::list))
			.then(Commands.literal("give")
				.then(Commands.argument("jugadores", EntityArgument.players())
					.then(Commands.argument("armaId", ResourceLocationArgument.id())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(Config.loadedWeaponIds(), builder))
						.executes(ctx -> give(ctx, 1))
						.then(Commands.argument("cantidad", IntegerArgumentType.integer(1, 64))
							.executes(ctx -> give(ctx, IntegerArgumentType.getInteger(ctx, "cantidad"))))))));
	}

	private static int load(CommandContext<CommandSourceStack> ctx) {
		String fileName = StringArgumentType.getString(ctx, "archivo");
		Path file = WEAPONS_DIR.resolve(fileName + ".json");

		if (!Files.exists(file)) {
			ctx.getSource().sendFailure(Component.literal("No encontré " + file.toAbsolutePath()));
			return 0;
		}

		try {
			int count = Config.loadFile(file);
			ctx.getSource().sendSuccess(() -> Component.literal("Cargadas " + count + " armas desde " + fileName + ".json"), true);
			return count;
		} catch (IOException | RuntimeException e) {
			ctx.getSource().sendFailure(Component.literal("No pude leer " + fileName + ".json: " + e.getMessage()));
			return 0;
		}
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		java.util.Set<String> ids = Config.loadedWeaponIds();
		ctx.getSource().sendSuccess(() -> Component.literal("Armas configuradas (" + ids.size() + "): " + String.join(", ", ids)), false);
		return ids.size();
	}

	private static int give(CommandContext<CommandSourceStack> ctx, int count) throws CommandSyntaxException {
		String weaponId = ResourceLocationArgument.getId(ctx, "armaId").toString();
		if (Config.weaponDefaultFor(weaponId) == null) {
			ctx.getSource().sendFailure(Component.literal("No conozco el arma \"" + weaponId + "\". Cárgala con /dndweapons load o usa un id de ítem/arma ya configurado."));
			return 0;
		}

		ItemStack stack = Config.buildWeaponStack(weaponId, count);
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "jugadores");
		for (ServerPlayer target : targets) {
			target.getInventory().add(stack.copy());
		}

		ctx.getSource().sendSuccess(() -> Component.literal("Entregado " + stack.getHoverName().getString() + " a " + targets.size() + " jugador(es)."), true);
		return targets.size();
	}
}
