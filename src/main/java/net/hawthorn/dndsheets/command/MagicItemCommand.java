package net.hawthorn.dndsheets.command;

import net.hawthorn.dndsheets.ContentNames;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.DndPaths;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.MagicItemRegistry;
import net.hawthorn.dndsheets.SheetLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * <p>{@code /dnditems}: magic items. Same set of subcommands as the rest of the content types
 * ({@code load} to hot-reload, {@code give} to hand out), plus the two that are unique to this type:
 * {@code attune} and {@code unattune}.</p>
 *
 * <p>Attunement isn't decoration: in 5e it caps a character at three items, and here it also solves a
 * problem unique to Minecraft — there's no ring slot or cloak slot to wear a Ring of Protection in, so
 * without it there'd be no way for an item of that type to be "in use".</p>
 */
@Mod.EventBusSubscriber
public class MagicItemCommand {

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dnditems")
			.then(Commands.literal("list")
				.executes(MagicItemCommand::list))
			.then(Commands.literal("info")
				.then(Commands.argument("id", StringArgumentType.string())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(MagicItemRegistry.ids(), builder))
					.executes(MagicItemCommand::info)))
			//Attuning is the player's own business over their own sheet: it isn't gated by operator status.
			.then(Commands.literal("attune")
				.then(Commands.argument("id", StringArgumentType.string())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(MagicItemRegistry.ids(), builder))
					.executes(ctx -> setAttuned(ctx, true))))
			.then(Commands.literal("unattune")
				.then(Commands.argument("id", StringArgumentType.string())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(MagicItemRegistry.ids(), builder))
					.executes(ctx -> setAttuned(ctx, false))))
			.then(Commands.literal("give")
				.requires(source -> DndsheetsMod.canActAsDm(source))
				.then(Commands.argument("players", EntityArgument.players())
					.then(Commands.argument("id", StringArgumentType.string())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(MagicItemRegistry.ids(), builder))
						.executes(MagicItemCommand::give))))
			.then(Commands.literal("load")
				.requires(source -> DndsheetsMod.canActAsDm(source))
				.then(Commands.argument("file", StringArgumentType.string())
					.executes(MagicItemCommand::load))));
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		if (MagicItemRegistry.ids().isEmpty()) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.magic.none_loaded"));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.magicitem.loaded_count", MagicItemRegistry.ids().size())
			.withStyle(ChatFormatting.GOLD), false);
		for (String id : MagicItemRegistry.ids()) {
			MagicItemRegistry.MagicItem item = MagicItemRegistry.get(id);
			//It's marked which one has real mechanics and which is purely narrative: without that, a DM
			//wouldn't know which ones the engine will apply and which ones they have to narrate themselves.
			ctx.getSource().sendSuccess(() -> Component.literal("  ").append(ContentNames.of(item.name())).append(" [" + id + "]")
				.append(item.hasMechanics() ? Component.empty() : Component.translatable("chat.dndsheets.magicitem.narrative_suffix")).withStyle(ChatFormatting.GRAY), false);
		}
		return MagicItemRegistry.ids().size();
	}

	private static int info(CommandContext<CommandSourceStack> ctx) {
		MagicItemRegistry.MagicItem item = MagicItemRegistry.get(StringArgumentType.getString(ctx, "id"));
		if (item == null) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.magic.no_such_item"));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> ContentNames.of(item.name()).append(" — " + item.rarity()).append(item.attunement() ? Component.translatable("chat.dndsheets.magicitem.info_attunement") : Component.empty()).withStyle(ChatFormatting.GOLD), false);
		if (!item.description().isBlank()) {
			ctx.getSource().sendSuccess(() -> Component.literal(item.description()).withStyle(ChatFormatting.GRAY), false);
		}
		return 1;
	}

	private static int setAttuned(CommandContext<CommandSourceStack> ctx, boolean attune) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		String id = StringArgumentType.getString(ctx, "id");
		MagicItemRegistry.MagicItem item = MagicItemRegistry.get(id);
		if (item == null) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.magic.no_such_item"));
			return 0;
		}
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return 0;

		boolean changed = attune ? MagicItemRegistry.attune(sheet, id) : MagicItemRegistry.unattune(sheet, id);
		if (!changed) {
			//The two possible reasons are distinguished instead of a "couldn't do it" that forces guessing.
			ctx.getSource().sendFailure(attune
				? Component.translatable("chat.dndsheets.magicitem.already_attuned", MagicItemRegistry.MAX_ATTUNED)
				: Component.translatable("chat.dndsheets.magicitem.not_attuned"));
			return 0;
		}
		SheetLoader.saveServer(sheet, player.getStringUUID());
		ctx.getSource().sendSuccess(() -> Component.translatable(attune ? "chat.dndsheets.magicitem.attuned" : "chat.dndsheets.magicitem.unattuned",
			ContentNames.of(item.name())).withStyle(ChatFormatting.GREEN), false);
		return 1;
	}

	private static int give(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "players");
		String id = StringArgumentType.getString(ctx, "id");
		MagicItemRegistry.MagicItem magicItem = MagicItemRegistry.get(id);
		if (magicItem == null) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.magic.no_such_item"));
			return 0;
		}
		Item base = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(magicItem.itemId()));
		if (base == null) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.content.base_item_missing", magicItem.itemId()));
			return 0;
		}

		for (ServerPlayer target : targets) {
			ItemStack stack = MagicItemRegistry.tag(new ItemStack(base), id);
			stack.setHoverName(ContentNames.of(magicItem.name()).withStyle(ChatFormatting.AQUA));
			target.getInventory().add(stack);
			target.sendSystemMessage(Component.translatable("chat.dndsheets.item.received_magic", ContentNames.of(magicItem.name()), (magicItem.attunement() ? Component.translatable("chat.dndsheets.magicitem.attune_hint", id) : Component.literal(".")))
				.withStyle(ChatFormatting.GREEN));
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.magicitem.given", ContentNames.of(magicItem.name()), targets.size()), true);
		return targets.size();
	}

	private static int load(CommandContext<CommandSourceStack> ctx) {
		String fileName = StringArgumentType.getString(ctx, "file");
		Path file = DndPaths.ITEMS_DIR.resolve(fileName);
		try {
			int loaded = MagicItemRegistry.loadFile(file);
			ctx.getSource().sendSuccess(() -> Component.translatable("chat.dndsheets.magicitem.loaded", loaded)
				.withStyle(ChatFormatting.GREEN), true);
			return loaded;
		} catch (IOException e) {
			ctx.getSource().sendFailure(Component.translatable("chat.dndsheets.magicitem.read_failed", file.toString(), e.getMessage()));
			return 0;
		}
	}
}
