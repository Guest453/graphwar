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

import GraphServer.Constants;

/**
 * A computer player that asks a language model, served through Pollinations,
 * for the function to shoot.
 *
 * Every candidate the model returns is fired inside ShotSimulator first. The
 * bot's personality then judges the result, and either the shot goes out or the
 * model is told exactly what went wrong and asked again. That loop is what
 * makes the characters real: the pacifist can verify that it missed, and the
 * trickshot artist can throw away a hit that was not pretty enough.
 *
 * It extends ComputerPlayer on purpose. Every place in the game that decides
 * whether a player is a bot does an instanceof check against ComputerPlayer,
 * and inheriting also keeps the original genetic algorithm around as a last
 * resort for the personalities that permit it.
 */
public class PollinationsPlayer extends ComputerPlayer
{
	/** Left after the requests so a failed turn still has time for the fallback. */
	private static final int TIME_RESERVED_FOR_FALLBACK = 12000;

	/** How long a single model call may take. */
	private static final int REQUEST_TIMEOUT = 20000;

	private static final int MAP_COLUMNS = 51;
	private static final int MAP_ROWS = 21;

	private static final boolean DEBUG = System.getProperty("graphwar.pollinations.debug") != null;

	private final Graphwar graphwar;
	private final PollinationsClient client;
	private final BotPersonality personality;

	private volatile boolean thinking;
	private volatile boolean cancelled;

	/** The pacifist only says this once. */
	private boolean announcedBerserk;

	/** A candidate shot and what it did in the simulation. */
	private static class Candidate
	{
		private final String function;
		private final double angle;
		private final ShotSimulator.Result result;
		private final double score;

		private Candidate(String function, double angle, ShotSimulator.Result result, double score)
		{
			this.function = function;
			this.angle = angle;
			this.result = result;
			this.score = score;
		}
	}

	public PollinationsPlayer(String name, int playerID, int team, boolean localPlayer, int numSoldiers, boolean ready, int level, String model, BotPersonality personality, Graphwar graphwar)
	{
		super(name, playerID, team, localPlayer, numSoldiers, ready, level, graphwar);

		this.graphwar = graphwar;
		this.client = new PollinationsClient(model);
		this.personality = personality != null ? personality : BotPersonality.SOLDIER;
		this.thinking = false;
		this.cancelled = false;
		this.announcedBerserk = false;
	}

	public String getModel()
	{
		return client.getModel();
	}

	public BotPersonality getPersonality()
	{
		return personality;
	}

	public void thinkFunction()
	{
		// Unlike the evolutionary bot there is nothing to gain from thinking
		// ahead on someone else's turn, and every call costs a request, so we
		// only reach for the model when the turn is actually ours.
		if(graphwar.getGameData().getCurrentTurnPlayer() != this)
		{
			return;
		}

		if(thinking)
		{
			return;
		}

		thinking = true;
		cancelled = false;

		Thread thread = new Thread(new Runnable()
		{
			public void run()
			{
				think();
			}
		}, "pollinations-" + getName());

		thread.setDaemon(true);
		thread.start();
	}

	public void stopThinkFunction()
	{
		cancelled = true;
		thinking = false;

		super.stopThinkFunction();
	}

	private void think()
	{
		try
		{
			int gameMode = graphwar.getGameData().getGameMode();
			Soldier shooter = this.getCurrentTurnSoldier();

			if(shooter == null)
			{
				fallback(null, "no soldier to shoot with");
				return;
			}

			BotPersonality.Context context = buildContext(shooter);

			announceBerserkOnce(context);

			Candidate chosen = askModel(gameMode, shooter, context);

			if(cancelled || graphwar.getGameData().getCurrentTurnPlayer() != this)
			{
				thinking = false;
				return;
			}

			if(chosen != null)
			{
				fire(chosen, gameMode, context);
			}
			else
			{
				fallback(context, "the model produced nothing this personality would fire");
			}
		}
		catch(Throwable t)
		{
			// A bot must never take the game down with it.
			fallback(null, t.getClass().getSimpleName() + ": " + t.getMessage());
		}
	}

