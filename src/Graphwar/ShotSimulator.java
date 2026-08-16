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

import java.util.List;

import GraphServer.Constants;

/**
 * Fires a candidate function inside the game's own physics without touching the
 * real match, and reports what it did.
 *
 * This is what lets a bot have an opinion about its own shot. A pacifist can
 * check that it is really going to miss, a trickshot artist can insist on a
 * flashy path, and any bot can tell the model "you passed four units too high"
 * and ask again.
 */
public class ShotSimulator
{
	/** What a candidate function turned out to do. */
	public static class Result
	{
		public boolean valid;
		public String error;

		public boolean hitEnemy;
		public boolean hitFriend;
		public boolean hitSelf;

		/** Closest the shot came to any living enemy, in game units. */
		public double nearestEnemyDistance = Double.MAX_VALUE;

		/** Signed vertical gap at the closest approach: positive means the shot passed above. */
		public double nearestEnemyVerticalGap = 0;

		/** Position of the enemy the shot came closest to. */
		public double nearestEnemyX = 0;
		public double nearestEnemyY = 0;

		/** Closest approach to a living teammate, in game units. */
		public double nearestFriendDistance = Double.MAX_VALUE;

		public double pathLength;
		public double maxHeight;
		public double minHeight;
		public double endX;
		public double endY;
		public int numSteps;

		/** Direction reversals in y: a rough measure of how wavy the shot was. */
		public int turningPoints;

		/** Points where the shot squeezed past terrain. */
		public int grazes;

		/** True when the shot stopped before crossing the field, so it ran into something. */
		public boolean stoppedEarly;

		public boolean hitNobody()
		{
			return !hitEnemy && !hitFriend && !hitSelf;
		}

		public String describe()
		{
			StringBuilder sb = new StringBuilder();

			if(!valid)
			{
				return "the function was rejected: " + error;
			}

			if(hitEnemy)
			{
				sb.append("it hit an enemy");
			}
			else if(hitFriend)
			{
				sb.append("it hit one of your own team");
			}
			else if(hitSelf)
			{
				sb.append("it hit your own soldier");
			}
			else
			{
				sb.append("it hit nobody");
			}

			if(nearestEnemyDistance < Double.MAX_VALUE)
			{
				sb.append(", closest enemy approach ").append(round(nearestEnemyDistance)).append(" units");

				if(!hitEnemy)
				{
					sb.append(" (the shot passed ");
					sb.append(round(Math.abs(nearestEnemyVerticalGap)));
					sb.append(nearestEnemyVerticalGap > 0 ? " above" : " below");
					sb.append(" the enemy at x = ").append(round(nearestEnemyX)).append(")");
				}
			}

			if(stoppedEarly)
			{
				sb.append(", and it stopped early at x = ").append(round(endX)).append(" so it ran into the terrain");
			}

			return sb.toString();
		}
	}

	/**
	 * Runs the function through the same integrator the real shot uses.
	 * Never throws: a broken function comes back as an invalid result.
	 */
	public static Result simulate(Graphwar graphwar, Player me, int gameMode, String functionString, double angle)
	{
		Result result = new Result();

		Function function;

		try
		{
			function = new Function(functionString);
		}
		catch(MalformedFunction e)
		{
			result.valid = false;
			result.error = "the game's parser could not read it";
			return result;
		}
		catch(Exception e)
		{
			result.valid = false;
			result.error = e.getClass().getSimpleName();
			return result;
		}

		GameData gameData = graphwar.getGameData();
		List<Player> playerList = gameData.getPlayers();
		Player[] players = playerList.toArray(new Player[0]);

		boolean inverted = (me.getTeam() == Constants.TEAM2);
		int currentTurn = gameData.getCurrentTurnIndex();
		Obstacle obstacle = gameData.getObstacle();

		try
		{
			switch(gameMode)
			{
				case Constants.NORMAL_FUNC:
					function.processFunctionRange(obstacle, players, players.length, currentTurn, inverted);
					break;
				case Constants.FST_ODE:
					function.processRK4Range(obstacle, players, players.length, currentTurn, inverted);
					break;
				case Constants.SND_ODE:
					function.processRK42Range(obstacle, players, players.length, currentTurn, angle, inverted);
					break;
				default:
					function.processFunctionRange(obstacle, players, players.length, currentTurn, inverted);
					break;
			}
		}
		catch(Exception e)
		{
			result.valid = false;
			result.error = "the shot could not be computed (" + e.getClass().getSimpleName() + ")";
			return result;
		}

		result.valid = true;

		readHits(result, function, players, me);
		readPath(result, function, obstacle, inverted);
		readDistances(result, function, players, me, inverted);

		return result;
	}

	private static void readHits(Result result, Function function, Player[] players, Player me)
	{
		Soldier myShooter = me.getCurrentTurnSoldier();

		for(int k = 0; k < function.getNumPlayersHit(); k++)
		{
			int playerIndex = function.getPlayerHit(k);
			int soldierIndex = function.getSoldierHit(k);

			if(playerIndex < 0 || playerIndex >= players.length)
			{
				continue;
			}

			Player hitPlayer = players[playerIndex];

			if(soldierIndex < 0 || soldierIndex >= hitPlayer.getNumSoldiers())
			{
				continue;
			}

			Soldier hitSoldier = hitPlayer.getSoldiers()[soldierIndex];

			if(hitSoldier == myShooter)
			{
				result.hitSelf = true;
			}
			else if(hitPlayer.getTeam() == me.getTeam())
			{
				result.hitFriend = true;
			}
			else
			{
				result.hitEnemy = true;
			}
		}
	}

