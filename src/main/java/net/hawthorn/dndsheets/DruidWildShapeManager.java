package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <p>Druid Wild Shape: the player <b>turns into</b> a beast from the bestiary. It takes over their HP,
 * their AC, their physical abilities and their attack, and they look like it. When it ends — by time, at
 * will, or because the shape dropped to 0 HP — they become themselves again, with the health they had
 * before transforming.</p>
 *
 * <p><b>Where the state lives, and why it matters.</b> On the <b>sheet</b>, not in memory: the sheet
 * persists and syncs on its own, so disconnecting mid-shape — or the server crashing — doesn't leave
 * anyone with a bear's stats forever. It's the same reason conditions are stored where they're stored
 * (see {@code Combatant}).</p>
 *
 * <p><b>Why there's no {@code WildShapeCombatant}.</b> It was the obvious choice and it's the wrong one.
 * Everything that asks "what's its AC?", "how much HP does it have left?" or "what's its Strength
 * modifier?" already goes through the sheet and Minecraft's health attribute. So the transformation
 * <b>writes right there</b> the beast's numbers and stores the old ones to give back: the whole engine —
 * the monster deciding whether it hits, the DM's party list, the HUD, concentration — sees the shape
 * without any of them knowing it exists. A new combatant type would have forced every one of those paths
 * to learn to tell it apart.</p>
 *
 * <p><b>What it doesn't do</b>, and this is real 5e: it doesn't preserve the beast's saving throw
 * proficiencies, and it doesn't cap by CR (the bestiary doesn't carry that data). The DM decides which
 * beast is fair, the same way it's played at a table.</p>
 */
@Mod.EventBusSubscriber
public class DruidWildShapeManager {
	private static final int DURATION_ROUNDS = 10; //5e's 1 hour simplified to 10 rounds, same as Rage.
	private static final int DURATION_TICKS = 20 * 60;

	/** Default hit if the chosen beast declares no attack. */
	private static final String FALLBACK_DICE = "1d6";
	private static final String FALLBACK_ABILITY = "str";

	//The beast's stats get WRITTEN onto the sheet, so the druid's own have to be saved to give back
	//later. All under the same prefix: if something ever goes wrong, it's obvious at a glance what the
	//shape left behind.
	private static final String SHAPE_ID = "wildShapeId";
	private static final String RETURN_HP = "wildShapeReturnHp";
	private static final String OLD_AC = "wildShapeOldAc";
	private static final String OLD_ABILITY = "wildShapeOld_";
	//Whether they could fly BEFORE transforming (creative/spectator), not because of the shape — so
	//reverting doesn't take away a permission they already had. It lives on the sheet and not in memory
	//for the same reason as everything else here: it survives a disconnect midway through the 10 rounds.
	private static final String OLD_MAYFLY = "wildShapeOldMayfly";

	/** The abilities the shape changes. Int/Wis/Cha stay: in 5e the beast doesn't make you dumb. */
	private static final List<String> PHYSICAL = List.of("str", "dex", "con");
	private static final Map<String, String> LONG = Map.of(
		"str", "strength", "dex", "dexterity", "con", "constitution");

	/** The id of the beast they're shaped into, or {@code null} if not transformed. */
	@Nullable
	public static String shapeOf(JsonObject sheet) {
		if (sheet == null || !sheet.has(SHAPE_ID)) return null;
		String id = sheet.get(SHAPE_ID).getAsString();
		return id.isEmpty() ? null : id;
	}

	public static boolean isShifted(ServerPlayer player) {
		return shapeOf(SheetLoader.getServerSheet(player.getStringUUID())) != null;
	}

	/** Returns the druid to their own form without announcing it: used by character switching. See SheetLoader. */
	public static void clearFor(ServerPlayer player) {
		revert(player, false);
	}

	/**
	 * <p>The claw swipe: the dice of the first attack of the beast they're shaped into, which is what 5e
	 * calls "you use the beast's statistics." With no attack declared, a 1d6 by Strength — the same as
	 * before, so a half-written beast stays playable.</p>
	 */
	public static TraitRegistry.UnarmedProfile unarmedProfile(ServerPlayer player) {
		MonsterRegistry.MonsterStatBlock block = blockOf(SheetLoader.getServerSheet(player.getStringUUID()));
		if (block == null || block.attacks().isEmpty()) {
			return new TraitRegistry.UnarmedProfile(FALLBACK_DICE, FALLBACK_ABILITY);
		}
		MonsterRegistry.MonsterAttack attack = block.attacks().get(0);
		return new TraitRegistry.UnarmedProfile(attack.dice(), attack.damageAbility());
	}

