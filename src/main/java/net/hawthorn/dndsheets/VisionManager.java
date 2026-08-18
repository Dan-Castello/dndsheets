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
 * <p>La otra mitad del entorno, después de {@link Cover}: la luz. Estar a oscuras deja de ser decoración y
 * pasa a ser la regla de 5e —quien no ve, ataca con desventaja y le atacan con ventaja— usando el nivel de
 * luz que Minecraft ya calcula en cada bloque. Ver {@link Light} para los umbrales y por qué son los de
 * vanilla y no una escala propia.</p>
 *
 * <p><b>Se apoya entero en piezas que ya existían.</b> La ceguera es {@link Condition#CEGADO}, con las siete
 * consecuencias que ya tenía desde la Fase 0; no hay una regla nueva de combate en ningún sitio. Lo único
 * que aporta esta clase es <em>cuándo</em> se pone y se quita.</p>
 *
 * <p><b>Llevar una antorcha en la mano cuenta como luz brillante.</b> Vanilla no ilumina desde la mano, así
 * que sin esto un personaje con una antorcha encendida en el puño estaría ciego en una cueva, que es
 * absurdo en la mesa y peor en pantalla. El nivel lo pone el propio bloque ({@code getLightEmission}), así
 * que un farol de otro mod cuenta sin que haya que apuntarlo en ninguna lista.</p>
 *
 * <p><b>Apagado por defecto</b> ({@code visionRules} en el toml, {@code /dndvision} en caliente). Es la
 * regla más intrusiva que puede tener este mod: cegar a quien pica piedra de noche cambia cómo se juega a
 * Minecraft fuera de la mesa, y el invariante de "si no has configurado nada, esto es Minecraft normal" pesa
 * más que la fidelidad. Creativo y espectador quedan siempre fuera, que es la salida práctica del DM.</p>
 *
 * <p><b>Simplificado a propósito:</b> los monstruos ven en la oscuridad. En el SRD casi todos tienen visión
 * en la oscuridad, así que la aproximación acierta la mayoría de las veces, y la alternativa —mirar la luz
 * de cada entidad viva del mundo en cada tick— cuesta mucho más de lo que corrige. Un DM que quiera cegar a
 * un monstruo concreto ya tiene {@code /dndturns effect}.</p>
 */
@Mod.EventBusSubscriber
public final class VisionManager {
	/** Una vez por segundo: la luz de un bloque no cambia lo bastante deprisa como para mirarla 20 veces. */
	private static final int CHECK_INTERVAL_TICKS = 20;

	/**
	 * Los efectos duran mucho más que el intervalo para que se solapen y no parpadeen entre comprobación y
	 * comprobación. Y por encima de 200 ticks a propósito: por debajo de ese umbral vanilla hace parpadear
	 * la visión nocturna para avisar de que se acaba, y aquí no se acaba, se está refrescando.
	 */
	private static final int EFFECT_TICKS = 240;

	/**
	 * <p>La "fuente" de una ceguera causada por la oscuridad. No es una entidad: es un negativo distinto de
	 * {@link Combatant#NO_SOURCE}, así que no puede colisionar con el id de ninguna criatura ni confundirse
	 * con "sin fuente".</p>
	 *
	 * <p>Existe porque quitar la condición sin saber quién la puso sería el mismo error de siempre: al salir
	 * a la luz se borraría también la ceguera que acaba de echarte un conjuro o un DM. Marcar la fuente hace
	 * la pertenencia exacta, y además sobrevive a un reinicio, cosa que un {@code Set} en memoria no hace —
	 * y las condiciones sí se persisten, así que un marcador que se pierde deja al jugador ciego para
	 * siempre.</p>
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
		if (combatant == null) return; //Sin ficha, Minecraft normal.

		if (player.isCreative() || player.isSpectator()) {
			lift(player, combatant);
			return;
		}

		JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		boolean darkvision = CharacterRules.darkvisionFeetFor(sheet) > 0;
		Light around = rawLightAround(player);

		//La visión nocturna se concede por la luz REAL, no por la que se ve tras aplicarla: si no, en cuanto
		//el rasgo convierte la oscuridad en penumbra dejaría de haber motivo para concederla.
		if (around == Light.DARK && darkvision) grant(player, MobEffects.NIGHT_VISION);

		if (!around.withDarkvision(darkvision).blinds()) {
			lift(player, combatant);
			return;
		}

		grant(player, MobEffects.DARKNESS);
		if (combatant.hasCondition(Condition.CEGADO)) return;
		combatant.addCondition(Condition.CEGADO, DARKNESS_SOURCE);
		player.sendSystemMessage(Component.literal(
			"Estás a oscuras: atacas con desventaja y te atacan con ventaja. Enciende algo.")
			.withStyle(ChatFormatting.DARK_GRAY));
	}

	/** Devuelve a este jugador a la vista, si es que se la habíamos quitado nosotros. */
	private static void lift(ServerPlayer player, Combatant combatant) {
		remove(player, MobEffects.DARKNESS);
		remove(player, MobEffects.NIGHT_VISION);
		if (combatant.sourceOf(Condition.CEGADO) != DARKNESS_SOURCE) return;
		combatant.removeCondition(Condition.CEGADO);
		player.sendSystemMessage(Component.literal("Vuelves a ver.").withStyle(ChatFormatting.DARK_GRAY));
	}

	/**
	 * Levanta la regla de todo el mundo. La llama {@code /dndvision off}: sin esto, apagar la regla dejaría
	 * ciego para siempre a quien estuviera a oscuras en ese momento, porque el tick que lo arreglaría es el
	 * mismo que se acaba de apagar.
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
		//A la altura de los ojos y no de los pies: es donde se mira desde, y estando de pie en un túnel
		//iluminado por arriba las dos casillas pueden dar números distintos.
		BlockPos eyes = BlockPos.containing(player.getEyePosition());
		return Light.fromLightLevel(player.level().getMaxLocalRawBrightness(eyes));
	}

	private static int lightEmission(ItemStack stack) {
		//getLightEmission() del estado está deprecado en vanilla porque lo normal es preguntárselo al mundo
		//en una posición; aquí el bloque está en una mano y no hay posición que dar, así que la versión del
		//estado es justo la correcta. El número lo pone el bloque (antorcha 14, farol 15), no una tabla
		//nuestra: la lámpara de otro mod cuenta sola.
		return stack.getItem() instanceof BlockItem item
			? item.getBlock().defaultBlockState().getLightEmission() : 0;
	}

	private static void grant(ServerPlayer player, MobEffect effect) {
		//ambient=true no es cosmético: es lo que marca estos efectos como nuestros, para poder quitarlos sin
		//tocar la visión nocturna de una poción o de un DM (ver remove).
		player.addEffect(new MobEffectInstance(effect, EFFECT_TICKS, 0, true, false, true));
	}

	private static void remove(ServerPlayer player, MobEffect effect) {
		MobEffectInstance active = player.getEffect(effect);
		if (active != null && active.isAmbient()) player.removeEffect(effect);
	}
}
