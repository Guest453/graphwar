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
 * Checks the AI bot end to end without starting the game:
 *
 *   java -cp graphwar.jar Graphwar.PollinationsTest [model] [rounds]
 *
 * It sends a sample battlefield to the model, then runs the answer through the
 * same extraction and the same parser the bot uses before firing, so a green
 * run means a bot with this configuration would actually be able to shoot.
 */
public class PollinationsTest
{
	private static final String SAMPLE_STATE =
			"Your soldier is at (-18.0, 2.0).\n" +
			"\n" +
			"ENEMIES (hit one of these):\n" +
			"  enemy at (14.0, -3.0) - to hit it you need f(14.0) - f(-18.0) = -5.0\n" +
			"\n" +
			"YOUR TEAM (never hit these):\n" +
			"  friendly at (-8.0, 2.0)\n" +
			"\n" +
			"TERRAIN. '#' is rock that blocks the shot, '@' is you, 'E' an enemy, 'o' a teammate, '.' is open air.\n" +
			"...................................................\n" +
			"...................................................\n" +
			"...................................................\n" +
			"...................................................\n" +
			"...................................................\n" +
			"...................................................\n" +
			".......@.........o.................................\n" +
			"...................................................\n" +
			"..........................#####....................\n" +
			".........................#######...................\n" +
			"..........................#####........E...........\n" +
			"...................................................\n" +
			"...................................................\n" +
			"\n" +
			"Choose the function that hits an enemy. Answer with JSON only.";

	private static final String COMPACT_STATE =
			"You are at (-18.0, 2.0). Enemy at (14.0, -3.0): you need f(14.0)-f(-18.0) = -5.0. "
			+ "Friendly at (-8.0, 2.0). Rock between x = 1 and x = 7 around y = -3.";

	public static void main(String[] args)
	{
		if(args.length > 0 && args[0].equalsIgnoreCase("offline"))
		{
			runOffline();
			return;
		}

		String model = args.length > 0 ? args[0] : null;
		int rounds = 1;

		if(args.length > 1)
		{
			try
			{
				rounds = Integer.parseInt(args[1]);
			}
			catch(NumberFormatException e)
			{
				System.err.println("rounds must be a number, using 1");
			}
		}

		PollinationsClient client = new PollinationsClient(model);

		System.out.println("model:   " + client.getModel());
		System.out.println("api key: " + (client.hasApiKey() ? "yes" : "no (anonymous tier)"));
		System.out.println();

		BotPersonality personality = BotPersonality.forName(args.length > 2 ? args[2] : "soldier");

		if(personality == null)
		{
			personality = BotPersonality.SOLDIER;
		}

		BotPersonality.Context context = new BotPersonality.Context();
		context.gameMode = Constants.NORMAL_FUNC;
		context.friendsAlive = 1;
		context.enemiesAlive = 1;

		System.out.println("bot:     " + personality.getId());

		boolean compact = !client.hasApiKey();

		if(compact)
		{
			System.out.println("prompt:  compact (no key, so the free tier's size limit applies)");
		}

		String systemPrompt = PollinationsPlayer.buildSystemPrompt(Constants.NORMAL_FUNC, personality, context, compact);
		String state = compact ? COMPACT_STATE : SAMPLE_STATE;

		int usable = 0;

		for(int i = 0; i < rounds; i++)
		{
			long started = System.currentTimeMillis();
			String reply;

			try
			{
				reply = client.chat(systemPrompt, state, personality.temperature(), 30000);
			}
			catch(Exception e)
			{
				System.out.println((i + 1) + ". request failed: " + e.getMessage());
				continue;
			}

			long elapsed = System.currentTimeMillis() - started;
			String function = PollinationsPlayer.extractFunction(reply);

			if(function == null)
			{
				System.out.println((i + 1) + ". no function found in reply after " + elapsed + " ms");
				System.out.println("   raw: " + oneLine(reply));
				continue;
			}

			String verdict;

			try
			{
				@SuppressWarnings("unused")
				Function parsed = new Function(function);
				verdict = "accepted by the game parser";
				usable++;
			}
			catch(MalformedFunction e)
			{
				verdict = "REJECTED by the game parser";
			}

			System.out.println((i + 1) + ". " + elapsed + " ms  y = " + function + "   [" + verdict + "]");
		}

		System.out.println();
		System.out.println(usable + "/" + rounds + " replies were usable shots");

		if(usable == 0)
		{
			System.exit(1);
		}
	}

	/**
	 * Checks the parts that do not need the network: the shapes models actually
	 * answer in, and whether what we pull out of them survives the game's parser.
	 */
	private static void runOffline()
	{
		String[][] cases = {
				{ "{\"reasoning\": \"arc over the rock\", \"function\": \"sin(x/3)*5\"}", "sin(x/3)*5" },
				{ "```json\n{\"function\": \"(x^2)/50\"}\n```", "(x^2)/50" },
				{ "Here is my shot:\n{\"function\": \"ln(abs(x))*2\"}", "ln(abs(x))*2" },
				{ "{\"function\": \"y = cos(x/4)*7\"}", "cos(x/4)*7" },
				{ "y = sqrt(abs(x))*3.", "sqrt(abs(x))*3" },
				{ "{\"function\": \"exp(x/8)\", \"angle\": -20}", "exp(x/8)" },
		};

		int passed = 0;

		for(int i = 0; i < cases.length; i++)
		{
			String extracted = PollinationsPlayer.extractFunction(cases[i][0]);
			boolean matches = cases[i][1].equals(extracted);
			boolean parses = false;

			if(extracted != null)
			{
				try
				{
					@SuppressWarnings("unused")
					Function parsed = new Function(extracted);
					parses = true;
				}
				catch(MalformedFunction e)
				{
					parses = false;
				}
			}

			if(matches && parses)
			{
				passed++;
			}

			System.out.println((matches && parses ? "ok   " : "FAIL ") + "extracted \"" + extracted
					+ "\", expected \"" + cases[i][1] + "\", parses: " + parses);
		}

		double angle = PollinationsPlayer.extractAngleFrom("{\"function\": \"x\", \"angle\": -20}");
		double expected = Math.toRadians(-20);
		boolean angleOk = Math.abs(angle - expected) < 0.0001;

		System.out.println((angleOk ? "ok   " : "FAIL ") + "angle -20 degrees read as " + angle + " radians");

		System.out.println();
		System.out.println(passed + "/" + cases.length + " extraction cases passed, angle "
				+ (angleOk ? "passed" : "failed"));

		if(passed != cases.length || !angleOk)
		{
			System.exit(1);
		}
	}

	private static String oneLine(String text)
	{
		if(text == null)
		{
			return "";
		}

		String flat = text.replace('\n', ' ').replace('\r', ' ').trim();

		return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
	}
}
