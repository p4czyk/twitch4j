package me.philippheuer.twitch4j.chat;

import com.jcabi.log.Logger;
import lombok.Getter;
import lombok.Setter;
import me.philippheuer.twitch4j.TwitchClient;
import me.philippheuer.twitch4j.chat.commands.CommandPermission;
import me.philippheuer.twitch4j.enums.SubPlan;
import me.philippheuer.twitch4j.events.Event;
import me.philippheuer.twitch4j.events.event.*;
import me.philippheuer.twitch4j.model.Channel;
import me.philippheuer.twitch4j.model.Cheer;
import me.philippheuer.twitch4j.model.Subscription;
import me.philippheuer.twitch4j.model.User;
import net.engio.mbassy.listener.Handler;
import net.jodah.expiringmap.ExpirationPolicy;
import net.jodah.expiringmap.ExpiringMap;
import org.kitteh.irc.client.library.element.MessageTag;
import org.kitteh.irc.client.library.element.ServerMessage;
import org.kitteh.irc.client.library.event.abstractbase.ClientReceiveServerMessageEventBase;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Getter
@Setter
public class IrcEventHandler {

	/**
	 * Holds recent Subscriptions
	 */
	static private Map<String, Subscription> subscriptionHistory = ExpiringMap.builder()
			.expiration(5, TimeUnit.MINUTES)
			.expirationPolicy(ExpirationPolicy.CREATED)
			.build();
	/**
	 * Holds the API Instance
	 */
	private TwitchClient twitchClient;

	/**
	 * Class Constructor
	 *
	 * @param twitchClient Twitch Client.
	 */
	public IrcEventHandler(TwitchClient twitchClient) {
		setTwitchClient(twitchClient);
	}

