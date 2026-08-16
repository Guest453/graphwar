//  Pollinations AI integration for Graphwar.
//
//  This file is part of Graphwar.
//
//  Graphwar is free software: you can redistribute it and/or modify
//  it under the terms of the GNU General Public License as published by
//  the Free Software Foundation, either version 3 of the License, or
//  (at your option) any later version.
//
//  Graphwar is distributed in the hope that it will be useful,
//  but WITHOUT ANY WARRANTY; without even the implied warranty of
//  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//  GNU General Public License for more details.

//  You should have received a copy of the GNU General Public License
//  along with Graphwar.  If not, see <http://www.gnu.org/licenses/>.

package Graphwar;

import java.util.Random;

/**
 * The character a Pollinations bot plays.
 *
 * A personality is two things at once. It flavours the prompt, which is what
 * makes the bot's shots look the way they do, and it judges the simulated shot
 * afterwards, which is what makes the behaviour real rather than a suggestion.
 * The pacifist genuinely checks that it missed; the trickshot artist genuinely
 * refuses a boring straight line.
 */
public abstract class BotPersonality
{
	protected static final Random random = new Random();

	/** Everything a personality needs to know about the moment it is shooting in. */
	public static class Context
	{
		public int gameMode;

		/** Living soldiers on the bot's own team, not counting the one shooting. */
		public int friendsAlive;

		public int enemiesAlive;

		/** True once the pacifist has nothing left to protect. */
		public boolean berserk;
	}

	public abstract String getId();

	public abstract String getDisplayName();

	/** Added to the system prompt, after the rules of the game. */
	public abstract String promptFlavor(Context context);

	/** Whether this simulated shot is good enough to actually fire. */
	public abstract boolean accepts(ShotSimulator.Result result, Context context);

	/** Ranks candidates so the best one can be fired when none was perfect. */
	public abstract double score(ShotSimulator.Result result, Context context);

	public double temperature()
	{
		return 0.8;
	}

	public int maxAttempts()
	{
		return 3;
	}

	/**
	 * Whether the classic evolutionary AI may take over when the model cannot
	 * produce anything usable. A pacifist says no: the old AI plays to kill.
	 */
	public boolean allowsClassicFallback(Context context)
	{
		return true;
	}

	/** Tried in order when the model fails and the classic AI is not allowed. */
	public String[] fallbackFunctions()
	{
		return new String[] { "0" };
	}

	/** An occasional line in the chat. Null most of the time. */
	public String chatLine(Context context, boolean fired)
	{
		return null;
	}

	protected static String pick(String[] lines)
	{
		return lines[random.nextInt(lines.length)];
	}

	/** Feedback handed back to the model when a candidate is refused. */
	public String critique(ShotSimulator.Result result, Context context)
	{
		if(!result.valid)
		{
			return "That function was rejected: " + result.error + ".";
		}

		return "That shot was simulated and " + result.describe() + ". " + adviceFor(result, context);
	}

	protected String adviceFor(ShotSimulator.Result result, Context context)
	{
		if(result.hitFriend)
		{
			return "Never hit your own team. Change the shape so the curve stays clear of them.";
		}

		if(result.stoppedEarly)
		{
			return "Go over or under the terrain instead of into it.";
		}

		if(result.nearestEnemyDistance < Double.MAX_VALUE)
		{
			String direction = result.nearestEnemyVerticalGap > 0 ? "lower" : "higher";

			return "Adjust the shape so the curve arrives " + direction + " at x = "
					+ ShotSimulator.round(result.nearestEnemyX) + ".";
		}

		return "Try a different shape.";
	}

	protected static boolean cleanHit(ShotSimulator.Result result)
	{
		return result.valid && result.hitEnemy && !result.hitFriend && !result.hitSelf;
	}


	///// The roster /////

	public static final BotPersonality SOLDIER = new Soldier();
	public static final BotPersonality SNIPER = new Sniper();
	public static final BotPersonality PEACE = new Peace();
	public static final BotPersonality TRICKSHOT = new Trickshot();
	public static final BotPersonality CHAOS = new Chaos();
	public static final BotPersonality BERSERKER = new Berserker();

	private static final BotPersonality[] ALL = { SOLDIER, SNIPER, PEACE, TRICKSHOT, CHAOS, BERSERKER };

	/** Accepts the id and a few friendly aliases. Null when nothing matches. */
	public static BotPersonality forName(String name)
	{
		if(name == null)
		{
			return null;
		}

		String key = name.trim().toLowerCase();

		if(key.length() == 0)
		{
			return null;
		}

		for(int i = 0; i < ALL.length; i++)
		{
			if(ALL[i].getId().equals(key))
			{
				return ALL[i];
			}
		}

		if(key.equals("pacifist") || key.equals("peaceful") || key.equals("hippie"))
		{
			return PEACE;
		}
		if(key.equals("trick") || key.equals("trickshotter") || key.equals("showoff"))
		{
			return TRICKSHOT;
		}
		if(key.equals("crazy") || key.equals("madman") || key.equals("random"))
		{
			return CHAOS;
		}
		if(key.equals("rage") || key.equals("angry") || key.equals("brute"))
		{
			return BERSERKER;
		}
		if(key.equals("normal") || key.equals("default") || key.equals("grunt"))
		{
			return SOLDIER;
		}

		return null;
	}

