package net.hawthorn.dndsheets;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * <p>Punto único de formato para todo lo que el mod anuncia por chat (tiradas, ataques, hechizos,
 * salvaciones de muerte). Antes cada sitio armaba una línea de texto plano; ahora todo pasa por aquí,
 * con una etiqueta de categoría en color, nombres en oro, impactos en verde, fallos en gris, daño en
 * rojo y eventos de muerte en rojo oscuro — para poder seguir lo que pasa de un vistazo en un chat con
 * mucho movimiento, en vez de tener que leer cada línea entera.</p>
 */
public class ChatFeedback {
	private static final ChatFormatting NAME = ChatFormatting.GOLD;
	private static final ChatFormatting HIT = ChatFormatting.GREEN;
	private static final ChatFormatting MISS = ChatFormatting.GRAY;
	private static final ChatFormatting DAMAGE = ChatFormatting.RED;
	private static final ChatFormatting ROLL = ChatFormatting.AQUA;
	private static final ChatFormatting DANGER = ChatFormatting.DARK_RED;
	private static final ChatFormatting GOOD = ChatFormatting.GREEN;
	private static final ChatFormatting COMBAT_TAG = ChatFormatting.DARK_AQUA;
	private static final ChatFormatting MAGIC_TAG = ChatFormatting.LIGHT_PURPLE;
	private static final ChatFormatting ROLL_TAG = ChatFormatting.BLUE;

	//Color único para activar un recurso de clase (Furia, Segundo Aliento, Inspiración Bárdica...).
	//Antes cada manager elegía su propio ChatFormatting suelto — Furia en RED, el mismo color que ya
	//significa "daño recibido" en el resto del mod — sin relación con ninguna paleta real. Package-private
	//a propósito: solo lo usan los managers de recurso de clase, todos en este mismo paquete.
	static final ChatFormatting RESOURCE = ChatFormatting.YELLOW;

	public static void broadcast(Entity source, Component message) {
		Level level = source.level();
		if (level.isClientSide() || level.getServer() == null) return;
		level.getServer().getPlayerList().broadcastSystemMessage(message, false);
	}

	private static MutableComponent tag(String labelKey, ChatFormatting color) {
		MutableComponent result = Component.literal("[").withStyle(color, ChatFormatting.BOLD);
		result.append(Component.translatable(labelKey));
		result.append(Component.literal("] "));
		return result;
	}

	//Nombres de personaje/monstruo/arma/hechizo vienen de JSON de contenido o de la hoja, no de código fijo
	//— un "§" en un nombre es el código de formato de Minecraft, así que sangraría color/negrita al resto
	//de la línea de chat de TODOS los jugadores aunque venga dentro de un Component.literal. name() y
	//dim() son los dos puntos por los que pasa ese texto interpolado en todo este archivo.
	private static String stripFormatting(String text) {
		return text == null ? null : text.replace('§', '?');
	}

	private static MutableComponent name(String text) {
		return Component.literal(stripFormatting(text)).withStyle(NAME, ChatFormatting.BOLD);
	}

	private static MutableComponent dim(String text) {
		return Component.literal(stripFormatting(text)).withStyle(ChatFormatting.GRAY);
	}

	//Overloads para texto ya traducido (Component.translatable) en vez de español fijo interpolado a mano.
	private static MutableComponent name(Component text) {
		return text.copy().withStyle(NAME, ChatFormatting.BOLD);
	}

	private static MutableComponent dim(Component text) {
		return text.copy().withStyle(ChatFormatting.GRAY);
	}

	//[Tirada] Fulano tira Fuerza: 15=15[1d20]+2
	public static MutableComponent roll(String characterName, String context, String rollText) {
		MutableComponent msg = tag("chat.dndsheets.tag.roll", ROLL_TAG).append(name(characterName));
		msg.append(dim(context != null && !context.isBlank()
			? Component.translatable("chat.dndsheets.roll.with_context", context)
			: Component.translatable("chat.dndsheets.roll.no_context")));
		return msg.append(Component.literal(rollText).withStyle(ROLL, ChatFormatting.BOLD));
	}

	//[Tirada] Fulano tira: 15=15[1d20]+2 (Fuerza) y 7=7[1d6]+2 (Daño)  — botones con varias tiradas a la vez.
	public static MutableComponent multiRoll(String characterName, java.util.List<String> contexts, java.util.List<String> rollTexts) {
		MutableComponent msg = tag("chat.dndsheets.tag.roll", ROLL_TAG).append(name(characterName))
			.append(dim(Component.translatable("chat.dndsheets.roll.multi_intro")));
		for (int i = 0; i < rollTexts.size(); i++) {
			if (i > 0) msg.append(dim(Component.translatable("chat.dndsheets.roll.and")));
			msg.append(Component.literal(rollTexts.get(i)).withStyle(ROLL, ChatFormatting.BOLD));
			String context = i < contexts.size() ? contexts.get(i) : null;
			if (context != null && !context.isBlank()) msg.append(dim(Component.translatable("chat.dndsheets.roll.context_paren", context)));
		}
		return msg;
	}

	//[Tirada] La tirada no funcionó: <motivo>
	public static MutableComponent rollFailed(String reason) {
		return tag("chat.dndsheets.tag.roll", ChatFormatting.RED).append(Component.literal(reason).withStyle(ChatFormatting.RED));
	}