	/**
	 * Event: onClientReceiveCommand
	 * Gets executed on NOTICE, USERNOTICE and simelar events.
	 *
	 * @param event ClientReceiveServerMessageEventBase provided by the irc library.
	 */
	@Handler(priority = Integer.MAX_VALUE)
	protected void onClientReceiveCommand(ClientReceiveServerMessageEventBase event) {
		/**
		 * About once every five minutes, you will receive a PING :tmi.twitch.tv from the server, in order to
		 * ensure that your connection to the server is not prematurely terminated, you should reply with PONG :tmi.twitch.tv.
		 */
		if (event.getOriginalMessage().contains("PING :tmi.twitch.tv")) {
			event.getClient().sendRawLine("PONG :tmi.twitch.tv");

			Logger.debug(this, "Responded to PING from Twitch IRC.");
		}

		/**
		 * Check for the Authentication failed notice, which will be send if the irc oauth token was invalid!
		 */
		if(event.getOriginalMessage().equals(":tmi.twitch.tv NOTICE * :Login authentication failed")) {
			Logger.error(this, "Login failed!");
			return;
		}

		// Handle Messages
		if (event.getCommand().equals("WHISPER") || event.getCommand().equals("USERNOTICE") || event.getCommand().equals("PRIVMSG") || event.getCommand().equals("NOTICE") || event.getCommand().equals("CLEARCHAT") || event.getCommand().equals("HOSTTARGET ") || event.getCommand().equals("ROOMSTATE")) {
			// Get Channel on IRC
			String channelName = event.getParameters().get(0).replace("#", "");
			Optional<Long> channelId = getTwitchClient().getUserEndpoint().getUserIdByUserName(channelName);

			if(!channelId.isPresent()) {
				Logger.error(this, "Got IrcEvent for invalid channel [%s][%s]", channelName, event.getOriginalMessage());
				return;
			}

			Channel channel = getTwitchClient().getChannelEndpoint(channelId.get()).getChannel();

			// Build Map from Tags
			Map<String, String> tagMap = getTagMap(event.getServerMessage());

			// Bans/Timeouts
			if (tagMap.containsKey("ban-reason")) {
				String banReason = tagMap.get("ban-reason");
				Long targetUserId = Long.parseLong(tagMap.get("target-user-id"));
				Optional<User> targetUser = getTwitchClient().getUserEndpoint().getUser(targetUserId);

				if (targetUser.isPresent()) {
					if (tagMap.containsKey("ban-duration")) {
						// Timeout
						Integer banDuration = Integer.parseInt(tagMap.get("ban-duration"));

						Event dispatchEvent = new UserTimeout(channel, targetUser.get(), banDuration, banReason);
						getTwitchClient().getDispatcher().dispatch(dispatchEvent);
					} else {
						// Permanent Ban
						Event dispatchEvent = new UserBan(channel, targetUser.get(), banReason);
						getTwitchClient().getDispatcher().dispatch(dispatchEvent);
					}
				}
			}

			// Private Messages
			if (event.getCommand().equals("WHISPER")) {
				String rawMessage = event.getServerMessage().getMessage();

				Pattern regExpr = Pattern.compile("^.+:.+?!.+?\\@.+?\\.tmi\\.twitch\\.tv WHISPER (?<recipient>[a-zA-Z0-9_]{4,25}) \\:(?<message>.+)$");
				Matcher matcher = regExpr.matcher(rawMessage);
				String chatMessage = "";

				if (!matcher.matches()) {
					return;
				}

				chatMessage = matcher.group("message");
				String recipientName = matcher.group("recipient");

				Long userId = Long.parseLong(tagMap.get("user-id"));
				Set<CommandPermission> userPermissions = new HashSet<CommandPermission>();
				userPermissions.add(CommandPermission.EVERYONE);

				// Get User by ID
				Optional<User> user = getTwitchClient().getUserEndpoint().getUser(userId);
				Optional<User> recipient = getTwitchClient().getUserEndpoint().getUserByUserName(recipientName);

				if (user.isPresent() && recipient.isPresent()) {
					// Dispatch Event
					PrivateMessageEvent privateMessageEvent = new PrivateMessageEvent(user.get(), recipient.get(), chatMessage, userPermissions);
					getTwitchClient().getDispatcher().dispatch(privateMessageEvent);
				}
			}
			// Events
			else if (event.getCommand().equals("PRIVMSG")) {
				// First Subscribers Subscriptions
				// Deprecated: To be removed as soon as the beta is over
				String rawMessage = event.getServerMessage().getMessage();
				if (event.getServerMessage().getMessage().startsWith(":twitchnotify")) {
					Pattern regExpr = null;
					Matcher matcher = null;

					// Subscription: Tier 1
					regExpr = Pattern.compile("^:twitchnotify!twitchnotify@twitchnotify\\.tmi\\.twitch\\.tv PRIVMSG #(?<channel>[a-zA-Z0-9_]{4,25}) :(?<userName>[a-zA-Z0-9_]{4,25}) just subscribed with a $4.99 sub");
					matcher = regExpr.matcher(rawMessage);
					if (matcher.matches()) {
						Long userId = getTwitchClient().getUserEndpoint().getUserIdByUserName(matcher.group("userName")).get();
						// was: matcher.group("channel")
						onSubscription(userId, channel, 1, false, "", "1000");
						return;
					}

					// Subscription: Tier 2
					regExpr = Pattern.compile("^:twitchnotify!twitchnotify@twitchnotify\\.tmi\\.twitch\\.tv PRIVMSG #(?<channel>[a-zA-Z0-9_]{4,25}) :(?<userName>[a-zA-Z0-9_]{4,25}) just subscribed with a $9.99 sub");
					matcher = regExpr.matcher(rawMessage);
					if (matcher.matches()) {
						Long userId = getTwitchClient().getUserEndpoint().getUserIdByUserName(matcher.group("userName")).get();
						// was: matcher.group("channel")
						onSubscription(userId, channel, 1, false, "", "2000");
						return;
					}

					// Subscription: Tier 3
					regExpr = Pattern.compile("^:twitchnotify!twitchnotify@twitchnotify\\.tmi\\.twitch\\.tv PRIVMSG #(?<channel>[a-zA-Z0-9_]{4,25}) :(?<userName>[a-zA-Z0-9_]{4,25}) just subscribed with a $24.99 sub");
					matcher = regExpr.matcher(rawMessage);
					if (matcher.matches()) {
						Long userId = getTwitchClient().getUserEndpoint().getUserIdByUserName(matcher.group("userName")).get();
						// was: matcher.group("channel")
						onSubscription(userId, channel, 1, false, "", "3000");
						return;
					}

					// Subscription: Normal
					regExpr = Pattern.compile("^:twitchnotify!twitchnotify@twitchnotify\\.tmi\\.twitch\\.tv PRIVMSG #(?<channel>[a-zA-Z0-9_]{4,25}) :(?<userName>[a-zA-Z0-9_]{4,25}) just subscribed!$");
					matcher = regExpr.matcher(rawMessage);
					if (matcher.matches()) {
						Long userId = getTwitchClient().getUserEndpoint().getUserIdByUserName(matcher.group("userName")).get();
						// was: matcher.group("channel")
						onSubscription(userId, channel, 1, false, "", "1000");
						return;
					}

					// Subscription: Twitch Prime
					regExpr = Pattern.compile("^:twitchnotify!twitchnotify@twitchnotify\\.tmi\\.twitch\\.tv PRIVMSG #(?<channel>[a-zA-Z0-9_]{4,25}) :(?<userName>[a-zA-Z0-9_]{4,25}) just subscribed with Twitch Prime!$");
					matcher = regExpr.matcher(rawMessage);
					if (matcher.matches()) {
						Long userId = getTwitchClient().getUserEndpoint().getUserIdByUserName(matcher.group("userName")).get();
						// was: matcher.group("channel")
						onSubscription(userId, channel, 1, true, "", "1000");
						return;
					}
				}

				// Cheers
				if (tagMap.containsKey("bits")) {
					onCheer(Long.parseLong(tagMap.get("user-id")), channel, tagMap.get("bits"), event.getParameters().get(1));
				}
			}
			// Resubscriptions
			else if (event.getCommand().equals("USERNOTICE")) {
				// Get SubMessage if user wrote one
				Optional<String> subMessage = Optional.empty();
				if (event.getParameters().size() > 1) {
					subMessage = Optional.ofNullable(event.getParameters().get(1));
				}

				// Check Tags
				if (tagMap.containsKey("msg-id") && tagMap.containsKey("msg-param-months") && tagMap.containsKey("display-name") && tagMap.containsKey("system-msg")) {
					if (tagMap.get("msg-id").equals("resub") && Integer.parseInt(tagMap.get("msg-param-months")) > 1) {
						Boolean isPrime = tagMap.get("system-msg").toLowerCase().contains("twitch prime");

						onSubscription(Long.parseLong(tagMap.get("user-id")), channel, Integer.parseInt(tagMap.get("msg-param-months")), isPrime, subMessage.orElse(""), tagMap.getOrDefault("msg-param-sub-plan", "1000"));
						return;
					}
				}
			}
			// Notices
			else if (event.getOriginalMessage().toString().contains("NOTICE")) {
				// ServerMessage message = event.getServerMessage();

				// Now hosting target_channel.
				if (tagMap.get("msg-id").equals("host_on")) {
					Event dispatchEvent = new HostOnEvent(channel, channel);
					getTwitchClient().getDispatcher().dispatch(dispatchEvent);
				}

				// Exited host mode.
				else if (tagMap.get("msg-id").equals("host_off")) {
					Event dispatchEvent = new HostOffEvent(channel);
					getTwitchClient().getDispatcher().dispatch(dispatchEvent);
				}

				// This room is now in emote-only mode.
				else if (tagMap.get("msg-id").equals("emote_only_on")) {
					// TODO: Trigger Event
				}

				// This room is no longer in emote-only mode.
				else if (tagMap.get("msg-id").equals("emote_only_off")) {
					// TODO: Trigger Event
				}

				// This channel has been suspended.
				else if (tagMap.get("msg-id").equals("msg_channel_suspended")) {
					// TODO: Trigger Event
				}

				// target_user has been timed out for ban_duration seconds.
				else if (tagMap.get("msg-id").equals("timeout_success")) {
					// TODO: Trigger Event
				}

				// target_user is now banned from this room.
				else if (tagMap.get("msg-id").equals("ban_success")) {
					// TODO: Trigger Event
				}

				// target_user is no longer banned from this room.
				else if (tagMap.get("msg-id").equals("unban_success")) {
					// TODO: Trigger Event
				}
			}
			// Roomstate
			else if (event.getCommand().equals("ROOMSTATE")) {
				if (tagMap.containsKey("subs-only")) {
					if (tagMap.get("subs-only").equals("0")) {
						// This room is no longer in subscribers-only mode.

					} else {
						// This room is now in subscribers-only mode.

					}
				}

				if (tagMap.containsKey("slow")) {
					if (tagMap.get("slow").equals("0")) {
						// This room is no longer in slow mode.
					} else {
						// This room is now in slow mode. You may send messages every slow_duration seconds.
						Integer messageDelay = Integer.parseInt(tagMap.get("slow"));

					}
				}

				if (tagMap.containsKey("r9k")) {
					if (tagMap.get("r9k").equals("0")) {
						// This room is no longer in r9k mode.
					} else {
						// This room is now in r9k mode.
					}
				}

				if (tagMap.containsKey("emote-only")) {
					if (tagMap.get("emote-only").equals("0")) {
						// This room is no longer in emote-only mode.
					} else {
						// This room is now in emote-only mode.
					}
				}

				if (tagMap.containsKey("followers-only")) {
					if (tagMap.get("followers-only").equals("0")) {
						// This room is no longer in followers-only mode.
					} else {
						// This room is now in followers-only mode.
					}
				}
			}
		}
	}