	/**
	 * Talks to the model until the personality is satisfied or the attempts run
	 * out, and returns the shot to take. Null when nothing was usable at all.
	 */
	private Candidate askModel(int gameMode, Soldier shooter, BotPersonality.Context context)
	{
		// Without a key the free tier only serves small requests, so the rules
		// and the battlefield are cut down to what will actually go through.
		boolean compact = !client.hasApiKey();

		String systemPrompt = buildSystemPrompt(gameMode, personality, context, compact);
		String statePrompt = buildStatePrompt(gameMode, shooter, context, compact);

		if(DEBUG)
		{
			System.err.println("[pollinations] " + getName() + " state:\n" + statePrompt);
		}

		Candidate best = null;
		StringBuilder history = new StringBuilder();

		int attempts = personality.maxAttempts();

		for(int attempt = 0; attempt < attempts; attempt++)
		{
			if(cancelled || !hasTimeLeft())
			{
				break;
			}

			String prompt = statePrompt + history.toString();
			String reply;

			try
			{
				reply = client.chat(systemPrompt, prompt, personality.temperature(), REQUEST_TIMEOUT);
			}
			catch(Exception e)
			{
				System.err.println("[pollinations] " + getName() + " request failed: " + e.getMessage());
				break;
			}

			if(DEBUG)
			{
				System.err.println("[pollinations] " + getName() + " reply:\n" + reply);
			}

			String function = extractFunction(reply);

			if(function == null)
			{
				history.append("\n\nYour previous answer contained no function. Answer with JSON only.");
				continue;
			}

			double angle = (gameMode == Constants.SND_ODE) ? extractAngleFrom(reply) : 0;

			ShotSimulator.Result result = ShotSimulator.simulate(graphwar, this, gameMode, function, angle);
			double score = personality.score(result, context);

			if(best == null || score > best.score)
			{
				best = new Candidate(function, angle, result, score);
			}

			if(personality.accepts(result, context))
			{
				return new Candidate(function, angle, result, score);
			}

			history.append("\n\nYou already tried \"").append(function).append("\". ");
			history.append(personality.critique(result, context));
			history.append(" Give a different function.");
		}

		// Nothing satisfied the personality outright. The best near miss is
		// still better than wasting the turn, as long as it does not do the one
		// thing this character refuses to do.
		if(best != null && best.result.valid && !isUnacceptable(best, context))
		{
			return best;
		}

		return null;
	}

	/** Guards the line a personality must never cross, even when compromising. */
	private boolean isUnacceptable(Candidate candidate, BotPersonality.Context context)
	{
		if(personality == BotPersonality.PEACE && !context.berserk)
		{
			// A pacifist would rather throw the turn away than land a hit.
			return !candidate.result.hitNobody();
		}

		return candidate.result.hitSelf || candidate.result.hitFriend;
	}

	private void fire(Candidate candidate, int gameMode, BotPersonality.Context context)
	{
		if(gameMode == Constants.SND_ODE)
		{
			graphwar.getGameData().setAngle(candidate.angle);
		}

		graphwar.getGameData().sendFunction(candidate.function);

		thinking = false;

		System.out.println("[pollinations] " + getName() + " (" + personality.getId() + "/" + client.getModel()
				+ ") fires y = " + candidate.function + " - " + candidate.result.describe());

		say(personality.chatLine(context, true));
	}

	/**
	 * Plays the turn without the model. Personalities that allow it hand over to
	 * the original evolutionary AI; the ones that do not fall back to their own
	 * safe repertoire.
	 */
	private void fallback(BotPersonality.Context context, String reason)
	{
		thinking = false;

		if(cancelled)
		{
			return;
		}

		System.err.println("[pollinations] " + getName() + " could not use the model: " + reason);

		if(context != null && !personality.allowsClassicFallback(context))
		{
			// This character refuses to let the evolutionary AI play for it,
			// and the evolutionary AI plays to kill. If its own repertoire has
			// nothing either, it would rather lose the turn than break
			// character, so this is where the turn ends.
			if(!fireFallbackFunction(context))
			{
				System.err.println("[pollinations] " + getName() + " holds its fire rather than play to kill");
			}

			return;
		}

		super.thinkFunction();
	}