	//[Combate] Fulano golpea con Espada: 7=7[1d6]+4  (muñeco de pruebas, sin CA de por medio)
	public static MutableComponent damageOnly(String characterName, String weaponName, String rollText) {
		return tag("chat.dndsheets.tag.combat", COMBAT_TAG)
			.append(name(characterName))
			.append(dim(Component.translatable("chat.dndsheets.combat.hits_with", weaponName)))
			.append(Component.literal(rollText).withStyle(DAMAGE, ChatFormatting.BOLD));
	}

	//[Combate] Fulano ataca a Mengano con Espada: 15 vs CA 13 → ¡Impacto! Daño: 7=7[1d6]+4
	public static MutableComponent attackResult(String attackerName, String targetName, String weaponName, String rollText, int ac, boolean hit, String damageText) {
		MutableComponent msg = tag("chat.dndsheets.tag.combat", COMBAT_TAG)
			.append(name(attackerName))
			.append(dim(Component.translatable("chat.dndsheets.combat.attacks")))
			.append(name(targetName))
			.append(dim(Component.translatable("chat.dndsheets.combat.with_weapon_vs_ac", weaponName, rollText, ac)));
		if (hit) {
			msg.append(Component.translatable("chat.dndsheets.combat.hit").withStyle(HIT, ChatFormatting.BOLD));
			msg.append(dim(Component.translatable("chat.dndsheets.combat.damage_label")));
			msg.append(Component.literal(damageText).withStyle(DAMAGE, ChatFormatting.BOLD));
		} else {
			msg.append(Component.translatable("chat.dndsheets.combat.miss").withStyle(MISS, ChatFormatting.ITALIC));
		}
		return msg;
	}

	//[Combate] ¡Fulano ha derrotado a Goblin!
	public static MutableComponent defeated(String attackerName, String targetName) {
		return tag("chat.dndsheets.tag.combat", COMBAT_TAG)
			.append(name(attackerName))
			.append(Component.translatable("chat.dndsheets.combat.defeated").withStyle(HIT, ChatFormatting.BOLD))
			.append(name(targetName))
			.append(dim("."));
	}

	//[Magia] Fulano cura a Mengano con Curar Heridas: 8=8[1d8]+3 PG
	public static MutableComponent healResult(String casterName, String targetName, String spellName, String healText) {
		return tag("chat.dndsheets.tag.magic", MAGIC_TAG)
			.append(name(casterName))
			.append(dim(Component.translatable("chat.dndsheets.magic.heals")))
			.append(name(targetName))
			.append(dim(Component.translatable("chat.dndsheets.magic.with_spell", spellName)))
			.append(Component.translatable("chat.dndsheets.magic.heal_amount", healText).withStyle(GOOD, ChatFormatting.BOLD));
	}

	//[Magia] Fulano lanza Bola de Fuego contra Mengano: salvación 12 vs CD 15 → Falla la salvación. Daño: 24
	public static MutableComponent saveResult(String casterName, String targetName, String spellName, String saveRollText, int dc, boolean saved, Component outcomeLabel, String damageText) {
		MutableComponent msg = tag("chat.dndsheets.tag.magic", MAGIC_TAG)
			.append(name(casterName))
			.append(dim(Component.translatable("chat.dndsheets.magic.casts_against", spellName)))
			.append(name(targetName))
			.append(dim(Component.translatable("chat.dndsheets.magic.save_vs_dc", saveRollText, dc)));
		msg.append(outcomeLabel.copy().withStyle(saved ? HIT : MISS, saved ? ChatFormatting.BOLD : ChatFormatting.ITALIC));
		if (damageText != null) {
			msg.append(dim(Component.translatable("chat.dndsheets.magic.damage_label")));
			msg.append(Component.literal(damageText).withStyle(DAMAGE, ChatFormatting.BOLD));
		}
		return msg;
	}

	//[Muerte] ¡Fulano ha caído a 0 PG y necesita salvaciones de muerte!
	public static MutableComponent downed(String characterName) {
		return tag("chat.dndsheets.tag.death", DANGER)
			.append(name(characterName))
			.append(Component.translatable("chat.dndsheets.death.downed").withStyle(DANGER));
	}

	//[Muerte] Fulano tira salvación de muerte: 15 → Éxitos ●●○ Fallos ○○○
	public static MutableComponent deathSaveRoll(String characterName, int rollValue, int successes, int failures) {
		return tag("chat.dndsheets.tag.death", DANGER)
			.append(name(characterName))
			.append(dim(Component.translatable("chat.dndsheets.death.save_roll")))
			.append(Component.literal(String.valueOf(rollValue)).withStyle(ROLL, ChatFormatting.BOLD))
			.append(dim(Component.translatable("chat.dndsheets.death.successes")))
			.append(Component.literal(marks(successes)).withStyle(GOOD))
			.append(dim(Component.translatable("chat.dndsheets.death.failures")))
			.append(Component.literal(marks(failures)).withStyle(DAMAGE));
	}

	private static String marks(int count) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < 3; i++) builder.append(i < count ? "●" : "○");
		return builder.toString();
	}

	//[Muerte] ¡Fulano saca un 20 natural en su salvación de muerte: vuelve en sí!
	public static MutableComponent naturalTwenty(String characterName) {
		return tag("chat.dndsheets.tag.death", GOOD)
			.append(name(characterName))
			.append(Component.translatable("chat.dndsheets.death.natural_twenty").withStyle(GOOD, ChatFormatting.BOLD));
	}

	//[Muerte] Fulano reanima a Mengano.
	public static MutableComponent revived(String reviverName, String targetName) {
		return tag("chat.dndsheets.tag.death", GOOD)
			.append(name(reviverName))
			.append(dim(Component.translatable("chat.dndsheets.death.revives")))
			.append(name(targetName))
			.append(dim("."));
	}
}
