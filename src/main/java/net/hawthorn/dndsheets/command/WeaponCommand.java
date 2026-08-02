package net.hawthorn.dndsheets.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.hawthorn.dndsheets.Config;
import net.hawthorn.dndsheets.DndPaths;
import net.minecraft.ChatFormatting;
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
import net.minecraftforge.registries.ForgeRegistries;

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
			int count = loadFile(file);
			ctx.getSource().sendSuccess(() -> Component.literal("Cargadas " + count + " armas desde " + fileName + ".json"), true);
			return count;
		} catch (IOException | RuntimeException e) {
			ctx.getSource().sendFailure(Component.literal("No pude leer " + fileName + ".json: " + e.getMessage()));
			return 0;
		}
	}

	//Público: también lo usa DndPaths para precargar solo todos los .json de la carpeta al arrancar el servidor.
	public static int loadFile(Path file) throws IOException {
		String json = Files.readString(file);
		JsonArray weapons = JsonParser.parseString(json).getAsJsonArray();
		int count = 0;
		int index = 0;
		for (JsonElement element : weapons) {
			index++;
			try {
				JsonObject weapon = element.getAsJsonObject();
				if (!weapon.has("id") || !weapon.has("dice") || !weapon.has("ability")) {
					System.out.println("Saltando arma #" + index + " en " + file.getFileName() + ": falta \"id\", \"dice\" o \"ability\".");
					continue;
				}

				String id = weapon.get("id").getAsString();
				String dice = weapon.get("dice").getAsString();
				String ability = weapon.get("ability").getAsString();
				String name = weapon.has("name") ? weapon.get("name").getAsString() : id;
				String baseItem = weapon.has("item") ? weapon.get("item").getAsString() : "minecraft:stick";
				String damageType = weapon.has("damageType") ? weapon.get("damageType").getAsString() : "fisico";
				String hands = weapon.has("hands") ? weapon.get("hands").getAsString() : "one";
				String versatileDice = weapon.has("versatileDice") ? weapon.get("versatileDice").getAsString() : null;

				//Opcional: qué clases pueden usarla (subcadenas comparadas contra "Clase y Nivel" de la hoja,
				//mismo patrón que Config.hitDieFor) — sin este campo (el caso por defecto) cualquier clase
				//puede usar el arma, igual que antes.
				java.util.List<String> classes = new java.util.ArrayList<>();
				if (weapon.has("classes")) {
					for (JsonElement el : weapon.getAsJsonArray("classes")) classes.add(el.getAsString());
				}

				Integer customModelData = weapon.has("customModelData") ? weapon.get("customModelData").getAsInt() : null;

				Config.registerWeapon(id, dice, ability, damageType, hands, versatileDice, classes, name, baseItem, customModelData);
				count++;
			} catch (RuntimeException e) {
				System.out.println("Saltando arma #" + index + " en " + file.getFileName() + ": " + e);
			}
		}
		return count;
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

		ItemStack stack = buildWeaponStack(weaponId, count);
		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "jugadores");
		for (ServerPlayer target : targets) {
			target.getInventory().add(stack.copy());
		}

		ctx.getSource().sendSuccess(() -> Component.literal("Entregado " + stack.getHoverName().getString() + " a " + targets.size() + " jugador(es)."), true);
		return targets.size();
	}

	//Si el id es directamente un ítem real de Minecraft (p.ej. "minecraft:bow"), se entrega tal cual, sin
	//etiqueta NBT. Si es un id personalizado (p.ej. "dndsheets:dagger"), se etiqueta sobre el ítem base
	//configurado (por /dndweapons load) para que el resto del sistema lo reconozca como esa arma.
	//Público: también lo usa la pestaña creativa (DndsheetsModCreativeTab) para mostrar las armas cargadas.
	public static ItemStack buildWeaponStack(String weaponId, int count) {
		ResourceLocation directLoc = ResourceLocation.tryParse(weaponId);
		Item directItem = directLoc != null ? ForgeRegistries.ITEMS.getValue(directLoc) : null;
		if (directItem != null && directItem != Items.AIR) {
			return new ItemStack(directItem, count);
		}

		Config.WeaponGiveInfo giveInfo = Config.giveInfoFor(weaponId);
		Item baseItem = Items.STICK;
		if (giveInfo != null) {
			ResourceLocation baseLoc = ResourceLocation.tryParse(giveInfo.baseItemId());
			Item resolved = baseLoc != null ? ForgeRegistries.ITEMS.getValue(baseLoc) : null;
			if (resolved != null) baseItem = resolved;
		}

		ItemStack stack = new ItemStack(baseItem, count);
		CompoundTag dndTag = new CompoundTag();
		dndTag.putString("weapon", weaponId);
		stack.getOrCreateTag().put("dndsheets", dndTag);
		if (giveInfo != null) {
			stack.setHoverName(Component.literal(giveInfo.displayName()));
			//Reskin por resource pack: un modelo custom en assets/minecraft/models/item/<baseItem>.json puede
			//mapear este número a un modelo/textura distinta, sin que el arma tenga que compartir la del
			//ítem base que la representa (p.ej. una "Daga" que ya no se ve como una espada de hierro).
			if (giveInfo.customModelData() != null) stack.getOrCreateTag().putInt("CustomModelData", giveInfo.customModelData());
		}
		addHandsLore(stack, Config.weaponDefaultFor(weaponId));
		return stack;
	}

	//Para "identificar armas de una y dos manos ya que algunas tienen bonificaciones" (feedback de
	//playtesting): una línea de lore visible en el tooltip del ítem, no solo un dato en el JSON que solo
	//lee el código. Las armas de "hands":"one" (el caso por defecto, casi todas) no llevan lore extra —
	//no hay nada especial que señalar.
	private static void addHandsLore(ItemStack stack, Config.WeaponDefault weaponDefault) {
		if (weaponDefault == null) return;

		String text = switch (weaponDefault.hands()) {
			case "two" -> "A dos manos";
			case "versatile" -> weaponDefault.isVersatile()
				? "Versátil (" + weaponDefault.dice() + " a una mano, " + weaponDefault.versatileDice() + " a dos)"
				: null;
			default -> null;
		};
		if (text == null) return;

		net.minecraft.nbt.ListTag lore = new net.minecraft.nbt.ListTag();
		lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(Component.literal(text).withStyle(ChatFormatting.GRAY))));
		stack.getOrCreateTagElement("display").put("Lore", lore);
	}
}