	/** Tries the personality's own canned shots and fires the first one it likes. */
	private boolean fireFallbackFunction(BotPersonality.Context context)
	{
		int gameMode = graphwar.getGameData().getGameMode();
		String[] functions = personality.fallbackFunctions();

		Candidate best = null;

		for(int i = 0; i < functions.length; i++)
		{
			ShotSimulator.Result result = ShotSimulator.simulate(graphwar, this, gameMode, functions[i], 0);

			if(!result.valid)
			{
				continue;
			}

			double score = personality.score(result, context);

			if(best == null || score > best.score)
			{
				best = new Candidate(functions[i], 0, result, score);
			}

			if(personality.accepts(result, context))
			{
				best = new Candidate(functions[i], 0, result, score);
				break;
			}
		}

		if(best == null || isUnacceptable(best, context))
		{
			return false;
		}

		System.out.println("[pollinations] " + getName() + " falls back to y = " + best.function);

		graphwar.getGameData().sendFunction(best.function);

		return true;
	}

	private BotPersonality.Context buildContext(Soldier shooter)
	{
		BotPersonality.Context context = new BotPersonality.Context();

		context.gameMode = graphwar.getGameData().getGameMode();

		Player[] players = graphwar.getGameData().getPlayers().toArray(new Player[0]);

		for(int i = 0; i < players.length; i++)
		{
			for(int j = 0; j < players[i].getNumSoldiers(); j++)
			{
				Soldier soldier = players[i].getSoldiers()[j];

				if(!soldier.isAlive() || soldier == shooter)
				{
					continue;
				}

				if(players[i].getTeam() == this.team)
				{
					context.friendsAlive++;
				}
				else
				{
					context.enemiesAlive++;
				}
			}
		}

		context.berserk = (context.friendsAlive == 0);

		return context;
	}

	private void announceBerserkOnce(BotPersonality.Context context)
	{
		if(announcedBerserk || !context.berserk || personality != BotPersonality.PEACE)
		{
			return;
		}

		announcedBerserk = true;

		say("You killed them all. I am done being merciful.");
	}

	private void say(String message)
	{
		if(message == null)
		{
			return;
		}

		try
		{
			graphwar.getGameData().sendChatMessage(this, message);
		}
		catch(Exception e)
		{
			// Flavour is never worth an exception.
		}
	}

	private boolean hasTimeLeft()
	{
		return graphwar.getGameData().getRemainingTime() > TIME_RESERVED_FOR_FALLBACK;
	}

	static String buildSystemPrompt(int gameMode, BotPersonality personality, BotPersonality.Context context)
	{
		return buildSystemPrompt(gameMode, personality, context, false);
	}

