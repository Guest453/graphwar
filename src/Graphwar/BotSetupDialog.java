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

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.IOException;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;

/**
 * The menu for setting up an AI bot: which character it plays, which model
 * answers for it, and the API key that pays for the request.
 *
 * The add computer player window is fixed artwork with its two fields baked
 * into the image, so there is nowhere to put a dropdown. This opens on top of
 * it instead, which also means the key only has to be typed once ever.
 */
public class BotSetupDialog
{
	/** What the player chose. */
	public static class Setup
	{
		public BotPersonality personality;
		public String model;
		public int level;
	}

	/** The characters, in the order they are offered. */
	private static final BotPersonality[] CHOICES = {
			BotPersonality.SOLDIER,
			BotPersonality.SNIPER,
			BotPersonality.PEACE,
			BotPersonality.TRICKSHOT,
			BotPersonality.CHAOS,
			BotPersonality.BERSERKER };

	/** Used when the live model list cannot be fetched. */
	private static final String[] FALLBACK_MODELS = {
			"gemma", "gemma-4-31b", "Catniti/gemma-4-31b", "openai", "openai-large",
			"claude-large", "gemini", "grok-large", "deepseek", "llama", "mistral" };

	private static final String[] DESCRIPTIONS = {
			"plays it straight: hit an enemy, spare your own team",
			"slower, fussier, and harder to survive",
			"misses on purpose, until its last teammate dies",
			"only takes the shot if it arrives spectacularly",
			"fires the strangest function it can invent",
			"wants the kill enough to risk friendly fire" };

	private BotSetupDialog()
	{
	}

	/**
	 * Asks how the bot should play. Returns null when the player cancels, in
	 * which case no bot should be added.
	 */
	public static Setup show(Component parent, BotPersonality preselected, String model, int level)
	{
		final JComboBox personalityBox = new JComboBox();

		for(int i = 0; i < CHOICES.length; i++)
		{
			personalityBox.addItem(CHOICES[i].getDisplayName() + " - " + DESCRIPTIONS[i]);
		}

		for(int i = 0; i < CHOICES.length; i++)
		{
			if(CHOICES[i] == preselected)
			{
				personalityBox.setSelectedIndex(i);
				break;
			}
		}

		// Editable, so an unlisted model can still be typed in.
		JComboBox modelBox = new JComboBox();
		modelBox.setEditable(true);

		java.util.List<String> available = PollinationsClient.listModels();

		if(available.isEmpty())
		{
			available = java.util.Arrays.asList(FALLBACK_MODELS);
		}

		for(int i = 0; i < available.size(); i++)
		{
			modelBox.addItem(available.get(i));
		}

		String wanted = model != null ? model : PollinationsClient.DEFAULT_MODEL;
		modelBox.setSelectedItem(wanted);

		for(int i = 0; i < available.size(); i++)
		{
			if(PollinationsClient.modelFromLabel(available.get(i)).equals(wanted))
			{
				modelBox.setSelectedIndex(i);
				break;
			}
		}
		JTextField levelField = new JTextField(String.valueOf(level), 6);
		JPasswordField keyField = new JPasswordField(16);

		boolean hasKey = PollinationsClient.isApiKeyConfigured();

		JPanel form = new JPanel(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(3, 4, 3, 4);
		c.anchor = GridBagConstraints.WEST;
		c.fill = GridBagConstraints.HORIZONTAL;

		int row = 0;

		c.gridx = 0;
		c.gridy = row;
		c.weightx = 0;
		form.add(new JLabel("Personality:"), c);
		c.gridx = 1;
		c.weightx = 1;
		form.add(personalityBox, c);

		row++;
		c.gridx = 0;
		c.gridy = row;
		c.weightx = 0;
		form.add(new JLabel("Model:"), c);
		c.gridx = 1;
		c.weightx = 1;
		form.add(modelBox, c);

		row++;
		c.gridx = 0;
		c.gridy = row;
		c.weightx = 0;
		form.add(new JLabel("Fallback level:"), c);
		c.gridx = 1;
		c.weightx = 1;
		form.add(levelField, c);

		row++;
		c.gridx = 0;
		c.gridy = row;
		c.weightx = 0;
		form.add(new JLabel("API key:"), c);
		c.gridx = 1;
		c.weightx = 1;
		form.add(keyField, c);

		JPanel panel = new JPanel(new BorderLayout(0, 8));

		JPanel notes = new JPanel(new GridBagLayout());
		GridBagConstraints n = new GridBagConstraints();
		n.gridx = 0;
		n.anchor = GridBagConstraints.WEST;
		n.gridy = 0;
		notes.add(new JLabel("The bot asks a language model what to shoot."), n);
		n.gridy = 1;
		notes.add(new JLabel("The fallback level is the classic AI that covers for it."), n);
		n.gridy = 2;
		notes.add(new JLabel(hasKey ? "A key is already saved. Leave it empty to keep it."
				: "A key is needed: the free tier refuses prompts this size."), n);

		panel.add(notes, BorderLayout.NORTH);
		panel.add(form, BorderLayout.CENTER);
		panel.setPreferredSize(new Dimension(470, 190));

		int choice = JOptionPane.showConfirmDialog(parent, panel, "AI bot",
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

		if(choice != JOptionPane.OK_OPTION)
		{
			return null;
		}

		String key = new String(keyField.getPassword());

		if(key.trim().length() > 0)
		{
			try
			{
				PollinationsClient.saveApiKey(key);
			}
			catch(IOException e)
			{
				JOptionPane.showMessageDialog(parent,
						"The key could not be saved to " + PollinationsClient.getConfigFile() + ":\n" + e.getMessage(),
						"AI bot", JOptionPane.WARNING_MESSAGE);
			}
		}

		Setup setup = new Setup();

		int selected = personalityBox.getSelectedIndex();
		setup.personality = CHOICES[selected >= 0 ? selected : 0];

		Object chosenModel = modelBox.getSelectedItem();
		setup.model = chosenModel != null ? PollinationsClient.modelFromLabel(chosenModel.toString()) : "";

		if(setup.model.length() == 0)
		{
			setup.model = PollinationsClient.DEFAULT_MODEL;
		}

		setup.level = level;

		try
		{
			setup.level = Integer.parseInt(levelField.getText().trim());
		}
		catch(NumberFormatException e)
		{
			// keep what we came in with
		}

		return setup;
	}
}
