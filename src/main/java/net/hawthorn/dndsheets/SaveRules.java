package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * <p>Lo que decide cómo le sienta a alguien un conjuro de salvación, lo lance quien lo lance: la cobertura
 * que le da el terreno, la CD contra la que tira de verdad, si la supera, y cuánto daño acaba recibiendo.</p>
 *
 * <p>La otra mitad de {@link AttackRules}, y por la misma razón. Esto estaba escrito dos veces —
 * {@code SpellCastManager.castSaveSpell} cuando lanza un jugador y {@code MonsterActionManager.resolveSpell}
 * cuando lanza un monstruo— y las dos copias ya se habían separado: la cobertura solo contaba del lado del
 * jugador, así que parapetarse del aliento de un dragón, que es el caso de manual de la regla, no hacía
 * nada.</p>
 *
 * <p>La tolerancia con un objetivo sin bloque de estadísticas (un mob de otro mod: tira un d20 pelado en vez
 * de quedar fuera del conjuro) venía solo de la ruta del jugador. <b>No era un fallo</b> —el conjuro de un
 * monstruo apunta siempre a un jugador, así que su rama nunca llegaba ahí— pero ahora la regla es una sola,
 * y el día que un monstruo pueda apuntar a otra cosa ya se comporta igual.</p>
 *
 * <p>Lo que se queda fuera es lo que de verdad difiere entre las dos rutas: de dónde sale la CD (un jugador
 * la calcula de su hoja, un monstruo la trae escrita en su bloque), cómo se anuncia, y qué efecto de estado
 * cuelga del fallo.</p>
 */
final class SaveRules {

	/**
	 * @param dc  la CD contra la que se tiró de verdad, ya con la cobertura descontada — es la que hay que
	 *            anunciar, o el número no cuadraría con el resultado.
	 * @param finalDamage lo que hay que aplicar: entero, mitad, o nada.
	 */
	record Outcome(Cover cover, int dc, Combatant.SaveRoll roll, boolean saved, int finalDamage,
			String damageFormatted, Component label, boolean legendaryResistance) {}

	private SaveRules() {
	}

	/**
	 * <p>Resuelve la salvación. Devuelve {@code null} si no hay nada que resolver (el dado no se pudo tirar,
	 * o el objetivo no tiene con qué salvarse), y entonces quien llama no debe anunciar nada.</p>
	 *
	 * @param baseDc CD del lanzador, antes de cobertura.
	 */
	static Outcome resolve(Entity caster, Entity target, String saveAbility, int baseDc, String dice, boolean halfOnSave) {
		//La cobertura sube las salvaciones de DESTREZA y solo esas: es esquivar lo que un parapeto ayuda a
		//hacer, no aguantar un veneno ni resistir una sugestión. Se le resta a la CD en vez de sumarse a la
		//tirada porque es el mismo margen y el objetivo puede no tener hoja donde apuntar un bono.
		Cover cover = "dex".equals(saveAbility) ? Cover.between(caster, target) : Cover.NONE;
		int dc = baseDc - cover.bonus();

		//Hoja vacía a propósito: el daño de un conjuro son dados pelados, sin la característica de nadie.
		DiceManager.RollOutcome damageRoll = DiceManager.roll(new JsonObject(), dice);
		if (damageRoll.result() == null) return null;

		Combatant.SaveRoll saveRoll = rollSave(target, saveAbility);
		if (saveRoll == null || saveRoll.formatted() == null) return null;

		boolean saved = saveRoll.succeeds(dc);
		//Resistencia Legendaria: un jefe que falla puede decidir que no. Se resuelve AQUÍ, después de tirar y
		//antes de contar el daño, porque es exactamente eso — convertir un fallo en un éxito— y porque este
		//es el único sitio del mod donde se decide si una salvación se supera. Antes de unificar las dos
		//rutas habría habido que escribirlo dos veces, y la del monstruo se habría quedado atrás como se
		//quedó todo lo demás.
		boolean legendary = false;
		if (!saved && MonsterRegistry.spendLegendaryResistance(target)) {
			saved = true;
			legendary = true;
		}
		int rolled = damageRoll.result().getValue();
		int finalDamage = saved ? (halfOnSave ? rolled / 2 : 0) : rolled;
		Component label = Component.translatable(saved
			? (halfOnSave ? "chat.dndsheets.spell.save_half" : "chat.dndsheets.spell.save_none")
			: "chat.dndsheets.spell.save_fail");

		return new Outcome(cover, dc, saveRoll, saved, finalDamage,
			finalDamage > 0 ? damageRoll.formatted() + " (" + finalDamage + ")" : null, label, legendary);
	}

	private static Combatant.SaveRoll rollSave(Entity target, String saveAbility) {
		Combatant combatant = Combatant.of(target);
		if (combatant != null) return combatant.rollSave(saveAbility);
		//Jugador sin hoja cargada: no se resuelve nada. Mejor no hacer daño que hacerlo con características
		//inventadas.
		if (target instanceof Player) return null;
		//Mob de otro mod sin bloque de estadísticas (ver TurnManager.isMonster): no hay características que
		//consultar, así que tira el d20 pelado. Esta rama existía solo del lado del jugador, de modo que el
		//mismo mob era inmune a los conjuros de monstruo y no a los de jugador.
		return new Combatant.SaveRoll(DiceManager.roll(new JsonObject(), "1d20"), null);
	}
}
