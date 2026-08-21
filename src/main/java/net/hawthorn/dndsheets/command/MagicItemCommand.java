package net.hawthorn.dndsheets.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.DndPaths;
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
 * <p>{@code /dnditems}: objetos mágicos. Mismo juego de subcomandos que el resto de tipos de contenido
 * ({@code load} para recargar en caliente, {@code give} para entregar), más los dos que son propios de
 * este tipo: {@code attune} y {@code unattune}.</p>
 *
 * <p>La sintonización no es un adorno: en 5e limita a tres objetos por personaje, y aquí resuelve además
 * un problema propio de Minecraft — no hay ranura de anillo ni de capa donde llevar puesto un Anillo de
 * Protección, así que sin ella no habría forma de que un objeto de ese tipo estuviera "en uso".</p>
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
			//Sintonizar es cosa del propio jugador sobre su propia hoja: no se gatea por operador.
			.then(Commands.literal("attune")
				.then(Commands.argument("id", StringArgumentType.string())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(MagicItemRegistry.ids(), builder))
					.executes(ctx -> setAttuned(ctx, true))))
			.then(Commands.literal("unattune")
				.then(Commands.argument("id", StringArgumentType.string())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(MagicItemRegistry.ids(), builder))
					.executes(ctx -> setAttuned(ctx, false))))
			.then(Commands.literal("give")
				.requires(source -> source.hasPermission(2))
				.then(Commands.argument("jugadores", EntityArgument.players())
					.then(Commands.argument("id", StringArgumentType.string())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(MagicItemRegistry.ids(), builder))
						.executes(MagicItemCommand::give))))
			.then(Commands.literal("load")
				.requires(source -> source.hasPermission(2))
				.then(Commands.argument("archivo", StringArgumentType.string())
					.executes(MagicItemCommand::load))));
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		if (MagicItemRegistry.ids().isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("No hay ningún objeto mágico cargado."));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Objetos mágicos cargados: " + MagicItemRegistry.ids().size())
			.withStyle(ChatFormatting.GOLD), false);
		for (String id : MagicItemRegistry.ids()) {
			MagicItemRegistry.MagicItem item = MagicItemRegistry.get(id);
			//Se marca cuál tiene mecánicas reales y cuál es puramente narrativo: sin eso, un DM no sabría
			//cuáles va a aplicar el motor y cuáles tiene que narrar él.
			ctx.getSource().sendSuccess(() -> Component.literal("  " + item.name() + " [" + id + "]"
				+ (item.hasMechanics() ? "" : " (narrativo)")).withStyle(ChatFormatting.GRAY), false);
		}
		return MagicItemRegistry.ids().size();
	}

	private static int info(CommandContext<CommandSourceStack> ctx) {
		MagicItemRegistry.MagicItem item = MagicItemRegistry.get(StringArgumentType.getString(ctx, "id"));
		if (item == null) {
			ctx.getSource().sendFailure(Component.literal("No existe ese objeto mágico."));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal(item.name() + " — " + item.rarity()
			+ (item.attunement() ? " (requiere sintonización)" : "")).withStyle(ChatFormatting.GOLD), false);
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
			ctx.getSource().sendFailure(Component.literal("No existe ese objeto mágico."));
			return 0;
		}
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return 0;

		boolean changed = attune ? MagicItemRegistry.attune(sheet, id) : MagicItemRegistry.unattune(sheet, id);
		if (!changed) {
			//Se distinguen los dos motivos posibles en vez de un "no se pudo" que obliga a adivinar.
			ctx.getSource().sendFailure(Component.literal(attune
				? "Ya lo tenías sintonizado, o llegaste al límite de " + MagicItemRegistry.MAX_ATTUNED + "."
				: "No lo tenías sintonizado."));
			return 0;
		}
		SheetLoader.saveServer(sheet, player.getStringUUID());
		ctx.getSource().sendSuccess(() -> Component.literal((attune ? "Sintonizado con " : "Dejaste de sintonizar ")
			+ item.name() + ".").withStyle(ChatFormatting.GREEN), false);
		return 1;
	}

	private static int give(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "jugadores");
		String id = StringArgumentType.getString(ctx, "id");
		MagicItemRegistry.MagicItem magicItem = MagicItemRegistry.get(id);
		if (magicItem == null) {
			ctx.getSource().sendFailure(Component.literal("No existe ese objeto mágico."));
			return 0;
		}
		Item base = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(magicItem.itemId()));
		if (base == null) {
			ctx.getSource().sendFailure(Component.literal("El ítem base \"" + magicItem.itemId() + "\" no existe."));
			return 0;
		}

		for (ServerPlayer target : targets) {
			ItemStack stack = MagicItemRegistry.tag(new ItemStack(base), id);
			stack.setHoverName(Component.literal(magicItem.name()).withStyle(ChatFormatting.AQUA));
			target.getInventory().add(stack);
			target.sendSystemMessage(Component.translatable("chat.dndsheets.item.received_magic", magicItem.name(), (magicItem.attunement() ? ". Sintonízalo con /dnditems attune " + id : "."))
				.withStyle(ChatFormatting.GREEN));
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Entregado " + magicItem.name() + " a "
			+ targets.size() + " jugador(es)."), true);
		return targets.size();
	}

	private static int load(CommandContext<CommandSourceStack> ctx) {
		String fileName = StringArgumentType.getString(ctx, "archivo");
		Path file = DndPaths.ITEMS_DIR.resolve(fileName);
		try {
			int loaded = MagicItemRegistry.loadFile(file);
			ctx.getSource().sendSuccess(() -> Component.literal("Cargados " + loaded + " objetos mágicos.")
				.withStyle(ChatFormatting.GREEN), true);
			return loaded;
		} catch (IOException e) {
			ctx.getSource().sendFailure(Component.literal("No se pudo leer " + file + ": " + e.getMessage()));
			return 0;
		}
	}
}