	static String buildSystemPrompt(int gameMode, BotPersonality personality, BotPersonality.Context context, boolean compact)
	{
		if(compact)
		{
			return buildCompactSystemPrompt(gameMode, personality, context);
		}

		StringBuilder sb = new StringBuilder();

		sb.append("You are playing Graphwar, an artillery game where shots travel along the graph of a mathematical function. ");
		sb.append("You control one soldier and choose the function it fires.\n\n");

		sb.append("The battlefield is a cartesian plane. x runs from -25 (left) to 25 (right), y from -14.6 (bottom) to 14.6 (top). ");
		sb.append("Your soldiers are always on the left, at negative x, and the enemies are always to the right of you.\n\n");

		sb.append("ALLOWED SYNTAX. Write only the right hand side of the equation, with no \"y =\" prefix.\n");
		sb.append("  variables: x, y, y'\n");
		sb.append("  operators: + - * / ^\n");
		sb.append("  functions: sqrt() log() ln() abs() sin() cos() tan() exp()\n");
		sb.append("  constants: e, pi, and plain numbers like 2 or 0.35\n");
		sb.append("Nothing else exists. There is no floor, min, max, atan, sinh, sign or piecewise notation, ");
		sb.append("no variables other than the ones listed, and no multiplication by juxtaposition: write 2*x, never 2x.\n\n");

		switch(gameMode)
		{
			case Constants.NORMAL_FUNC:
				sb.append("MODE: plain function. Your shot follows y = f(x), but the whole curve is shifted vertically so that it passes through your soldier. ");
				sb.append("Any constant you add is therefore irrelevant: only the SHAPE matters. ");
				sb.append("To hit an enemy at (ex, ey) from your position (sx, sy) you need f(ex) - f(sx) to equal ey - sy.\n");
				break;
			case Constants.FST_ODE:
				sb.append("MODE: first order differential equation. You write f in y' = f(x, y), and the shot is the solution curve starting at your soldier's position. ");
				sb.append("No constant is added, the starting point is the initial condition. y' is the slope of the shot at each point.\n");
				break;
			case Constants.SND_ODE:
				sb.append("MODE: second order differential equation. You write f in y'' = f(x, y, y'), and the shot is the solution starting at your soldier, ");
				sb.append("fired at an initial angle you also choose. y'' is the curvature, so a negative constant arcs the shot downwards like gravity.\n");
				break;
		}

		sb.append("\nPITFALLS. Steep functions leave the screen almost immediately: x^2 already reaches 100 at x = 10, so scale it down, ");
		sb.append("for example (x^2)/50. sqrt() and log() of a negative number make the shot explode on the spot, and your soldier stands at negative x, ");
		sb.append("so use sqrt(abs(x)) instead of sqrt(x). A shot that is too long also explodes, so avoid high frequency oscillations like sin(50*x).\n\n");

		sb.append("YOUR CHARACTER. ").append(personality.promptFlavor(context)).append("\n\n");

		sb.append("Answer with a single JSON object and nothing else:\n");

		if(gameMode == Constants.SND_ODE)
		{
			sb.append("{\"reasoning\": \"one short sentence\", \"function\": \"...\", \"angle\": -20}\n");
			sb.append("where angle is the firing angle in degrees, between -90 and 90, positive being upwards.\n");
		}
		else
		{
			sb.append("{\"reasoning\": \"one short sentence\", \"function\": \"...\"}\n");
		}

		return sb.toString();
	}

	/** The terse rules that fit inside the free tier's budget. */
	private static String buildCompactSystemPrompt(int gameMode, BotPersonality personality, BotPersonality.Context context)
	{
		StringBuilder sb = new StringBuilder();

		sb.append("Graphwar: your shot follows the graph of a function. Plane is x -25..25, y -14.6..14.6. ");
		sb.append("You are on the left at negative x, enemies on the right.\n");
		sb.append("Syntax: only x, y, y', the operators + - * / ^, and sqrt log ln abs sin cos tan exp, ");
		sb.append("plus numbers, e and pi. Write 2*x, never 2x. No other functions exist.\n");

		switch(gameMode)
		{
			case Constants.FST_ODE:
				sb.append("You write f in y' = f(x,y); the shot solves it from your position.\n");
				break;
			case Constants.SND_ODE:
				sb.append("You write f in y'' = f(x,y,y'); the shot solves it from your position at your chosen angle.\n");
				break;
			default:
				sb.append("The curve is shifted to pass through you, so only its shape matters: ");
				sb.append("to hit (ex,ey) from (sx,sy) you need f(ex)-f(sx) = ey-sy.\n");
				break;
		}

		sb.append("Scale steep functions down, like (x^2)/50, and never sqrt or log of a negative.\n");
		sb.append(personality.promptFlavor(context)).append("\n");

		if(gameMode == Constants.SND_ODE)
		{
			sb.append("Answer with JSON only: {\"function\": \"...\", \"angle\": -20}");
		}
		else
		{
			sb.append("Answer with JSON only: {\"function\": \"...\"}");
		}

		return sb.toString();
	}

	private String buildStatePrompt(int gameMode, Soldier shooter, BotPersonality.Context context)
	{
		return buildStatePrompt(gameMode, shooter, context, false);
	}

