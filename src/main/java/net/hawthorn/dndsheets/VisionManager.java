package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * <p>The other half of environment, after {@link Cover}: light. Being in the dark stops being decoration
 * and becomes the 5e rule — those who can't see attack with disadvantage and are attacked with advantage —
 * using the light level Minecraft already computes for every block. See {@link Light} for the thresholds
 * and why they're vanilla's and not a scale of our own.</p>
 *
 * <p><b>Rests entirely on pieces that already existed.</b> Blindness is {@link Condition#BLINDED}, with the
 * seven consequences it has had since Phase 0; there is no new combat rule anywhere. The only thing this
 * class contributes is <em>when</em> it's applied and removed.</p>
 *
 * <p><b>Holding a torch in hand counts as bright light.</b> Vanilla doesn't light from the hand, so
 * without this a character with a lit torch in their fist would be blind in a cave, which is absurd at
 * the table and worse on screen. The level comes from the block itself ({@code getLightEmission}), so a
 * lantern from another mod counts without having to be listed anywhere.</p>
 *
 * <p><b>Off by default</b> ({@code visionRules} in the toml, {@code /dndvision} at runtime). It's the most
 * intrusive rule this mod can have: blinding someone mining stone at night changes how Minecraft plays
 * outside the table, and the invariant "if you haven't configured anything, this is normal Minecraft"
 * outweighs fidelity. Creative and spectator are always excluded, which is the DM's practical escape
 * hatch.</p>
 *
 * <p><b>Deliberately simplified:</b> monsters see in the dark. In the SRD almost all of them have
 * darkvision, so the approximation is right most of the time, and the alternative — checking the light
 * around every living entity in the world every tick — costs far more than it fixes. A DM who wants to
 * blind one specific monster already has {@code /dndturns effect}.</p>
 */
@Mod.EventBusSubscriber
public final class VisionManager {
	/** Once per second: a block's light doesn't change fast enough to warrant checking it 20 times. */
	private static final int CHECK_INTERVAL_TICKS = 20;

	/**
	 * Effects last much longer than the interval so they overlap and don't flicker between one check and
	 * the next. And deliberately above 200 ticks: below that threshold vanilla makes night vision flicker
	 * to warn it's about to end, and here it isn't ending, it's being refreshed.
	 */
	private static final int EFFECT_TICKS = 240;

	/**
	 * <p>The "source" of a blindness caused by darkness. It isn't an entity: it's a negative number distinct
	 * from {@link Combatant#NO_SOURCE}, so it can't collide with any creature's id or be confused with
	 * "no source".</p>
	 *
	 * <p>It exists because removing the condition without knowing who applied it would be the same old
	 * mistake: stepping into the light would also clear a blindness that a spell or a DM just inflicted.
	 * Marking the source makes ownership exact, and it also survives a restart, which an in-memory
	 * {@code Set} does not — and conditions ARE persisted, so a marker that gets lost would leave the
	 * player blind forever.</p>
	 */
	static final int DARKNESS_SOURCE = -2;

	private VisionManager() {}

	@SubscribeEvent
	public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
		if (event.phase != TickEvent.Phase.END) return;
		if (!(event.player instanceof ServerPlayer player)) return;
		if (player.tickCount % CHECK_INTERVAL_TICKS != 0) return;
		if (!Config.visionRules()) return;
		update(player);
	}

	private static void update(ServerPlayer player) {
		Combatant combatant = Combatant.of(player);
		if (combatant == null) return; //No sheet, normal Minecraft.

		if (player.isCreative() || player.isSpectator()) {
			lift(player, combatant);
			return;
		}

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		boolean darkvision = CharacterRules.darkvisionFeetFor(sheet) > 0;
		Light around = rawLightAround(player);

		//Night vision is granted based on the REAL light, not the light seen after applying it: otherwise,
		//as soon as the trait turns darkness into dim light there would stop being a reason to grant it.
		if (around == Light.DARK && darkvision) grant(player, MobEffects.NIGHT_VISION);

		if (!around.withDarkvision(darkvision).blinds()) {
			lift(player, combatant);
			return;
		}

		grant(player, MobEffects.DARKNESS);
		if (combatant.hasCondition(Condition.BLINDED)) return;
		combatant.addCondition(Condition.BLINDED, DARKNESS_SOURCE);
		player.sendSystemMessage(Component.translatable("chat.dndsheets.vision.blinded")
			.withStyle(ChatFormatting.DARK_GRAY));
	}

	/**
	 * <p>Whether this person is in dim light RIGHT NOW: neither bright light nor dark enough to count as
	 * blind ({@link Condition#BLINDED}, which already has its own complete mechanic). This is the half of
	 * the light rule that {@link Light} documented as deliberately having no effect ("gives disadvantage on
	 * Perception checks that rely on sight, and here it does nothing mechanical") — see
	 * {@code RollAnnouncerProcedure}, the only caller, for where it actually becomes disadvantage.</p>
	 *
	 * <p>Same gate as the rest of this class: off if {@code visionRules} is disabled, or if whoever is
	 * asking is in creative/spectator. Without this, Perception disadvantage would turn on by itself even
	 * if the DM never enabled the vision rule.</p>
	 */
	public static boolean inDimLight(ServerPlayer player) {
		if (!Config.visionRules() || player.isCreative() || player.isSpectator()) return false;
		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		boolean darkvision = CharacterRules.darkvisionFeetFor(sheet) > 0;
		return rawLightAround(player).withDarkvision(darkvision) == Light.DIM;
	}

	/** Restores this player's sight, if it was us who had taken it away. */
	private static void lift(ServerPlayer player, Combatant combatant) {
		remove(player, MobEffects.DARKNESS);
		remove(player, MobEffects.NIGHT_VISION);
		if (combatant.sourceOf(Condition.BLINDED) != DARKNESS_SOURCE) return;
		combatant.removeCondition(Condition.BLINDED);
		player.sendSystemMessage(Component.translatable("chat.dndsheets.vision.restored").withStyle(ChatFormatting.DARK_GRAY));
	}

	/**
	 * Lifts the rule for everyone. Called by {@code /dndvision off}: without this, turning off the rule
	 * would leave whoever was in the dark at that moment blind forever, because the tick that would fix it
	 * is the very one that was just turned off.
	 */
	public static void liftAll(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Combatant combatant = Combatant.of(player);
			if (combatant != null) lift(player, combatant);
		}
	}

	private static Light rawLightAround(ServerPlayer player) {
		if (lightEmission(player.getMainHandItem()) > 0 || lightEmission(player.getOffhandItem()) > 0) {
			return Light.BRIGHT;
		}
		//At eye height, not at the feet: that's where you're looking from, and while standing in a tunnel
		//lit from above the two blocks can give different numbers.
		BlockPos eyes = BlockPos.containing(player.getEyePosition());
		return Light.fromLightLevel(player.level().getMaxLocalRawBrightness(eyes));
	}

	private static int lightEmission(ItemStack stack) {
		//The state's getLightEmission() is deprecated in vanilla because the normal way is to ask the world
		//at a position; here the block is in a hand and there's no position to give, so the state version
		//is exactly the right one. The number comes from the block (torch 14, lantern 15), not from a table
		//of ours: a lamp from another mod counts on its own.
		return stack.getItem() instanceof BlockItem item
			? item.getBlock().defaultBlockState().getLightEmission() : 0;
	}

	private static void grant(ServerPlayer player, MobEffect effect) {
		//ambient=true isn't cosmetic: it's what marks these effects as ours, so they can be removed without
		//touching night vision from a potion or from a DM (see remove).
		player.addEffect(new MobEffectInstance(effect, EFFECT_TICKS, 0, true, false, true));
	}

	private static void remove(ServerPlayer player, MobEffect effect) {
		MobEffectInstance active = player.getEffect(effect);
		if (active != null && active.isAmbient()) player.removeEffect(effect);
	}
}
