package net.hawthorn.dndsheets.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.hawthorn.dndsheets.CombatFx;
import net.hawthorn.dndsheets.MonsterRegistry;
import net.hawthorn.dndsheets.DndPaths;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;

/**
 * <p>Carga bloques de estadísticas de monstruo desde JSON y los invoca como mobs vanilla reales sin IA
 * ({@code NoAI:1}), para que el DM los controle a mano con la vara de DM en vez de dejar que Minecraft
 * los mueva o ataque solo. Ver {@link MonsterRegistry} para el formato del JSON y
 * {@link net.hawthorn.dndsheets.MonsterActionManager} para cómo el DM dispara sus ataques/hechizos.</p>
 */
@Mod.EventBusSubscriber
public class MonsterCommand {
	private static final Path MONSTERS_DIR = DndPaths.MONSTERS_DIR;
	private static final String[] ABILITY_SUGGESTIONS = {"str", "dex", "con", "int", "wis", "cha"};

	@SubscribeEvent
	public static void registerCommand(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("dndmonsters")
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("load")
				.then(Commands.argument("archivo", StringArgumentType.word())
					.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(DndPaths.jsonFileNames(MONSTERS_DIR), builder))
					.executes(MonsterCommand::load)))
			.then(Commands.literal("list").executes(MonsterCommand::list))
			.then(spawnNode())
			.then(galleryNode())
			.then(attackNode())
			.then(Commands.literal("dmtool")
				.then(Commands.argument("jugadores", EntityArgument.players()).executes(MonsterCommand::giveDmTool)))
			.then(Commands.literal("movetool")
				.then(Commands.argument("jugadores", EntityArgument.players()).executes(MonsterCommand::giveMoveTool))));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> spawnNode() {
		return Commands.literal("spawn")
			.then(Commands.argument("monstruoId", ResourceLocationArgument.id())
				.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(MonsterRegistry.ids(), builder))
				.executes(ctx -> spawn(ctx, 1))
				.then(Commands.argument("cantidad", IntegerArgumentType.integer(1, 50))
					.executes(ctx -> spawn(ctx, IntegerArgumentType.getInteger(ctx, "cantidad")))))
			//NPC en blanco, sin JSON: nombre obligatorio, entidad base/CA/PG opcionales (por defecto
			//aldeano, CA 10, 10 PG) — para rellenarlo en vivo con "attack add" según haga falta.
			.then(Commands.literal("generic")
				.then(Commands.argument("nombre", StringArgumentType.string())
					.executes(ctx -> spawnGeneric(ctx, "minecraft:villager", 10, 10))
					.then(Commands.argument("baseEntity", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ForgeRegistries.ENTITY_TYPES.getKeys().stream().map(Object::toString), builder))
						.executes(ctx -> spawnGeneric(ctx, StringArgumentType.getString(ctx, "baseEntity"), 10, 10))
						.then(Commands.argument("ac", IntegerArgumentType.integer(0, 30))
							.executes(ctx -> spawnGeneric(ctx, StringArgumentType.getString(ctx, "baseEntity"), IntegerArgumentType.getInteger(ctx, "ac"), 10))
							.then(Commands.argument("hp", IntegerArgumentType.integer(1, 9999))
								.executes(ctx -> spawnGeneric(ctx, StringArgumentType.getString(ctx, "baseEntity"), IntegerArgumentType.getInteger(ctx, "ac"), IntegerArgumentType.getInteger(ctx, "hp"))))))));
	}

	//Invoca de golpe TODO el bestiario en cuadrícula, para ver de un vistazo con qué modelo sale cada uno
	//—que es justo lo que cambia según qué mods de aspecto haya instalados (ver MonsterSkins)—. Sin esto,
	//comprobar que un pack de aspecto se aplicó de verdad exigía invocarlos de uno en uno.
	private static LiteralArgumentBuilder<CommandSourceStack> galleryNode() {
		return Commands.literal("gallery")
			.executes(ctx -> gallery(ctx, ""))
			//"clear" es literal, así que gana al argumento: no se puede filtrar por la palabra "clear".
			.then(Commands.literal("clear").executes(MonsterCommand::galleryClear))
			.then(Commands.argument("filtro", StringArgumentType.word())
				.executes(ctx -> gallery(ctx, StringArgumentType.getString(ctx, "filtro"))));
	}

	//Edita EN VIVO los ataques de un monstruo ya invocado (una instancia concreta, no toda su especie —
	//ver MonsterRegistry.addCustomAttack): sin "appliesEffect", solo ataque+daño, a propósito (ver la nota
	//en MonsterRegistry). Para un efecto de estado sigue haciendo falta el JSON completo del monstruo.
	private static LiteralArgumentBuilder<CommandSourceStack> attackNode() {
		return Commands.literal("attack")
			.then(Commands.literal("add")
				.then(Commands.argument("objetivo", EntityArgument.entity())
					.then(Commands.argument("nombre", StringArgumentType.string())
						.then(Commands.argument("habAtaque", StringArgumentType.word())
							.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ABILITY_SUGGESTIONS, builder))
							.then(Commands.argument("dado", StringArgumentType.word())
								.then(Commands.argument("habDano", StringArgumentType.word())
									.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ABILITY_SUGGESTIONS, builder))
									.then(Commands.argument("tipoDano", StringArgumentType.word())
										.executes(MonsterCommand::addAttack))))))))
			.then(Commands.literal("remove")
				.then(Commands.argument("objetivo", EntityArgument.entity())
					.then(Commands.argument("nombre", StringArgumentType.string())
						.executes(MonsterCommand::removeAttack))))
			.then(Commands.literal("clear")
				.then(Commands.argument("objetivo", EntityArgument.entity())
					.executes(MonsterCommand::clearAttacks)));
	}

	private static int load(CommandContext<CommandSourceStack> ctx) {
		String fileName = StringArgumentType.getString(ctx, "archivo");
		Path file = MONSTERS_DIR.resolve(fileName + ".json");

		if (!Files.exists(file)) {
			ctx.getSource().sendFailure(Component.literal("No encontré " + file.toAbsolutePath()));
			return 0;
		}

		try {
			int count = MonsterRegistry.loadFile(file);
			ctx.getSource().sendSuccess(() -> Component.literal("Cargados " + count + " monstruos desde " + fileName + ".json"), true);
			return count;
		} catch (IOException | RuntimeException e) {
			ctx.getSource().sendFailure(Component.literal("No pude leer " + fileName + ".json: " + e.getMessage()));
			return 0;
		}
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		Set<String> ids = MonsterRegistry.ids();
		ctx.getSource().sendSuccess(() -> Component.literal("Monstruos cargados (" + ids.size() + "): " + String.join(", ", ids)), false);
		return ids.size();
	}

	private static int spawn(CommandContext<CommandSourceStack> ctx, int count) {
		String monsterId = ResourceLocationArgument.getId(ctx, "monstruoId").toString();
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(monsterId);
		if (block == null) {
			ctx.getSource().sendFailure(Component.literal("No conozco el monstruo \"" + monsterId + "\". Cárgalo con /dndmonsters load."));
			return 0;
		}

		ServerLevel level = ctx.getSource().getLevel();
		Vec3 pos = ctx.getSource().getPosition();
		int spawned = 0;
		for (int i = 0; i < count; i++) {
			Entity entity = MonsterRegistry.spawnAt(level, pos.x, pos.y, pos.z, monsterId);
			if (entity != null) {
				CombatFx.monsterSpawn(entity);
				spawned++;
			}
		}

		if (spawned == 0) {
			ctx.getSource().sendFailure(Component.literal("El ítem base \"" + block.baseEntityId() + "\" de " + monsterId + " no existe."));
			return 0;
		}

		int finalSpawned = spawned;
		ctx.getSource().sendSuccess(() -> Component.literal("Invocados " + finalSpawned + " " + block.name() + " (CA " + block.ac() + ", " + block.maxHp() + " PG)."), true);
		return spawned;
	}

	//4 bloques y no 3: con 3 los modelos grandes (un dragón de Ice and Fire, un gigante) se solapan con su
	//vecino y no se distingue cuál es cuál, que es lo único para lo que sirve esta cuadrícula.
	private static final int GALLERY_SPACING = 4;

	private static int gallery(CommandContext<CommandSourceStack> ctx, String filter) {
		String needle = filter.toLowerCase(java.util.Locale.ROOT);
		java.util.List<String> ids = MonsterRegistry.ids().stream()
			.filter(id -> needle.isEmpty() || id.toLowerCase(java.util.Locale.ROOT).contains(needle))
			.sorted()
			.toList();
		if (ids.isEmpty()) {
			ctx.getSource().sendFailure(Component.literal("Ningún monstruo cargado " + (needle.isEmpty() ? "todavía." : "contiene \"" + filter + "\".")));
			return 0;
		}

		ServerLevel level = ctx.getSource().getLevel();
		Vec3 origin = ctx.getSource().getPosition();
		int columns = (int) Math.ceil(Math.sqrt(ids.size()));
		int spawned = 0;
		for (int i = 0; i < ids.size(); i++) {
			//Sin CombatFx.monsterSpawn: son cientos a la vez y el efecto por monstruo solo tapa la vista.
			if (MonsterRegistry.spawnAt(level,
					origin.x + (i % columns) * GALLERY_SPACING,
					origin.y,
					origin.z + (i / columns) * GALLERY_SPACING,
					ids.get(i)) != null) {
				spawned++;
			}
		}

		int finalSpawned = spawned;
		ctx.getSource().sendSuccess(() -> Component.literal("Invocados " + finalSpawned + " monstruos en una cuadrícula de "
			+ columns + " columnas. Bórralos con /dndmonsters gallery clear."), true);
		return spawned;
	}

	//Borra TODO monstruo del mod que haya cerca, no solo los de la galería: 330 fichas no se limpian a mano
	//con la Vara de DM. El radio cubre de sobra la cuadrícula más grande (18x18 casillas de 4 bloques).
	private static int galleryClear(CommandContext<CommandSourceStack> ctx) {
		ServerLevel level = ctx.getSource().getLevel();
		Vec3 pos = ctx.getSource().getPosition();
		java.util.List<Entity> found = level.getEntities((Entity) null,
			new net.minecraft.world.phys.AABB(pos, pos).inflate(200),
			entity -> MonsterRegistry.monsterIdOf(entity) != null);

		for (Entity entity : found) {
			//markDefeated antes del remove: este borrado no pasa por la muerte vanilla, y sin esto un
			//combate en marcha se quedaría esperando a un enemigo que ya no existe (ver TurnManager).
			net.hawthorn.dndsheets.TurnManager.markDefeated(entity.getId());
			entity.remove(Entity.RemovalReason.DISCARDED);
		}

		ctx.getSource().sendSuccess(() -> Component.literal("Borrados " + found.size() + " monstruos en 200 bloques."), true);
		return found.size();
	}

	//NPC en blanco (sin JSON de por medio): CA/PG/características por defecto, sin ataques. Pensado para
	//rellenarlo en vivo con /dndmonsters attack add según lo que necesite ese encuentro concreto.
	private static int spawnGeneric(CommandContext<CommandSourceStack> ctx, String baseEntity, int ac, int hp) {
		String name = StringArgumentType.getString(ctx, "nombre");
		ServerLevel level = ctx.getSource().getLevel();
		Vec3 pos = ctx.getSource().getPosition();

		Entity entity = MonsterRegistry.spawnGeneric(level, pos.x, pos.y, pos.z, name, baseEntity, ac, hp);
		if (entity == null) {
			ctx.getSource().sendFailure(Component.literal("El ítem base \"" + baseEntity + "\" no existe."));
			return 0;
		}

		CombatFx.monsterSpawn(entity);
		ctx.getSource().sendSuccess(() -> Component.literal("Invocado " + name + " (CA " + ac + ", " + hp + " PG) — añádele ataques con /dndmonsters attack add."), true);
		return 1;
	}

	//Ataque personalizado sobre UN monstruo ya invocado (no toca el bloque de estadísticas compartido de
	//su especie, ver MonsterRegistry.addCustomAttack) — así se puede dar un ataque extra a un solo goblin
	//de la tanda sin recargar JSON ni afectar a los demás.
	private static int addAttack(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		Entity target = EntityArgument.getEntity(ctx, "objetivo");
		if (MonsterRegistry.statBlockOf(target) == null) {
			ctx.getSource().sendFailure(Component.literal("Ese objetivo no es un monstruo invocado por /dndmonsters."));
			return 0;
		}

		String name = StringArgumentType.getString(ctx, "nombre");
		String toHitAbility = StringArgumentType.getString(ctx, "habAtaque").toLowerCase(java.util.Locale.ROOT);
		String dice = StringArgumentType.getString(ctx, "dado");
		String damageAbility = StringArgumentType.getString(ctx, "habDano").toLowerCase(java.util.Locale.ROOT);
		String damageType = StringArgumentType.getString(ctx, "tipoDano").toLowerCase(java.util.Locale.ROOT);

		MonsterRegistry.addCustomAttack(target, new MonsterRegistry.MonsterAttack(name, toHitAbility, dice, damageAbility, damageType, null, null, 0));
		ctx.getSource().sendSuccess(() -> Component.literal("Añadido \"" + name + "\" a " + target.getName().getString() + "."), true);
		return 1;
	}

	private static int removeAttack(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		Entity target = EntityArgument.getEntity(ctx, "objetivo");
		String name = StringArgumentType.getString(ctx, "nombre");

		if (!MonsterRegistry.removeCustomAttack(target, name)) {
			ctx.getSource().sendFailure(Component.literal(target.getName().getString() + " no tenía ningún ataque personalizado llamado \"" + name + "\"."));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Quitado \"" + name + "\" de " + target.getName().getString() + "."), true);
		return 1;
	}

	private static int clearAttacks(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		Entity target = EntityArgument.getEntity(ctx, "objetivo");
		MonsterRegistry.clearCustomAttacks(target);
		ctx.getSource().sendSuccess(() -> Component.literal("Ataques personalizados de " + target.getName().getString() + " borrados."), true);
		return 1;
	}

	private static int giveDmTool(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ItemStack stack = buildDmToolStack();

		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "jugadores");
		for (ServerPlayer target : targets) {
			target.getInventory().add(stack.copy());
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Entregada la Vara de DM a " + targets.size() + " jugador(es)."), true);
		return targets.size();
	}

	//Público: también lo usa la pestaña creativa (DndsheetsModCreativeTab).
	public static ItemStack buildDmToolStack() {
		ItemStack stack = net.hawthorn.dndsheets.ItemLook.DM_WAND.applyTo(
			new ItemStack(net.hawthorn.dndsheets.init.DndsheetsModItems.TOKEN.get()));
		CompoundTag dndTag = new CompoundTag();
		dndTag.putBoolean("dmtool", true);
		stack.getOrCreateTag().put("dndsheets", dndTag);
		stack.setHoverName(Component.literal("Vara de DM"));

		net.minecraft.nbt.ListTag lore = new net.minecraft.nbt.ListTag();
		lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(
			Component.literal("Clic derecho en un monstruo invocado: menú de sus ataques/hechizos.").withStyle(net.minecraft.ChatFormatting.GRAY))));
		lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(
			Component.literal("Agachado + clic derecho en un monstruo o armor stand: lo borra.").withStyle(net.minecraft.ChatFormatting.GRAY))));
		stack.getOrCreateTagElement("display").put("Lore", lore);

		return stack;
	}

	private static int giveMoveTool(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ItemStack stack = buildMoveToolStack();

		Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "jugadores");
		for (ServerPlayer target : targets) {
			target.getInventory().add(stack.copy());
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Entregada la Vara de Movimiento a " + targets.size() + " jugador(es)."), true);
		return targets.size();
	}

	//Público: también lo usa la pestaña creativa (DndsheetsModCreativeTab).
	public static ItemStack buildMoveToolStack() {
		ItemStack stack = net.hawthorn.dndsheets.ItemLook.MOVE_WAND.applyTo(
			new ItemStack(net.hawthorn.dndsheets.init.DndsheetsModItems.TOKEN.get()));
		CompoundTag dndTag = new CompoundTag();
		dndTag.putBoolean("movetool", true);
		stack.getOrCreateTag().put("dndsheets", dndTag);
		stack.setHoverName(Component.literal("Vara de Movimiento"));

		net.minecraft.nbt.ListTag lore = new net.minecraft.nbt.ListTag();
		lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(
			Component.literal("Clic derecho en un monstruo invocado: lo selecciona.").withStyle(net.minecraft.ChatFormatting.GRAY))));
		lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(
			Component.literal("Clic derecho en un bloque: mueve ahí al monstruo seleccionado.").withStyle(net.minecraft.ChatFormatting.GRAY))));
		stack.getOrCreateTagElement("display").put("Lore", lore);

		return stack;
	}
}