	private String buildStatePrompt(int gameMode, Soldier shooter, BotPersonality.Context context, boolean compact)
	{
		boolean inverted = (this.team == Constants.TEAM2);

		double shooterX = ShotSimulator.toGameX(shooter.getX(), inverted);
		double shooterY = ShotSimulator.toGameY(shooter.getY());

		StringBuilder sb = new StringBuilder();

		sb.append("Your soldier is at (").append(round(shooterX)).append(", ").append(round(shooterY)).append(").\n\n");

		StringBuilder enemies = new StringBuilder();
		StringBuilder friends = new StringBuilder();
		int enemyCount = 0;
		int friendCount = 0;

		Player[] players = graphwar.getGameData().getPlayers().toArray(new Player[0]);

		for(int i = 0; i < players.length; i++)
		{
			for(int j = 0; j < players[i].getNumSoldiers(); j++)
			{
				Soldier soldier = players[i].getSoldiers()[j];

				if(!soldier.isAlive() || soldier == shooter)
				{
					continue;
				}

				double x = ShotSimulator.toGameX(soldier.getX(), inverted);
				double y = ShotSimulator.toGameY(soldier.getY());

				if(players[i].getTeam() != this.team)
				{
					enemyCount++;
					enemies.append("  enemy at (").append(round(x)).append(", ").append(round(y)).append(")");

					if(gameMode == Constants.NORMAL_FUNC)
					{
						enemies.append(" - to hit it you need f(").append(round(x)).append(") - f(")
								.append(round(shooterX)).append(") = ").append(round(y - shooterY));
					}

					enemies.append("\n");
				}
				else
				{
					friendCount++;
					friends.append("  friendly at (").append(round(x)).append(", ").append(round(y)).append(")\n");
				}
			}
		}

		sb.append("ENEMIES:\n");
		sb.append(enemyCount > 0 ? enemies.toString() : "  none visible\n");

		sb.append("\nYOUR TEAM:\n");
		sb.append(friendCount > 0 ? friends.toString() : "  none left alive besides you\n");

		if(!compact)
		{
			sb.append("\nTERRAIN. Each row is 1.46 units of y, each column 1 unit of x. ");
			sb.append("'#' is rock that blocks the shot, '@' is you, 'E' an enemy, 'o' a teammate, '.' is open air.\n");
			sb.append(buildMap(shooter, inverted));
		}

		sb.append("\nAnswer with JSON only.");

		return sb.toString();
	}

	/** A coarse picture of the battlefield, drawn in the shooter's own frame. */
	private String buildMap(Soldier shooter, boolean inverted)
	{
		char[][] map = new char[MAP_ROWS][MAP_COLUMNS];

		Obstacle obstacle = graphwar.getGameData().getObstacle();

		double xStep = ((double) Constants.PLANE_GAME_LENGTH) / (MAP_COLUMNS - 1);
		double yTop = ShotSimulator.toGameY(0);
		double yStep = (2 * yTop) / (MAP_ROWS - 1);

		for(int row = 0; row < MAP_ROWS; row++)
		{
			for(int col = 0; col < MAP_COLUMNS; col++)
			{
				double gameX = -Constants.PLANE_GAME_LENGTH / 2.0 + col * xStep;
				double gameY = yTop - row * yStep;

				boolean blocked = false;

				if(obstacle != null)
				{
					blocked = obstacle.collidePoint(ShotSimulator.toScreenX(gameX, inverted), ShotSimulator.toScreenY(gameY));
				}

				map[row][col] = blocked ? '#' : '.';
			}
		}

		Player[] players = graphwar.getGameData().getPlayers().toArray(new Player[0]);

		for(int i = 0; i < players.length; i++)
		{
			for(int j = 0; j < players[i].getNumSoldiers(); j++)
			{
				Soldier soldier = players[i].getSoldiers()[j];

				if(!soldier.isAlive())
				{
					continue;
				}

				double x = ShotSimulator.toGameX(soldier.getX(), inverted);
				double y = ShotSimulator.toGameY(soldier.getY());

				int col = (int) Math.round((x + Constants.PLANE_GAME_LENGTH / 2.0) / xStep);
				int row = (int) Math.round((yTop - y) / yStep);

				if(row < 0 || row >= MAP_ROWS || col < 0 || col >= MAP_COLUMNS)
				{
					continue;
				}

				if(soldier == shooter)
				{
					map[row][col] = '@';
				}
				else if(players[i].getTeam() != this.team)
				{
					map[row][col] = 'E';
				}
				else
				{
					map[row][col] = 'o';
				}
			}
		}

		StringBuilder sb = new StringBuilder();

		for(int row = 0; row < MAP_ROWS; row++)
		{
			sb.append(new String(map[row])).append('\n');
		}

		return sb.toString();
	}