	private static void readPath(Result result, Function function, Obstacle obstacle, boolean inverted)
	{
		int steps = function.getNumSteps();

		result.numSteps = steps;

		if(steps <= 0)
		{
			return;
		}

		double previousX = function.getX(0);
		double previousY = function.getY(0);

		result.maxHeight = previousY;
		result.minHeight = previousY;

		int previousDirection = 0;

		// Probing terrain for every step would cost more than the shot itself.
		int probeStride = Math.max(1, steps / 200);

		for(int i = 1; i < steps; i++)
		{
			double x = function.getX(i);
			double y = function.getY(i);

			result.pathLength += Math.sqrt((x - previousX) * (x - previousX) + (y - previousY) * (y - previousY));

			if(y > result.maxHeight)
			{
				result.maxHeight = y;
			}
			if(y < result.minHeight)
			{
				result.minHeight = y;
			}

			int direction = 0;

			if(y > previousY)
			{
				direction = 1;
			}
			else if(y < previousY)
			{
				direction = -1;
			}

			if(direction != 0 && previousDirection != 0 && direction != previousDirection)
			{
				result.turningPoints++;
			}

			if(direction != 0)
			{
				previousDirection = direction;
			}

			if(obstacle != null && i % probeStride == 0 && grazesTerrain(obstacle, x, y, inverted))
			{
				result.grazes++;
			}

			previousX = x;
			previousY = y;
		}

		result.endX = function.getX(steps - 1);
		result.endY = function.getY(steps - 1);

		// A shot that made it across leaves through the side of the plane.
		double edge = Constants.PLANE_GAME_LENGTH / 2.0;
		result.stoppedEarly = Math.abs(result.endX) < edge - 0.5;
	}

	/** True when the point is clear but there is rock within about a unit of it. */
	private static boolean grazesTerrain(Obstacle obstacle, double gameX, double gameY, boolean inverted)
	{
		if(obstacle.collidePoint(toScreenX(gameX, inverted), toScreenY(gameY)))
		{
			return false;
		}

		double probe = 1.0;

		return obstacle.collidePoint(toScreenX(gameX, inverted), toScreenY(gameY - probe))
				|| obstacle.collidePoint(toScreenX(gameX, inverted), toScreenY(gameY + probe));
	}

	private static void readDistances(Result result, Function function, Player[] players, Player me, boolean inverted)
	{
		Soldier myShooter = me.getCurrentTurnSoldier();
		int steps = function.getNumSteps();

		if(steps <= 0)
		{
			return;
		}

		// Sampling every step is wasted work at 20000 steps per shot.
		int stride = Math.max(1, steps / 400);

		for(int i = 0; i < players.length; i++)
		{
			for(int j = 0; j < players[i].getNumSoldiers(); j++)
			{
				Soldier soldier = players[i].getSoldiers()[j];

				if(!soldier.isAlive() || soldier == myShooter)
				{
					continue;
				}

				double soldierX = toGameX(soldier.getX(), inverted);
				double soldierY = toGameY(soldier.getY());

				double best = Double.MAX_VALUE;
				double bestGap = 0;

				for(int k = 0; k < steps; k += stride)
				{
					double dx = function.getX(k) - soldierX;
					double dy = function.getY(k) - soldierY;
					double distance = Math.sqrt(dx * dx + dy * dy);

					if(distance < best)
					{
						best = distance;
						bestGap = dy;
					}
				}

				if(players[i].getTeam() != me.getTeam())
				{
					if(best < result.nearestEnemyDistance)
					{
						result.nearestEnemyDistance = best;
						result.nearestEnemyVerticalGap = bestGap;
						result.nearestEnemyX = soldierX;
						result.nearestEnemyY = soldierY;
					}
				}
				else
				{
					if(best < result.nearestFriendDistance)
					{
						result.nearestFriendDistance = best;
					}
				}
			}
		}
	}


	///// Coordinate helpers, shared with the bots /////

	public static double toGameX(int screenX, boolean inverted)
	{
		double x = inverted ? Constants.PLANE_LENGTH - screenX : screenX;

		return Constants.PLANE_GAME_LENGTH * (x - Constants.PLANE_LENGTH / 2.0) / Constants.PLANE_LENGTH;
	}

	public static double toGameY(int screenY)
	{
		return Constants.PLANE_GAME_LENGTH * (-screenY + Constants.PLANE_HEIGHT / 2.0) / Constants.PLANE_LENGTH;
	}

	public static int toScreenX(double gameX, boolean inverted)
	{
		double x = Constants.PLANE_LENGTH * gameX / Constants.PLANE_GAME_LENGTH + Constants.PLANE_LENGTH / 2.0;

		if(inverted)
		{
			x = Constants.PLANE_LENGTH - x;
		}

		return (int) Math.round(x);
	}

	public static int toScreenY(double gameY)
	{
		return (int) Math.round(-Constants.PLANE_LENGTH * gameY / Constants.PLANE_GAME_LENGTH + Constants.PLANE_HEIGHT / 2.0);
	}

	static String round(double value)
	{
		return String.valueOf(Math.round(value * 10.0) / 10.0);
	}
}
