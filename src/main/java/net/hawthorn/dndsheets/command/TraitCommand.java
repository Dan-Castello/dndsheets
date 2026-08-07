package net.hawthorn.dndsheets.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.DndPaths;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.TraitRegistry;
import net.hawthorn.dndsheets.network.SheetClientMessage;
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
import net.minecraftforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;

/**
 * <p>Carga rasgos (pasivas/habilidades de clase) desde JSON en
 * {@code <carpeta del mundo>/dndsheets/traits/<archivo>.json} (ver {@link TraitRegistry} para el formato)
 * y los concede a mano a un jugador, además de lo que ya conceda su preset de clase.</p>
 */
@Mod.EventBusSubscriber
public class TraitCommand {
	private static final Path TRAITS_DIR = DndPaths.TRAITS_DIR;

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndtraits")
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("load")
				.then(Commands.argument("archivo", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DndPaths.jsonFileNames(TRAITS_DIR), builder))
					.executes(TraitCommand::load)))
			.then(Commands.literal("list").executes(TraitCommand::list))
			.then(Commands.literal("grant")
				.then(Commands.argument("jugadores", EntityArgument.players())
					.then(Commands.argument("rasgoId", ResourceLocationArgument.id())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(TraitRegistry.ids(), builder))
						.executes(TraitCommand::grant)))));
	}

	private static int load(CommandContext<CommandSourceStack> ctx) {
		String fileName = StringArgumentType.getString(ctx, "archivo");
		Path file = TRAITS_DIR.resolve(fileName + ".json");

		if (!Files.exists(file)) {
			ctx.getSource().sendFailure(Component.literal("No encontré " + file.toAbsolutePath()));
			return 0;
		}

		try {
			int count = TraitRegistry.loadFile(file);
			ctx.getSource().sendSuccess(() -> Component.literal("Cargados " + count + " rasgos desde " + fileName + ".json"), true);
			return count;
		} catch (IOException | RuntimeException e) {
			ctx.getSource().sendFailure(Component.literal("No pude leer " + fileName + ".json: " + e.getMessage()));
			return 0;
		}
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		Set<String> ids = TraitRegistry.ids();
		ctx.getSource().sendSuccess(() -> Component.literal("Rasgos cargados (" + ids.size() + "): " + String.join(", ", ids)), false);
		return ids.size();
	}

	private static int grant(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String traitId = ResourceLocationArgument.getId(ctx, "rasgoId").toString();
		TraitRegistry.Trait trait = TraitRegistry.get(traitId);
		if (trait == null) {
			ctx.getSource().sendFailure(Component.literal("No conozco el rasgo \"" + traitId + "\". Cárgalo con /dndtraits load."));
			return 0;
		}

		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "jugadores");
		for (ServerPlayer target : targets) grantToPlayer(target, traitId);
		ctx.getSource().sendSuccess(() -> Component.literal(targets.size() + " jugador(es) recibieron el rasgo " + trait.name() + "."), true);
		return targets.size();
	}

	//Público: también lo usa el Panel de DM (ver network.TraitGrantMessage) para conceder un rasgo sin
	//pasar por Brigadier. No valida el id: el llamador ya lo resolvió contra TraitRegistry.
	public static void grantToPlayer(ServerPlayer target, String traitId) {
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null) return;

		SheetLoader.validateSheet(sheet);
		TraitRegistry.grant(sheet, traitId);

		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> target), new SheetClientMessage(sheet.toString().getBytes()));
	}
}
