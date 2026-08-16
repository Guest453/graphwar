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
import java.awt.GridLayout;
import java.io.IOException;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;

/**
 * Where the player types their Pollinations API key.
 *
 * The add computer player dialog is a fixed piece of artwork with its fields
 * baked into the image, so there is no room to grow a third labelled box in it.
 * This asks in a proper dialog instead, once, and remembers the answer in
 * ~/.graphwar/pollinations.properties.
 */
public class PollinationsKeyDialog
{
	private PollinationsKeyDialog()
	{
	}

	/**
	 * Asks for the key when one is needed, and saves whatever comes back.
	 * With force the question is asked even if it has been answered before,
	 * which is how a key gets replaced later.
	 */
	public static void promptIfNeeded(Component parent, boolean force)
	{
		if(!force && PollinationsClient.wasApiKeyRequested())
		{
			return;
		}

		JPasswordField keyField = new JPasswordField(24);

		JPanel message = new JPanel(new BorderLayout(0, 8));

		JPanel text = new JPanel(new GridLayout(0, 1, 0, 2));
		text.add(new JLabel("AI bots ask a language model, through Pollinations, what to shoot."));
		text.add(new JLabel("Paste an API key to use the good models, or leave it empty to play"));
		text.add(new JLabel("on the free anonymous tier, which is slower and sometimes refuses."));
		text.add(new JLabel(" "));
		text.add(new JLabel("Get a key at https://auth.pollinations.ai"));

		JPanel field = new JPanel(new BorderLayout(6, 0));
		field.add(new JLabel("API key:"), BorderLayout.WEST);
		field.add(keyField, BorderLayout.CENTER);

		message.add(text, BorderLayout.CENTER);
		message.add(field, BorderLayout.SOUTH);
		message.setPreferredSize(new Dimension(460, 150));

		int choice = JOptionPane.showConfirmDialog(parent, message, "Pollinations API key",
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

		if(choice != JOptionPane.OK_OPTION)
		{
			return;
		}

		String key = new String(keyField.getPassword());

		try
		{
			PollinationsClient.saveApiKey(key);
		}
		catch(IOException e)
		{
			JOptionPane.showMessageDialog(parent,
					"The key could not be saved to " + PollinationsClient.getConfigFile() + ":\n" + e.getMessage(),
					"Pollinations API key", JOptionPane.WARNING_MESSAGE);
		}
	}
}