	/**
	 * Pulls the function out of the model's answer. The happy path is the JSON
	 * we asked for, but models like to wrap things in prose or code fences, so
	 * the plainer shapes are accepted too.
	 */
	static String extractFunction(String reply)
	{
		if(reply == null)
		{
			return null;
		}

		String text = stripCodeFences(reply);

		Object parsed = PollinationsClient.Json.parse(text);
		String candidate = null;

		if(parsed instanceof java.util.Map)
		{
			Object value = ((java.util.Map<?, ?>) parsed).get("function");

			if(value instanceof String)
			{
				candidate = (String) value;
			}
		}

		if(candidate == null)
		{
			candidate = findQuotedField(text, "function");
		}

		if(candidate == null)
		{
			candidate = lastMeaningfulLine(text);
		}

		return cleanFunction(candidate);
	}

	/** The firing angle, in radians, for second order mode. */
	static double extractAngleFrom(String reply)
	{
		String text = stripCodeFences(reply);

		Object parsed = PollinationsClient.Json.parse(text);

		if(parsed instanceof java.util.Map)
		{
			Object value = ((java.util.Map<?, ?>) parsed).get("angle");

			if(value instanceof Double)
			{
				return clampAngle(((Double) value).doubleValue());
			}
			if(value instanceof String)
			{
				try
				{
					return clampAngle(Double.parseDouble(((String) value).trim()));
				}
				catch(NumberFormatException e)
				{
					return 0;
				}
			}
		}

		return 0;
	}

	/** Degrees from the model, radians for the game, clamped to what is aimable. */
	private static double clampAngle(double degrees)
	{
		if(degrees > 89)
		{
			degrees = 89;
		}
		if(degrees < -89)
		{
			degrees = -89;
		}

		return Math.toRadians(degrees);
	}

	private static String cleanFunction(String candidate)
	{
		if(candidate == null)
		{
			return null;
		}

		String function = candidate.trim();

		// Models like to answer with the whole equation.
		function = function.replaceAll("^\\s*y\\s*'{0,2}\\s*=\\s*", "");
		function = function.replaceAll("^\\s*f\\s*\\([^)]*\\)\\s*=\\s*", "");

		// And to end it like a sentence.
		while(function.length() > 0 && (function.endsWith(".") || function.endsWith(";") || function.endsWith(",")))
		{
			function = function.substring(0, function.length() - 1).trim();
		}

		function = function.replace('\n', ' ').replace('\r', ' ').trim();

		if(function.length() == 0)
		{
			return null;
		}

		return function;
	}

	private static String stripCodeFences(String text)
	{
		String stripped = text.trim();

		if(!stripped.startsWith("```"))
		{
			return stripped;
		}

		int firstBreak = stripped.indexOf('\n');

		if(firstBreak < 0)
		{
			return stripped;
		}

		stripped = stripped.substring(firstBreak + 1);

		int closing = stripped.lastIndexOf("```");

		if(closing >= 0)
		{
			stripped = stripped.substring(0, closing);
		}

		return stripped.trim();
	}

	/** Finds "field": "value" in text that is nearly, but not quite, JSON. */
	private static String findQuotedField(String text, String field)
	{
		String needle = "\"" + field + "\"";
		int start = text.indexOf(needle);

		if(start < 0)
		{
			return null;
		}

		int colon = text.indexOf(':', start + needle.length());

		if(colon < 0)
		{
			return null;
		}

		int open = text.indexOf('"', colon);

		if(open < 0)
		{
			return null;
		}

		StringBuilder sb = new StringBuilder();

		for(int i = open + 1; i < text.length(); i++)
		{
			char c = text.charAt(i);

			if(c == '\\' && i + 1 < text.length())
			{
				sb.append(text.charAt(++i));
				continue;
			}

			if(c == '"')
			{
				return sb.toString();
			}

			sb.append(c);
		}

		return null;
	}

	private static String lastMeaningfulLine(String text)
	{
		String[] lines = text.split("\n");

		for(int i = lines.length - 1; i >= 0; i--)
		{
			String line = lines[i].trim();

			if(line.length() > 0 && !line.startsWith("{") && !line.startsWith("}"))
			{
				return line;
			}
		}

		return null;
	}

	private static String round(double value)
	{
		return ShotSimulator.round(value);
	}
}