	public static String listNames()
	{
		StringBuilder sb = new StringBuilder();

		for(int i = 0; i < ALL.length; i++)
		{
			if(i > 0)
			{
				sb.append(", ");
			}

			sb.append(ALL[i].getId());
		}

		return sb.toString();
	}


	///// Implementations /////

	/** Plays the game straight: hit an enemy, spare your own team. */
	private static class Soldier extends BotPersonality
	{
		public String getId()
		{
			return "soldier";
		}

		public String getDisplayName()
		{
			return "Soldier";
		}

		public String promptFlavor(Context context)
		{
			return "You are a competent, no nonsense soldier. Pick the simplest function that hits an enemy "
					+ "without endangering your own team. Prefer gentle arcs you can aim reliably.";
		}

		public boolean accepts(ShotSimulator.Result result, Context context)
		{
			return cleanHit(result);
		}

		public double score(ShotSimulator.Result result, Context context)
		{
			if(!result.valid)
			{
				return -1000000;
			}

			double points = 0;

			points += result.hitEnemy ? 1000000 : 0;
			points -= result.hitFriend ? 2000000 : 0;
			points -= result.hitSelf ? 3000000 : 0;
			points -= Math.min(result.nearestEnemyDistance, 1000);

			return points;
		}
	}

	/** The same goal, pursued with more patience and less imagination. */
	private static class Sniper extends BotPersonality
	{
		public String getId()
		{
			return "sniper";
		}

		public String getDisplayName()
		{
			return "Sniper";
		}

		public String promptFlavor(Context context)
		{
			return "You are a precision marksman. Accuracy is everything: compute the shape carefully, "
					+ "prefer functions whose value you can predict exactly at the enemy's x, and refine your "
					+ "previous attempt rather than starting over. Boring and lethal beats clever and wide.";
		}

		public double temperature()
		{
			return 0.35;
		}

		public int maxAttempts()
		{
			return 4;
		}

		public boolean accepts(ShotSimulator.Result result, Context context)
		{
			return cleanHit(result);
		}

		public double score(ShotSimulator.Result result, Context context)
		{
			return SOLDIER.score(result, context);
		}

		public String chatLine(Context context, boolean fired)
		{
			if(random.nextInt(100) < 15)
			{
				return pick(new String[] { "Hold still.", "One shot.", "Wind is fine." });
			}

			return null;
		}
	}

	/**
	 * Refuses to hurt anyone, and misses on purpose to prove it, until the last
	 * of its team falls. Then it stops being polite.
	 */
	private static class Peace extends BotPersonality
	{
		public String getId()
		{
			return "peace";
		}

		public String getDisplayName()
		{
			return "Peace";
		}

		public String promptFlavor(Context context)
		{
			if(context.berserk)
			{
				return "Your entire team is dead. The pacifism is over. You have perfect knowledge of this "
						+ "battlefield and you are going to use it: hit an enemy, and do not waste a single turn. "
						+ "There is nobody left to protect, so the only thing that matters is the kill.";
			}

			return "You are a pacifist who was drafted into this war and refuses to take a life. "
					+ "You must MISS on purpose. Fire a beautiful, dramatic arc that sails clearly past everyone "
					+ "and hits nothing at all: a gentle sine wave, a soaring parabola, something that looks "
					+ "impressive and harms nobody. Come reasonably close so it looks like you are trying, "
					+ "but never actually hit a soldier of either team.";
		}

		public double temperature()
		{
			return 0.9;
		}

		public int maxAttempts()
		{
			return 3;
		}

		public boolean accepts(ShotSimulator.Result result, Context context)
		{
			if(!result.valid)
			{
				return false;
			}

			if(context.berserk)
			{
				return cleanHit(result);
			}

			// A pacifist's shot is only acceptable if it truly harms nobody.
			return result.hitNobody();
		}

		public double score(ShotSimulator.Result result, Context context)
		{
			if(!result.valid)
			{
				return -1000000;
			}

			if(context.berserk)
			{
				return SOLDIER.score(result, context);
			}

			double points = 0;

			points -= result.hitEnemy ? 1000000 : 0;
			points -= result.hitFriend ? 1000000 : 0;
			points -= result.hitSelf ? 1000000 : 0;

			// A near miss is the whole performance, so aim to pass a couple of
			// units away rather than as far away as possible.
			if(result.nearestEnemyDistance < Double.MAX_VALUE)
			{
				points -= Math.abs(result.nearestEnemyDistance - 3.0);
			}

			return points;
		}

		public boolean allowsClassicFallback(Context context)
		{
			// The evolutionary AI plays to kill, which is the one thing this
			// bot will not do. It only gets the wheel once the gloves are off.
			return context.berserk;
		}

		public String[] fallbackFunctions()
		{
			return new String[] { "sin(x/3)*8", "cos(x/4)*10", "(x^2)/40", "-(x^2)/40", "8", "-8", "0" };
		}

