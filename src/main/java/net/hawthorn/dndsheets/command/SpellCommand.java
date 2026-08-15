package net.hawthorn.dndsheets.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.DndPaths;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.hawthorn.dndsheets.SpellRegistry;
import net.hawthorn.dndsheets.network.SheetClientMessage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;

/**
 * <p>Carga hechizos desde JSON en {@code <carpeta del mundo>/dndsheets/spells/<archivo>.json} (ver
 * {@link SpellRegistry} para el formato) y permite que un jugador "aprenda" uno, añadiéndolo a la lista
 * de hechizos conocidos de su propia hoja para que aparezca en su Grimorio. Aprender el PRIMER hechizo
 * también le da un espacio de conjuro (si tenía 0) y un báculo de lanzado rápido en el inventario, para
 * que se pueda probar de inmediato sin depender de {@code /dndsheet setslots} ni {@code /dndspells
 * staff} aparte.</p>
 */
@Mod.EventBusSubscriber
public class SpellCommand {
	private static final Path SPELLS_DIR = DndPaths.SPELLS_DIR;

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndspells")
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("load")
				.then(Commands.argument("archivo", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DndPaths.jsonFileNames(SPELLS_DIR), builder))
					.executes(SpellCommand::load)))
			.then(Commands.literal("list").executes(SpellCommand::list))
			.then(Commands.literal("learn")
				.then(Commands.argument("jugadores", EntityArgument.players())
					.then(Commands.argument("hechizoId", ResourceLocationArgument.id())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(SpellRegistry.ids(), builder))
						.executes(SpellCommand::learn))))
			.then(Commands.literal("staff")
				.then(Commands.argument("jugadores", EntityArgument.players())
					.then(Commands.argument("hechizoId", ResourceLocationArgument.id())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(SpellRegistry.ids(), builder))
						.executes(ctx -> staff(ctx, "minecraft:blaze_rod"))
						.then(Commands.argument("itemBase", ResourceLocationArgument.id())
							.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ForgeRegistries.ITEMS.getKeys().stream().map(Object::toString), builder))
							.executes(ctx -> staff(ctx, ResourceLocationArgument.getId(ctx, "itemBase").toString())))))));
	}

	private static int load(CommandContext<CommandSourceStack> ctx) {
		String fileName = StringArgumentType.getString(ctx, "archivo");
		Path file = SPELLS_DIR.resolve(fileName + ".json");

		if (!Files.exists(file)) {
			ctx.getSource().sendFailure(Component.literal("No encontré " + file.toAbsolutePath()));
			return 0;
		}

		try {
			int count = SpellRegistry.loadFile(file);
			ctx.getSource().sendSuccess(() -> Component.literal("Cargados " + count + " hechizos desde " + fileName + ".json"), true);
			return count;
		} catch (IOException | RuntimeException e) {
			ctx.getSource().sendFailure(Component.literal("No pude leer " + fileName + ".json: " + e.getMessage()));
			return 0;
		}
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		Set<String> ids = SpellRegistry.ids();
		ctx.getSource().sendSuccess(() -> Component.literal("Hechizos cargados (" + ids.size() + "): " + String.join(", ", ids)), false);
		return ids.size();
	}

	private static int learn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String spellId = ResourceLocationArgument.getId(ctx, "hechizoId").toString();
		SpellRegistry.Spell spell = SpellRegistry.get(spellId);
		if (spell == null) {
			ctx.getSource().sendFailure(Component.literal("No conozco el hechizo \"" + spellId + "\". Cárgalo con /dndspells load."));
			return 0;
		}

		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "jugadores");
		for (ServerPlayer target : targets) learnForPlayer(target, spellId, spell);

		ctx.getSource().sendSuccess(() -> Component.literal(targets.size() + " jugador(es) aprendieron " + spell.name() + "."), true);
		return targets.size();
	}

	//Público: también lo usa network.SpellGiveMessage (equivalente en GUI, ver client.gui.SpellGiveListScreen)
	//— mismo cuerpo por jugador que el bucle de arriba, para no duplicar la lógica de primer-hechizo entre
	//comando y GUI.
	public static void learnForPlayer(ServerPlayer target, String spellId, SpellRegistry.Spell spell) {
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null) return;

		SheetLoader.validateSheet(sheet);
		boolean alreadyKnown = !SpellRegistry.learn(sheet, spellId);

		//Sin esto, aprender un hechizo por primera vez dejaba al jugador con 0/0 espacios para
		//siempre (un descanso no crea espacios de la nada, solo rellena los que ya existían) y sin
		//nada en el inventario para lanzarlo: había que acordarse de /dndsheet setslots Y /dndspells
		//staff aparte. Si el DM ya configuró espacios a mano, esto no los toca.
		int slotsMax = sheet.has("spellSlotsMax") ? sheet.get("spellSlotsMax").getAsInt() : 0;
		if (slotsMax <= 0) {
			sheet.addProperty("spellSlotsMax", 1);
			sheet.addProperty("spellSlotsCurrent", 1);
		}
		if (!alreadyKnown) {
			target.getInventory().add(buildStaffStack(spellId, spell, "minecraft:blaze_rod"));
		}

		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> target), new SheetClientMessage(sheet.toString().getBytes()));
	}

	//Un báculo (o cualquier ítem base) etiquetado {dndsheets:{quickSpell:"id"}} lo lanza de un clic
	//derecho sin pasar por el Grimorio (ver QuickSpellManager), usando siempre las estadísticas y
	//espacios de conjuro reales del portador, no un "cargador" propio del báculo.
	private static int staff(CommandContext<CommandSourceStack> ctx, String itemId) throws CommandSyntaxException {
		String spellId = ResourceLocationArgument.getId(ctx, "hechizoId").toString();
		SpellRegistry.Spell spell = SpellRegistry.get(spellId);
		if (spell == null) {
			ctx.getSource().sendFailure(Component.literal("No conozco el hechizo \"" + spellId + "\". Cárgalo con /dndspells load."));
			return 0;
		}

		ItemStack stack = buildStaffStack(spellId, spell, itemId);

		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "jugadores");
		for (ServerPlayer target : targets) {
			target.getInventory().add(stack.copy());
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Entregado el Báculo de " + spell.name() + " a " + targets.size() + " jugador(es)."), true);
		return targets.size();
	}

	//Público: también lo usa la pestaña creativa (DndsheetsModCreativeTab) para mostrar los báculos de cada hechizo cargado.
	public static ItemStack buildStaffStack(String spellId, SpellRegistry.Spell spell, String itemId) {
		ResourceLocation itemLoc = ResourceLocation.tryParse(itemId);
		Item baseItem = itemLoc != null ? ForgeRegistries.ITEMS.getValue(itemLoc) : null;
		if (baseItem == null) baseItem = Items.BLAZE_ROD;

		ItemStack stack = new ItemStack(baseItem);
		CompoundTag dndTag = new CompoundTag();
		dndTag.putString("quickSpell", spellId);
		stack.getOrCreateTag().put("dndsheets", dndTag);
		stack.setHoverName(Component.literal("Báculo de " + spell.name()));
		return stack;
	}
}
