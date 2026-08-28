package net.hawthorn.dndsheets.client;

import com.google.gson.JsonObject;
import com.mojang.math.Axis;
import net.hawthorn.dndsheets.SheetLoader;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * <p>La mano del lanzador mientras conjura. Un conjuro con tiempo de lanzamiento (ver
 * {@code CastingManager}) ya no sale en el mismo instante en que se pide, y sin nada que lo acompañe ese
 * segundo de espera se lee como que el mod se colgó: la mano sube, se echa atrás y tiembla un poco
 * mientras la carga de partículas crece delante de ella.</p>
 *
 * <p><b>Solo primera persona, y es una decisión, no un olvido.</b> Poner la pose de brazos en tercera
 * persona significaría escribir en {@code HumanoidModel.rightArmPose}, y {@code PlayerRenderer} la
 * reescribe en {@code setModelProperties} DESPUÉS de {@code RenderPlayerEvent.Pre} — o sea que sin un
 * mixin no hay dónde engancharse, y este mod no lleva mixins a propósito (ver "Portability" en
 * PROJECT_CONTEXT.md, comprobado por {@code checkPortabilityCoupling}). Lo que ven los demás jugadores es
 * la carga de partículas de {@code CombatFx.spellCharge}, que es de servidor y por tanto la ve todo el
 * mundo: la historia se cuenta igual, sin tocar la portabilidad.</p>
 *
 * <p>El estado llega por el parche de campo que {@code CastingManager} ya manda a la hoja del cliente
 * ({@code castingSpell}/{@code castingTicks}/{@code castingUntil}) — la misma tubería que la concentración
 * y la ventaja pendiente, sin ningún mensaje de red propio.</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class SpellCastAnimator {

	/**
	 * <p>Cuánto lleva conjurado, de 0 a 1, o -1 si no se está conjurando nada. Se calcula del tick de fin y
	 * de la duración en vez de recibir un parche por tick: la red ya trajo los dos números una sola vez, al
	 * empezar, y el resto lo saca el cliente solo.</p>
	 */
	private static float progress(float partialTick) {
		JsonObject sheet = SheetLoader.getClientSheet();
		if (sheet == null || !sheet.has("castingUntil") || !sheet.has("castingTicks")) return -1;

		net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return -1;

		int total = sheet.get("castingTicks").getAsInt();
		if (total <= 0) return -1;
		float left = sheet.get("castingUntil").getAsLong() - (level.getGameTime() + partialTick);
		//Fuera del intervalo no se pinta nada: si el parche de "he terminado" se perdiera, la mano volvería
		//sola a su sitio en vez de quedarse levantada para siempre.
		if (left <= 0 || left > total) return -1;
		return 1.0f - left / total;
	}

	@SubscribeEvent
	public static void onRenderHand(RenderHandEvent event) {
		float progress = progress(event.getPartialTick());
		if (progress < 0) return;

		//Solo la mano principal: conjurar con las dos a la vez se ve como un tic nervioso, no como un gesto.
		if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;

		//Se echa atrás y arriba según avanza la carga, con un tope: pasado cierto punto la mano se sale del
		//encuadre y deja de contar nada.
		event.getPoseStack().translate(0.0, progress * 0.22, progress * 0.30);
		event.getPoseStack().mulPose(Axis.XP.rotationDegrees(-progress * 45.0f));

		//Un temblor pequeño y rápido que CRECE con la carga: es lo que separa "sostiene algo" de "sostiene
		//algo que le cuesta". Sin él, la mano quieta en alto se lee como un fotograma congelado.
		float time = (Minecraft.getInstance().level.getGameTime() + event.getPartialTick()) * 0.9f;
		float tremor = (float) Math.sin(time) * 0.012f * progress;
		event.getPoseStack().translate(tremor, tremor * 0.5f, 0.0);
	}
}