	/**
	 * Event: onMessage
	 * Gets executed on ChannelMessageEvent
	 *
	 * @param event The {@link org.kitteh.irc.client.library.event.channel.ChannelMessageEvent} provided by the irc library.
	 */
	@Handler(priority = Integer.MAX_VALUE)
	protected void onMessageEvent(org.kitteh.irc.client.library.event.channel.ChannelMessageEvent event) {
		String channelName = event.getChannel().getName().replace("#", "");
		String userName = event.getActor().getNick();
		String userMessage = event.getMessage();
		Set<CommandPermission> userPermissions = new HashSet<CommandPermission>();

		// Build Map from Tags
		Map<String, String> tagMap = getTagMap(event.getOriginalMessages().get(0));

		// Cancel, if there is no information about the user.
		if(!tagMap.containsKey("user-id")) {
			return;
		}

		Long userId = Long.parseLong(tagMap.get("user-id"));

		// Channel Information
		Long chanelId = getTwitchClient().getUserEndpoint().getUserIdByUserName(channelName).orElse(null);
		Channel channel = getTwitchClient().getChannelEndpoint(chanelId).getChannel();

		// User Information
		User user = new User();
		user.setId(userId);
		user.setName(userName.toLowerCase());
		user.setDisplayName(userName);

		// Check for Permissions
		if (tagMap.containsKey("badges")) {
			List<String> badges = Arrays.asList(tagMap.get("badges").split(","));
			// - Broadcaster
			if (badges.contains("broadcaster/1")) {
				userPermissions.add(CommandPermission.BROADCASTER);
				userPermissions.add(CommandPermission.MODERATOR);
			}
			// Twitch Prime
			if (badges.contains("premium/1")) {
				userPermissions.add(CommandPermission.PRIME_TURBO);
			}
			// Moderator
			if (badges.contains("moderator/1")) {
				userPermissions.add(CommandPermission.MODERATOR);
			}
		}
		// Twitch Turbo
		if (tagMap.containsKey("turbo") && tagMap.get("turbo").equals("1")) {
			userPermissions.add(CommandPermission.PRIME_TURBO);
		}
		// Subscriber
		if (tagMap.containsKey("subscriber") && tagMap.get("subscriber").equals("1")) {
			userPermissions.add(CommandPermission.SUBSCRIBER);
		}
		// Everyone
		userPermissions.add(CommandPermission.EVERYONE);

		// Dispatch Event
		ChannelMessageEvent channelMessageEvent = new ChannelMessageEvent(channel, user, userMessage, userPermissions);
		getTwitchClient().getDispatcher().dispatch(channelMessageEvent);
	}

