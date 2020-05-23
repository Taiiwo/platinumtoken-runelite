/*
 * Copyright (c) 2018 Abex
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.platinumtokens;

import com.google.common.primitives.Ints;
import java.util.Arrays;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Provider;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetConfig;
import net.runelite.api.widgets.WidgetID;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.Text;
import okhttp3.HttpUrl;

@Slf4j
@PluginDescriptor(
	name = "PlatinumTokens",
	description = "Adds a button that shows GE price analysis on items"
)
public class PlatinumTokensPlugin extends Plugin
{
	private static final int[] QUESTLIST_WIDGET_IDS = new int[]
		{
			WidgetInfo.QUESTLIST_FREE_CONTAINER.getId(),
			WidgetInfo.QUESTLIST_MEMBERS_CONTAINER.getId(),
			WidgetInfo.QUESTLIST_MINIQUEST_CONTAINER.getId(),
		};

	static final HttpUrl WIKI_BASE = HttpUrl.parse("https://platinumtokens.com");
	static final HttpUrl WIKI_API = WIKI_BASE.newBuilder().addPathSegments("api.php").build();
	static final String UTM_SORUCE_KEY = "utm_source";
	static final String UTM_SORUCE_VALUE = "runelite";

	private static final String MENUOP_GUIDE = "Guide"; //*
	private static final String MENUOP_QUICKGUIDE = "Quick Guide"; //*
	private static final String MENUOP_WIKI = "Prices";

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private Client client;

	@Inject
	private ChatboxPanelManager chatboxPanelManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Provider<PlatinumTokensSearchChatboxTextInput> platinumTokensSearchChatboxTextInputProvider;

	private Widget icon;

	private boolean platinumtokensSelected = false;

	@Override
	public void startUp()
	{
		spriteManager.addSpriteOverrides(PlatinumTokensSprite.values());
		clientThread.invokeLater(this::addWidgets);
	}

	@Override
	public void shutDown()
	{
		spriteManager.removeSpriteOverrides(PlatinumTokensSprite.values());
		clientThread.invokeLater(() ->
		{
			Widget minimapOrbs = client.getWidget(WidgetInfo.MINIMAP_ORBS);
			if (minimapOrbs == null)
			{
				return;
			}
			Widget[] children = minimapOrbs.getChildren();
			if (children == null || children.length < 1)
			{
				return;
			}
			children[0] = null;

			onDeselect();
			client.setSpellSelected(false);
		});
	}

	@Subscribe
	private void onWidgetLoaded(WidgetLoaded l)
	{
		if (l.getGroupId() == WidgetID.MINIMAP_GROUP_ID)
		{
			addWidgets();
		}
	}

	private void addWidgets()
	{
		Widget minimapOrbs = client.getWidget(WidgetInfo.MINIMAP_ORBS);
		if (minimapOrbs == null)
		{
			return;
		}

		icon = minimapOrbs.createChild(1, WidgetType.GRAPHIC);
		icon.setSpriteId(PlatinumTokensSprite.PT_ICON.getSpriteId());
		icon.setOriginalX(-10);
		icon.setOriginalY(0);
		icon.setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT);
		icon.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
		icon.setOriginalWidth(44);
		icon.setOriginalHeight(25);
		icon.setTargetVerb("Lookup");
		icon.setName("Prices");
		icon.setClickMask(WidgetConfig.USE_GROUND_ITEM | WidgetConfig.USE_ITEM | WidgetConfig.USE_NPC
			| WidgetConfig.USE_OBJECT | WidgetConfig.USE_WIDGET);
		icon.setNoClickThrough(true);
		icon.setOnTargetEnterListener((JavaScriptCallback) ev ->
		{
			platinumtokensSelected = true;
			// TODO: if GE open with an item selected,
			icon.setSpriteId(PlatinumTokensSprite.PT_SELECTED_ICON.getSpriteId());
			client.setAllWidgetsAreOpTargetable(true);
		});
		icon.setAction(5, "Search"); // Start at option 5 so the target op is ontop
		icon.setOnOpListener((JavaScriptCallback) ev ->
		{
			switch (ev.getOp())
			{
				case 6:
					openSearchInput();
					break;
			}
		});
		// This doesn't always run because we cancel the menuop
		icon.setOnTargetLeaveListener((JavaScriptCallback) ev -> onDeselect());
		icon.revalidate();
	}

	private void onDeselect()
	{
		client.setAllWidgetsAreOpTargetable(false);

		platinumtokensSelected = false;
		if (icon != null)
		{
			icon.setSpriteId(PlatinumTokensSprite.PT_ICON.getSpriteId());
		}
	}

	@Subscribe
	private void onMenuOptionClicked(MenuOptionClicked ev)
	{
		optarget:
		if (platinumtokensSelected)
		{
			onDeselect();
			client.setSpellSelected(false);
			ev.consume();

			String type;
			int id;
			String name;
			WorldPoint location;

			switch (ev.getMenuAction())
			{
				case RUNELITE:
					// This is a quest widget op
					return;
				case CANCEL:
					return;
				case ITEM_USE_ON_WIDGET:
				case SPELL_CAST_ON_GROUND_ITEM:
				{
					type = "item";
					id = itemManager.canonicalize(ev.getId());
					name = itemManager.getItemComposition(id).getName();
					location = null;
					break;
				}
				case SPELL_CAST_ON_NPC:
				{
					type = "npc";
					NPC npc = client.getCachedNPCs()[ev.getId()];
					NPCComposition nc = npc.getTransformedComposition();
					id = nc.getId();
					name = nc.getName();
					location = npc.getWorldLocation();
					break;
				}
				case SPELL_CAST_ON_GAME_OBJECT:
				{
					type = "object";
					ObjectComposition lc = client.getObjectDefinition(ev.getId());
					if (lc.getImpostorIds() != null)
					{
						lc = lc.getImpostor();
					}
					id = lc.getId();
					name = lc.getName();
					location = WorldPoint.fromScene(client, ev.getActionParam(), ev.getWidgetId(), client.getPlane());
					break;
				}
				case SPELL_CAST_ON_WIDGET:
					Widget w = getWidget(ev.getWidgetId(), ev.getActionParam());

					if (w.getType() == WidgetType.GRAPHIC && w.getItemId() != -1)
					{
						type = "item";
						id = itemManager.canonicalize(w.getItemId());
						name = itemManager.getItemComposition(id).getName();
						location = null;
						break;
					}
					// fallthrough
				default:
					log.info("Unknown menu option: {} {} {}", ev, ev.getMenuAction(), ev.getMenuAction() == MenuAction.CANCEL);
					return;
			}

			name = Text.removeTags(name);
			name = name.toLowerCase().replace("+", "-plus").replace(" ", "-");
			name = name.replace("(", "-").replace(")", "");
			HttpUrl WIKI_BASE = HttpUrl.parse("https://platinumtokens.com/item/" + name);
			HttpUrl.Builder urlBuilder = WIKI_BASE.newBuilder();
			HttpUrl url = urlBuilder.build();
			LinkBrowser.browse(url.toString());
			return;
		}

		if (ev.getMenuAction() == MenuAction.RUNELITE)
		{
			boolean quickguide = false;
			switch (ev.getMenuOption())
			{
				case MENUOP_QUICKGUIDE:
					quickguide = true;
					//fallthrough;
				default:
					ev.consume();
					String name = Text.removeTags(ev.getMenuTarget());
					name = Text.removeTags(name);
					name = name.toLowerCase().replace("+", "-plus").replace(" ", "-");
					name = name.replace("(", "-").replace(")", "");
					HttpUrl WIKI_BASE = HttpUrl.parse("https://platinumtokens.com/item/" + name);
					HttpUrl.Builder urlBuilder = WIKI_BASE.newBuilder();
					HttpUrl url = urlBuilder.build();
					LinkBrowser.browse(url.toString());
					break;
			}
		}
	}

	private void openSearchInput()
	{
		platinumTokensSearchChatboxTextInputProvider.get()
			.build();
	}

	private Widget getWidget(int wid, int index)
	{
		Widget w = client.getWidget(WidgetInfo.TO_GROUP(wid), WidgetInfo.TO_CHILD(wid));
		if (index != -1)
		{
			w = w.getChild(index);
		}
		return w;
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		int widgetIndex = event.getActionParam0();
		int widgetID = event.getActionParam1();
		MenuEntry[] menuEntries = client.getMenuEntries();

		if (platinumtokensSelected && event.getType() == MenuAction.SPELL_CAST_ON_WIDGET.getId())
		{
			Widget w = getWidget(widgetID, widgetIndex);
			if (!(w.getType() == WidgetType.GRAPHIC && w.getItemId() != -1))
			{
				// we don't support this widget
				// remove the last SPELL_CAST_ON_WIDGET; we can't blindly remove the top action because some other
				// plugin might have added something on this same event, and we probably shouldn't remove that instead
				MenuEntry[] oldEntries = menuEntries;
				menuEntries = Arrays.copyOf(menuEntries, menuEntries.length - 1);
				for (int ourEntry = oldEntries.length - 1;
					ourEntry >= 2 && oldEntries[oldEntries.length - 1].getType() != MenuAction.SPELL_CAST_ON_WIDGET.getId();
					ourEntry--)
				{
					menuEntries[ourEntry - 1] = oldEntries[ourEntry];
				}
				client.setMenuEntries(menuEntries);
			}
		}

		if (Ints.contains(QUESTLIST_WIDGET_IDS, widgetID)
			&& ((platinumtokensSelected && widgetIndex != -1) || "Read Journal:".equals(event.getOption())))
		{
			Widget w = getWidget(widgetID, widgetIndex);
			String target = w.getName();
			menuEntries = Arrays.copyOf(menuEntries, menuEntries.length + 2);

			MenuEntry menuEntry = menuEntries[menuEntries.length - 1] = new MenuEntry();
			menuEntry.setTarget(target);
			menuEntry.setOption(MENUOP_GUIDE);
			menuEntry.setParam0(widgetIndex);
			menuEntry.setParam1(widgetID);
			menuEntry.setType(MenuAction.RUNELITE.getId());

			menuEntry = menuEntries[menuEntries.length - 2] = new MenuEntry();
			menuEntry.setTarget(target);
			menuEntry.setOption(MENUOP_QUICKGUIDE);
			menuEntry.setParam0(widgetIndex);
			menuEntry.setParam1(widgetID);
			menuEntry.setType(MenuAction.RUNELITE.getId());

			client.setMenuEntries(menuEntries);
		}

		if (widgetID == WidgetInfo.ACHIEVEMENT_DIARY_CONTAINER.getId())
		{
			Widget w = getWidget(widgetID, widgetIndex);
			if (w.getActions() == null)
			{
				return;
			}

			String action = Stream.of(w.getActions())
				.filter(s -> s != null && !s.isEmpty())
				.findFirst().orElse(null);
			if (action == null)
			{
				return;
			}

			menuEntries = Arrays.copyOf(menuEntries, menuEntries.length + 1);

			MenuEntry menuEntry = menuEntries[menuEntries.length - 1] = new MenuEntry();
			menuEntry.setTarget(action.replace("Open ", "").replace("Journal", "Diary"));
			menuEntry.setOption(MENUOP_WIKI);
			menuEntry.setParam0(widgetIndex);
			menuEntry.setParam1(widgetID);
			menuEntry.setType(MenuAction.RUNELITE.getId());

			client.setMenuEntries(menuEntries);
		}

		if (WidgetInfo.TO_GROUP(widgetID) == WidgetInfo.SKILLS_CONTAINER.getGroupId())
		{
			Widget w = getWidget(widgetID, widgetIndex);
			if (w.getParentId() != WidgetInfo.SKILLS_CONTAINER.getId())
			{
				return;
			}

			String action = Stream.of(w.getActions())
				.filter(s -> s != null && !s.isEmpty())
				.findFirst().orElse(null);
			if (action == null)
			{
				return;
			}

			menuEntries = Arrays.copyOf(menuEntries, menuEntries.length + 1);

			MenuEntry menuEntry = menuEntries[menuEntries.length - 1] = new MenuEntry();
			menuEntry.setTarget(action.replace("View ", "").replace(" guide", ""));
			menuEntry.setOption(MENUOP_WIKI);
			menuEntry.setParam0(widgetIndex);
			menuEntry.setParam1(widgetID);
			menuEntry.setType(MenuAction.RUNELITE.getId());

			client.setMenuEntries(menuEntries);
		}
	}
}