	@Nullable
	private static MonsterRegistry.MonsterStatBlock blockOf(JsonObject sheet) {
		String id = shapeOf(sheet);
		return id == null ? null : MonsterRegistry.get(id);
	}

	/** The beasts that can be chosen: the bestiary filtered by type, no separate list to maintain. */
	public static List<String> beastIds() {
		List<String> beasts = new java.util.ArrayList<>();
		for (String id : MonsterRegistry.ids()) {
			MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(id);
			if (block != null && block.type() == CreatureType.BEAST) beasts.add(id);
		}
		return beasts;
	}

	public static void activate(ServerPlayer player, String monsterId) {
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null) return;
		if (shapeOf(sheet) != null) {
			//Already transformed: the second click reverts, which is what's expected of a toggle button
			//and avoids having to remember a command to change back.
			revert(player, true);
			return;
		}

		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(monsterId);
		if (block == null || block.type() != CreatureType.BEAST) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.wildshape.not_a_beast").withStyle(ChatFormatting.GRAY));
			return;
		}

		//5e: transforming is an action. Checked AFTER validating the beast (choosing badly shouldn't cost
		//anything) but BEFORE touching the sheet. tryAct returns true on its own outside combat
		//(TurnManager.active==false), so this doesn't restrict anything outside an encounter — it only
		//matters during turns.
		if (!TurnManager.tryAct(player)) {
			TurnManager.notifyCantAct(player);
			return;
		}

		//The health they return to is whatever they have RIGHT NOW, before anything is touched. In 5e you
		//come back with the HP you had when you transformed, and whatever damage the beast took stays with the beast.
		writeShape(sheet, block, (int) Math.ceil(player.getHealth()));
		setMaxHealth(player, block.maxHp());
		player.setHealth(block.maxHp());

		//Real flight speed (giant eagle, giant owl...), not just the model — without this the player kept
		//falling like anyone else does with a bird's body. It's saved whether they could ALREADY fly
		//before (creative/spectator) so reverting doesn't take it away; revert() restores it.
		sheet.addProperty(OLD_MAYFLY, player.getAbilities().mayfly);
		if (block.flies()) {
			player.getAbilities().mayfly = true;
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
		}

		SheetLoader.saveAndSync(player, sheet);
		WildShapeWatcher.broadcast(player, monsterId);
		CombatFx.activate(player);

		UUID uuid = player.getUUID();
		MinecraftServer server = player.getServer();
		TurnManager.scheduleExpiry(DURATION_ROUNDS, DURATION_TICKS, () -> {
			//Reattached by UUID instead of capturing the player: they may have disconnected during the 10
			//rounds, and then there's nobody to revert anything for (the sheet keeps it waiting for their
			//next login).
			ServerPlayer stillHere = server == null ? null : server.getPlayerList().getPlayer(uuid);
			if (stillHere != null) revert(stillHere, true);
		});

		player.sendSystemMessage(Component.translatable("chat.dndsheets.wildshape.start", ContentNames.of(block.name())).withStyle(ChatFeedback.RESOURCE));
	}

	/**
	 * <p>Undoes the transformation and gives the druid back who they were. {@code announce} false for the
	 * silent path (character switching): there's nothing to announce there because it's no longer that character.</p>
	 */
	public static void revert(ServerPlayer player, boolean announce) {
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (sheet == null || shapeOf(sheet) == null) return;

		int back = clearShape(sheet, (int) Math.ceil(player.getMaxHealth()));

		//The max is recomputed from class and level instead of being stored: it's the same computation
		//SheetLoader does when entering the world, and storing a number it already knows how to derive
		//would be a second source of truth.
		SheetLoader.applyClassHitPoints(player, sheet);
		//Never below 1: a shape dropping doesn't kill the druid, it reverts them (5e). Whoever wants them
		//dead will have to bring them down again, now in their own body.
		player.setHealth(Math.max(1f, Math.min(player.getMaxHealth(), back)));

		//Always lands (flying=false): if returning from a flying beast, they stop gliding in midair
		//instantly, which is real 5e. mayfly goes back to whatever it was BEFORE transforming — no
		//creative/spectator permission they already had gets taken away, and none the shape lent them stays either.
		boolean restoreMayfly = sheet.has(OLD_MAYFLY) && sheet.get(OLD_MAYFLY).getAsBoolean();
		sheet.remove(OLD_MAYFLY);
		player.getAbilities().mayfly = restoreMayfly;
		player.getAbilities().flying = false;
		player.onUpdateAbilities();

		SheetLoader.saveAndSync(player, sheet);
		WildShapeWatcher.broadcast(player, "");
		if (announce) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.wildshape.end").withStyle(ChatFormatting.GRAY));
		}
	}

	/**
	 * <p>The shape drops to 0: the druid comes back, they don't die. It runs at {@code HIGHEST} and
	 * cancels the event to arrive <b>before</b> {@code DeathSaveManager.onLivingDeath}, which would put
	 * them into death saves — a druid coming out of the shape isn't downed, they're just back in their
	 * own body.</p>
	 */
	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void onShapeDropped(LivingDeathEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		if (!(event.getEntity() instanceof ServerPlayer player)) return;
		if (!isShifted(player)) return;
		event.setCanceled(true);
		revert(player, true);
		player.sendSystemMessage(Component.translatable("chat.dndsheets.wildshape.dropped").withStyle(ChatFormatting.GRAY));
	}

	/**
	 * <p>Writes the beast's numbers onto the sheet and stores the druid's own underneath. Pure and with
	 * no player in front on purpose: it's the part that can leave a sheet corrupted forever — a druid with
	 * a bear's Strength and nothing to give it back — and {@code JsonContentSelfTest} runs without the
	 * game running.</p>
	 */
	static void writeShape(JsonObject sheet, MonsterRegistry.MonsterStatBlock block, int returnHp) {
		sheet.addProperty(SHAPE_ID, block.id());
		sheet.addProperty(RETURN_HP, returnHp);

		//Recording that there was NO override is just as important as recording what it was: without this
		//branch, a druid with no fixed AC would come back with the beast's AC set by hand forever.
		if (sheet.has("armorClassOverride")) sheet.addProperty(OLD_AC, sheet.get("armorClassOverride").getAsInt());
		else sheet.remove(OLD_AC);
		sheet.addProperty("armorClassOverride", block.ac());

		for (String ability : PHYSICAL) {
			String key = LONG.get(ability);
			sheet.addProperty(OLD_ABILITY + ability, sheet.has(key) ? sheet.get(key).getAsString() : "10");
			sheet.addProperty(key, String.valueOf(block.abilities().getOrDefault(ability, 10)));
		}
	}

	/** Undoes {@link #writeShape} and returns the HP the druid comes back to. */
	static int clearShape(JsonObject sheet, int fallbackHp) {
		for (String ability : PHYSICAL) {
			String key = LONG.get(ability);
			if (sheet.has(OLD_ABILITY + ability)) sheet.addProperty(key, sheet.get(OLD_ABILITY + ability).getAsString());
			sheet.remove(OLD_ABILITY + ability);
		}
		if (sheet.has(OLD_AC)) sheet.addProperty("armorClassOverride", sheet.get(OLD_AC).getAsInt());
		else sheet.remove("armorClassOverride");
		sheet.remove(OLD_AC);
		sheet.remove(SHAPE_ID);

		int back = sheet.has(RETURN_HP) ? sheet.get(RETURN_HP).getAsInt() : fallbackHp;
		sheet.remove(RETURN_HP);
		return back;
	}

	private static void setMaxHealth(ServerPlayer player, int value) {
		AttributeInstance attribute = player.getAttribute(Attributes.MAX_HEALTH);
		if (attribute != null) attribute.setBaseValue(value);
	}

	//--- Wild Shape item: activated from AbilityItemDispatcher instead of subscribing to the 3
	//interaction events separately. Same pattern as the Rage Totem
	//(BarbarianRageManager). ---

	static void tryUse(PlayerInteractEvent event) {
		event.setCanceled(true);
		if (!(event.getEntity() instanceof ServerPlayer player)) return;
		//Already transformed: the click reverts, without opening anything. Not transformed: a beast is chosen.
		if (isShifted(player)) {
			revert(player, true);
			return;
		}
		WildShapeWatcher.openPicker(player);
	}

	public static ItemStack buildWildShapeStack() {
		return AbilityItem.build(ItemLook.WILD_SHAPE, "wildShape", Component.translatable("chat.dndsheets.wildshape.item_name"),
			Component.translatable("chat.dndsheets.wildshape.item_lore", DURATION_ROUNDS).withStyle(ChatFormatting.GRAY));
	}
}