	/**
	 * Gets called when a new subscription is announced to the stream.
	 */
	private void onSubscription(Long userId, Channel channel, Integer streak, Boolean isPrime, String message, String subPlan) {
		// Build Subscription Entity
		Subscription entity = new Subscription();
		entity.setCreatedAt(Optional.ofNullable(new Date()));
		entity.setMessage(Optional.ofNullable(streak > 1 ? message : null)); // You can't write a message for the first sub.
		entity.setIsPrimeSub(Optional.ofNullable(isPrime));
		entity.setStreak(Optional.ofNullable(streak));
		entity.setUser(getTwitchClient().getUserEndpoint().getUser(userId).get());

		// Sub Tiers
		if(subPlan.equals("Prime")) {
			entity.setSubPlan(Optional.ofNullable(SubPlan.PRIME));
		} else if(subPlan.equals("1000")) {
			entity.setSubPlan(Optional.ofNullable(SubPlan.TIER_1));
		} else if(subPlan.equals("2000")) {
			entity.setSubPlan(Optional.ofNullable(SubPlan.TIER_2));
		} else if(subPlan.equals("3000")) {
			entity.setSubPlan(Optional.ofNullable(SubPlan.TIER_3));
		}

		// Prevent multi-firing of the same subscription (is sometimes send 2. times)
		String subHistoryKey = String.format("%s|%s", entity.getUser().getId(), entity.getStreak());
		if (subscriptionHistory.containsKey(subHistoryKey)) {
			Logger.trace(this, "Subscription called two times, not firing event! %s", entity.toString());
			return;
		} else {
			subscriptionHistory.put(subHistoryKey, entity);
		}

		// Fire Event
		getTwitchClient().getDispatcher().dispatch(new SubscriptionEvent(channel, entity));
	}

	/**
	 * Gets called when a new cheer is announced to the stream.
	 */
	private void onCheer(Long userId, Channel channel, String bits, String message) {
		// Build Cheer Entity
		Cheer entity = new Cheer();
		entity.setBits(Integer.parseInt(bits));
		entity.setMessage(message);
		entity.setUser(getTwitchClient().getUserEndpoint().getUser(userId).get());

		// Fire Event
		getTwitchClient().getDispatcher().dispatch(new CheerEvent(channel, entity));
	}

	private Map<String, String> getTagMap(ServerMessage serverMessage) {
		// Build Map from Tags
		Map<String, String> tagMap = new HashMap<>();
		for (MessageTag tag : serverMessage.getTags()) {
			if (tag.getValue().isPresent()) {
				tagMap.put(tag.getName(), tag.getValue().get());
			}
		}

		return tagMap;
	}
}
