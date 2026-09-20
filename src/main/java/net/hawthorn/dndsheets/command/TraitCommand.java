package net.hawthorn.dndsheets.command;

import net.hawthorn.dndsheets.ContentNames;

import com.google.gson.JsonObject;
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

import java.nio.file.Path;
import java.util.Collection;

/**
 * <p>Loads traits (passives/class features) from JSON at
 * {@code <world folder>/dndsheets/traits/<file>.json} (see {@link TraitRegistry} for the format)
 * and grants them to a player by hand, on top of whatever their class preset already grants.</p>
 */
@Mod.EventBusSubscriber
public class TraitCommand {
	private static final Path TRAITS_DIR = DndPaths.TRAITS_DIR;

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndtraits")
			.requires(source -> DndsheetsMod.canActAsDm(source))
			.then(ContentCommands.loadBranch(TRAITS_DIR, TraitRegistry::loadFile, "traits"))
			.then(ContentCommands.listBranch(TraitRegistry::ids, "Traits"))
			.then(Commands.literal("grant")
				.then(Commands.argument("players", EntityArgument.players())
					.then(Commands.argument("traitId", ResourceLocationArgument.id())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(TraitRegistry.ids(), builder))
						.executes(TraitCommand::grant)))));
	}



	private static int grant(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String traitId = ResourceLocationArgument.getId(ctx, "traitId").toString();
		TraitRegistry.Trait trait = TraitRegistry.get(traitId);
		if (trait == null) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.trait.no_such_load_hint", traitId));
			return 0;
		}

		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players");
		for (ServerPlayer target : targets) grantToPlayer(target, traitId);
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.trait.granted", targets.size(), ContentNames.of(trait.name())), true);
		return targets.size();
	}

	//Public: also used by the DM Panel (see network.TraitGrantMessage) to grant a trait without going
	//through Brigadier. Doesn't validate the id: the caller already resolved it against TraitRegistry.
	public static void grantToPlayer(ServerPlayer target, String traitId) {
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null) return;

		SheetLoader.validateSheet(sheet);
		TraitRegistry.grant(sheet, traitId);

		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> target), new SheetClientMessage(sheet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}
}
