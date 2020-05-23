package com.platinumtokens;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class PlatinumTokensTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(PlatinumTokensPlugin.class);
		RuneLite.main(args);
	}
}