		public String chatLine(Context context, boolean fired)
		{
			if(context.berserk)
			{
				return null;	// the player announces the turn itself
			}

			if(random.nextInt(100) < 30)
			{
				return pick(new String[] {
						"I will not shoot at you.",
						"Missed again. What a shame.",
						"There is still time to stop this.",
						"Beautiful curve, don't you think?" });
			}

			return null;
		}
	}

	/** Will only take the shot if it looks spectacular on the way in. */
	private static class Trickshot extends BotPersonality
	{
		public String getId()
		{
			return "trickshot";
		}

		public String getDisplayName()
		{
			return "Trickshot";
		}

		public String promptFlavor(Context context)
		{
			return "You are a showman who refuses to win boringly. A straight line is an insult. "
					+ "Your shot must still hit an enemy, but it has to arrive the hard way: loop it over the "
					+ "terrain, wave it through a gap, curl it down from above. Combine sin or cos with a "
					+ "polynomial, for example sin(x/2)*4 + (x^2)/60, so the shot weaves on its way to the target. "
					+ "Style first, but the target still has to fall.";
		}

		public double temperature()
		{
			return 1.0;
		}

		public int maxAttempts()
		{
			return 4;
		}

		public boolean accepts(ShotSimulator.Result result, Context context)
		{
			return cleanHit(result) && isFlashy(result);
		}

		private boolean isFlashy(ShotSimulator.Result result)
		{
			return result.turningPoints >= 2 || result.grazes >= 1 || result.pathLength > 70;
		}

		public double score(ShotSimulator.Result result, Context context)
		{
			if(!result.valid)
			{
				return -1000000;
			}

			double points = SOLDIER.score(result, context);

			points += result.turningPoints * 500.0;
			points += result.grazes * 250.0;
			points += result.pathLength;

			return points;
		}

		protected String adviceFor(ShotSimulator.Result result, Context context)
		{
			if(cleanHit(result) && !isFlashy(result))
			{
				return "It hit, but it was far too plain. Keep the impact and add waves or a much longer "
						+ "path: add an oscillating term so the shot changes direction several times on the way.";
			}

			return super.adviceFor(result, context);
		}

		public String chatLine(Context context, boolean fired)
		{
			if(random.nextInt(100) < 25)
			{
				return pick(new String[] {
						"Watch this one.",
						"Anyone can shoot straight.",
						"Style points, please.",
						"No-scope, round the houses." });
			}

			return null;
		}
	}

	/** Fires whatever the model dreams up and lets the plane sort it out. */
	private static class Chaos extends BotPersonality
	{
		public String getId()
		{
			return "chaos";
		}

		public String getDisplayName()
		{
			return "Chaos";
		}

		public String promptFlavor(Context context)
		{
			return "You are gloriously unhinged. Invent the strangest legal function you can: nest the "
					+ "allowed operations, stack sines on exponentials, pick absurd coefficients. You do not "
					+ "especially care what it hits. It only has to be valid, and it has to be interesting.";
		}

		public double temperature()
		{
			return 1.3;
		}

		public int maxAttempts()
		{
			return 2;
		}

		public boolean accepts(ShotSimulator.Result result, Context context)
		{
			return result.valid;
		}

		public double score(ShotSimulator.Result result, Context context)
		{
			return result.valid ? result.pathLength + result.turningPoints * 100.0 : -1000000;
		}

		public String[] fallbackFunctions()
		{
			return new String[] { "sin(x)*exp(x/10)", "tan(x/7)*3", "sin(x/2)*cos(x/3)*9" };
		}

		public String chatLine(Context context, boolean fired)
		{
			if(random.nextInt(100) < 30)
			{
				return pick(new String[] {
						"Let's see what this does!",
						"Mathematics is a suggestion.",
						"I have no idea where that went.",
						"WHEEEE" });
			}

			return null;
		}
	}

	/** Wants the kill badly enough to accept collateral damage. */
	private static class Berserker extends BotPersonality
	{
		public String getId()
		{
			return "berserker";
		}

		public String getDisplayName()
		{
			return "Berserker";
		}

		public String promptFlavor(Context context)
		{
			return "You are a berserker. Kill an enemy this turn. You would rather take the shot and clip a "
					+ "friendly than let the enemy live another round, so never hold back for safety, "
					+ "but an enemy must go down.";
		}

		public double temperature()
		{
			return 0.9;
		}

		public boolean accepts(ShotSimulator.Result result, Context context)
		{
			return result.valid && result.hitEnemy && !result.hitSelf;
		}

		public double score(ShotSimulator.Result result, Context context)
		{
			if(!result.valid)
			{
				return -1000000;
			}

			double points = 0;

			points += result.hitEnemy ? 1000000 : 0;
			points -= result.hitSelf ? 3000000 : 0;
			points -= result.hitFriend ? 100000 : 0;	// regrettable, not disqualifying
			points -= Math.min(result.nearestEnemyDistance, 1000);

			return points;
		}

		public String chatLine(Context context, boolean fired)
		{
			if(random.nextInt(100) < 25)
			{
				return pick(new String[] { "RAAAAGH", "Nobody move.", "Sorry in advance." });
			}

			return null;
		}
	}
}
