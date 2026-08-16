package net.hawthorn.dndsheets;

import net.minecraft.world.entity.Entity;

/**
 * <p>Lo que decide si un ataque acierta, sea quien sea el que ataca: la ventaja que aporta el estado del
 * objetivo, la cobertura del terreno, la CA efectiva tras reacciones defensivas, y si el impacto es
 * crítico.</p>
 *
 * <p><b>Por qué existe.</b> Esa parte estaba escrita <em>dos veces</em>, una en {@code CombatManager} (un
 * jugador ataca) y otra en {@code MonsterActionManager} (un monstruo ataca), y las dos copias se separaron
 * tres veces seguidas, siempre en la misma dirección: la regla nueva se escribía en la ruta del jugador y
 * la del monstruo se quedaba atrás.</p>
 *
 * <ul>
 *   <li>Un monstruo atacaba con {@code Advantage.NORMAL} fijo, así que se tragaba la mitad de lo que hacen
 *       las condiciones: "los ataques contra ti tienen ventaja" es media definición de derribado,
 *       apresado, paralizado, cegado e inconsciente.</li>
 *   <li>La cobertura se aplicaba solo cuando atacaba un jugador, o sea que un parapeto servía para que los
 *       monstruos se escondieran de los jugadores y nunca al revés.</li>
 *   <li>Esquivar iba a repetir la historia en cuanto se añadió.</li>
 * </ul>
 *
 * <p>Los tres se arreglaron uno a uno, que es exactamente la señal de que el arreglo no era ese. Con una
 * sola función, la regla siguiente entra en las dos rutas por construcción y no por acordarse.</p>
 *
 * <p>Lo que <b>no</b> entra aquí es lo que de verdad distingue a los dos: cómo se arma la tirada (un
 * jugador suma característica y competencia de su hoja, un monstruo su modificador fijo) y cómo se arma el
 * daño (armas, furtivo, castigo... frente a un dado del bloque). Eso son diferencias reales, no
 * duplicación.</p>
 */
final class AttackRules {

	/**
	 * @param targetAc CA contra la que se comparó de verdad, ya con cobertura y reacciones dentro — es la
	 *                 que hay que anunciar en el chat, o el número no cuadraría con el resultado.
	 * @param cover    para la nota del chat: un fallo contra una CA más alta de la que dice la hoja del
	 *                 monstruo se lee como un error del mod si no se dice por qué.
	 */
	record Against(int targetAc, Cover cover, boolean hit, boolean critical) {}

	private AttackRules() {
	}

	/**
	 * <p>Ventaja o desventaja del ataque entero: lo que aporta el estado del objetivo, si gastó su acción en
	 * Esquivar, y lo que traiga el atacante ({@code fromAttacker}: su propia ventaja pendiente, condiciones
	 * suyas...).</p>
	 *
	 * <p><b>Todo junto y de una vez, no por partes.</b> La regla de 5e es que con al menos una fuente de
	 * ventaja y al menos una de desventaja te quedas sin ninguna de las dos, <em>sin importar cuántas haya
	 * de cada</em>, y {@link DiceManager#combineAdvantage} implementa exactamente eso. Pero por lo mismo
	 * <b>no se puede anidar</b>: combinar primero lo del objetivo y meter el resultado en otra combinación
	 * convierte una ventaja y una desventaja que se anulaban en un "normal" indistinguible de "nada", y
	 * entonces la ventaja del atacante gana sola. Objetivo derribado (ventaja de cerca) que además Esquiva
	 * (desventaja), atacado por alguien con ventaja pendiente: la respuesta correcta es normal, y anidando
	 * salía ventaja. Por eso las fuentes del atacante entran aquí y no se combinan fuera.</p>
	 */
	static DiceManager.Advantage advantageAgainst(Combatant target, boolean melee, DiceManager.Advantage... fromAttacker) {
		DiceManager.Advantage[] sources = new DiceManager.Advantage[fromAttacker.length + 2];
		sources[0] = target.advantageAgainst(melee);
		//Esquivar se resuelve aquí y no dentro de advantageAgainst porque no es una condición del objetivo,
		//es una acción que gastó este asalto: Combatant no sabe de turnos ni debería.
		sources[1] = TurnActionManager.isDodging(target.entity()) ? DiceManager.Advantage.DISADVANTAGE : DiceManager.Advantage.NORMAL;
		System.arraycopy(fromAttacker, 0, sources, 2, fromAttacker.length);
		return DiceManager.combineAdvantage(sources);
	}

	/**
	 * <p>Resuelve la tirada ya hecha contra el objetivo: cobertura, CA efectiva, acierto y crítico.</p>
	 *
	 * @param melee si el ataque es cuerpo a cuerpo, que cambia dos cosas de 5e: un objetivo derribado es
	 *              más fácil de acertar de cerca y más difícil de lejos, y el crítico automático contra un
	 *              paralizado o inconsciente solo pasa de cerca.
	 */
	static Against against(Entity attacker, Combatant target, DiceManager.AttackRoll roll, boolean melee) {
		Cover cover = Cover.between(attacker, target.entity());
		int value = roll.outcome().result().getValue();

		int targetAc = target.armorClass() + cover.bonus();
		//Reacciones defensivas (Escudo): solo tiene sentido preguntarlas si el golpe de verdad depende de la
		//CA — un crítico siempre acierta y un pifia siempre falla, con o sin Escudo. A la reacción se le
		//descuenta la cobertura de la TIRADA en vez de sumársela a la CA: es el mismo margen, y así Escudo
		//sigue decidiendo sobre su propio número sin saber nada de parapetos.
		if (!roll.criticalHit() && !roll.criticalMiss()) {
			targetAc = target.reactiveArmorClass(value - cover.bonus()) + cover.bonus();
		}

		boolean hit = roll.criticalHit() || (!roll.criticalMiss() && value >= targetAc);
		//Crítico automático de 5e: cualquier impacto cuerpo a cuerpo contra un objetivo paralizado o
		//inconsciente es crítico, aunque el d20 no haya sacado un 20.
		boolean critical = roll.criticalHit() || (hit && melee && target.autoCritInMelee());
		return new Against(targetAc, cover, hit, critical);
	}
}
