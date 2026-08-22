package net.hawthorn.dndsheets.client;

import net.hawthorn.dndsheets.MonsterRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <p>Dibuja a un druida transformado como la bestia en la que está, en el cliente y <b>sin un solo
 * mixin</b>. Forge dispara {@link RenderPlayerEvent.Pre} justo para esto: se cancela el dibujado del
 * jugador y se dibuja otra cosa en su sitio.</p>
 *
 * <p>Que no haga falta un mixin es la razón por la que esto se escribió en vez de portar un mod de
 * transformación existente: los que hay parchean {@code PlayerRenderer} porque en su versión de Minecraft
 * no había evento, y {@code checkPortabilityCoupling} falla el build si aquí entra un mixin — el precio de
 * un port futuro es justo lo que ese test protege.</p>
 *
 * <p><b>Por qué los offsets van a cero.</b> {@code EntityRenderDispatcher.render} hace
 * {@code pushPose()} y {@code translate(x, y, z)} <em>antes</em> de llamar al renderizador, y este evento
 * se dispara dentro de ese renderizador — o sea, con la pila ya colocada sobre el jugador. Pasarle otra
 * vez la posición dibujaría la bestia al doble de distancia de la cámara.</p>
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class WildShapeRenderer {

	private static final Map<UUID, String> shapes = new HashMap<>();
	//Una entidad de mentira por tipo, reusada: construir una por fotograma y por jugador significa
	//construir sesenta por segundo y tirarlas, que es exactamente cómo se hace un mod que va a tirones.
	private static final Map<EntityType<?>, LivingEntity> dummies = new HashMap<>();

	private WildShapeRenderer() {
	}

	/** Un id vacío significa que ha vuelto a su forma. Lo manda {@code WildShapeWatcher}. */
	public static void setShape(UUID player, String monsterId) {
		if (monsterId == null || monsterId.isEmpty()) shapes.remove(player);
		else shapes.put(player, monsterId);
	}

	@SubscribeEvent
	public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
		Player player = event.getEntity();
		LivingEntity dummy = dummyFor(shapes.get(player.getUUID()));
		if (dummy == null) return;

		//La bestia se dibuja con la postura del jugador: sin esto mira siempre al norte y nunca camina.
		dummy.setPos(player.getX(), player.getY(), player.getZ());
		dummy.yBodyRot = player.yBodyRot;
		dummy.yBodyRotO = player.yBodyRotO;
		dummy.yHeadRot = player.yHeadRot;
		dummy.yHeadRotO = player.yHeadRotO;
		dummy.setYRot(player.getYRot());
		dummy.yRotO = player.yRotO;
		dummy.setXRot(player.getXRot());
		dummy.xRotO = player.xRotO;
		dummy.tickCount = player.tickCount;
		dummy.walkAnimation.setSpeed(player.walkAnimation.speed());
		dummy.walkAnimation.update(player.walkAnimation.position(), 1.0f);
		dummy.setShiftKeyDown(player.isShiftKeyDown());
		dummy.setInvisible(player.isInvisible());

		event.setCanceled(true);
		Minecraft.getInstance().getEntityRenderDispatcher().render(dummy, 0, 0, 0,
			player.getYRot(), event.getPartialTick(), event.getPoseStack(), event.getMultiBufferSource(),
			event.getPackedLight());
	}

	private static LivingEntity dummyFor(String monsterId) {
		if (monsterId == null) return null;
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(monsterId);
		if (block == null) return null;

		ResourceLocation loc = ResourceLocation.tryParse(block.baseEntityId());
		EntityType<?> type = loc == null ? null : ForgeRegistries.ENTITY_TYPES.getValue(loc);
		if (type == null) return null;

		//computeIfAbsent no vale: create() puede devolver null (una entidad que no se puede construir en el
		//cliente), y guardar ese null dejaría el hueco ocupado para siempre sin volver a intentarlo.
		LivingEntity cached = dummies.get(type);
		if (cached != null) return cached;

		Entity created = type.create(Minecraft.getInstance().level);
		if (!(created instanceof LivingEntity living)) return null;
		dummies.put(type, living);
		return living;
	}
}
