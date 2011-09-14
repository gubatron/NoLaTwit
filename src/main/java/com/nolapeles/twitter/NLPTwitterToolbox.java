package com.nolapeles.twitter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import twitter4j.IDs;
import twitter4j.ResponseList;
import twitter4j.Status;
import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.TwitterFactory;
import twitter4j.User;
import twitter4j.conf.Configuration;
import twitter4j.conf.ConfigurationBuilder;

/**
 * This class has several utilities to automate tasks related to your followers.
 * 
 * 
 **/
public class NLPTwitterToolbox {
	private static final int MAX_TWEET_LENGTH = 140;
	private static String SCREEN_NAME;
	private Twitter twitter;
	private Set<Long> FRIENDS;
	private long loadFriendCursor;
	private File fFriends;
	private Properties props;

	public static void main(String[] args) {

		if (args == null || args.length == 0) {
			System.out
					.println("ERROR: Please specify at least one twitter4j.properties file.\n");
			return;
		}
		
		for (String propfile : args) {
			serviceAccount(propfile);
		}

	}

	/**
	 * Takes the name of a twitter4j.prop file and performs on the account for that property file:
	 * > follow back new users that have mentioned us recently.
	 * > follow friday (if it's a friday)
	 * @param propfile
	 */
	public static void serviceAccount(String propfile) {
		File properties = new File(propfile);

		if (!properties.exists() || !properties.isFile()
				|| !properties.canRead()) {
			System.out.println("ERROR: " + propfile
					+ " is not a valid twitter4j.propertis file.\n");
		}

		NLPTwitterToolbox toolbox = new NLPTwitterToolbox(properties);

		// get all the latest mentions to us, and let's play with this.
		List<Status> statuses = null;
		try {
			statuses = toolbox.getMentions();
		} catch (TwitterException e) {
			e.printStackTrace();
			System.exit(-1);
		}

		// Follow back those that mentioned you, if you're not following them
		// already
		toolbox.unfollowInactive();
		toolbox.followNewUsers(statuses);
		toolbox.sendFollowFridayRecommendations(statuses);
	}
	
	/**
	 * Unfollow users that have been inactive for 3 months.
	 */
	private void unfollowInactive() {
		Iterator<Long> iterator = FRIENDS.iterator();
		
		Calendar threeMonthsAgo = Calendar.getInstance();
		threeMonthsAgo.add(Calendar.MONTH, -3);

		
		while (iterator.hasNext()) {
			Long friendId = iterator.next();
			
			try {

				//unfollow inactive, get user out of FRIENDS.
				User friend = twitter.showUser(friendId);
				if (friend.getStatus().getCreatedAt().before(threeMonthsAgo.getTime())) {
					twitter.destroyFriendship(friendId);
					iterator.remove();
					System.out.println("@"+ SCREEN_NAME+ " unfollows inactive @" + friend.getScreenName() + " Last Tweet was on ["+friend.getStatus().getCreatedAt()+"]");
				} else {
					System.out.println("Keeping @" + friend.getScreenName() + " for @" + SCREEN_NAME);
				}
			} catch (Exception e) {
				
			}
		}
		
		saveFriends();
	}

	private void sendFollowFridayRecommendations(List<Status> statuses) {
		
		if (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY) {
			System.out.println("No #FF today.");
			return;
		} else {
			System.out.println("TGI Friday!!!");
		}
		
		//TODO: Copy this code to generalize this pattern.
		//We're basically converting a list with repeated objects
		//into a sorted set based on the number of appearances. (histogram)
		
		List<User> usersToRecommend = new ArrayList<User>();
		final Map<User,Integer> userInteractions = new HashMap<User,Integer>();
		
		for (Status status : statuses) {
			User user = status.getUser();
			if (userInteractions.containsKey(user)) {
				userInteractions.put(user, 1+userInteractions.get(user));
			} else if (!user.getScreenName().equalsIgnoreCase(SCREEN_NAME)) {
				userInteractions.put(user, 1);
				usersToRecommend.add(user);
			}
		}
		
		Collections.sort(usersToRecommend, new Comparator<User>() {
			@Override
			public int compare(User u1, User u2) {
				return userInteractions.get(u2) - userInteractions.get(u1);
			}
		});

		//Now start preparing the twitts with the users, recommend the best ones first.
		List<String> tweets = new ArrayList<String>();
		StringBuilder tempTweet = new StringBuilder("#FF");
		for (User user : usersToRecommend) {
			if ((tempTweet.length() + user.getScreenName().length() + " @".length()) <= MAX_TWEET_LENGTH) {
				tempTweet.append(" @" + user.getScreenName());
			} else {
				tweets.add(tempTweet.toString());
				tempTweet = new StringBuilder("#FF");
			}
		}

		//sometimes there's material for only one tweet.
		String possibleShortRecommendation = tempTweet.toString();
		if (!possibleShortRecommendation.equals("#FF")) {
			tweets.add(possibleShortRecommendation);
		}

		//Tweet away!
		for (String tweet : tweets) {
			try {
				twitter.updateStatus(tweet);
			} catch (TwitterException e) {
				e.printStackTrace();
			}
		}

	}

	public NLPTwitterToolbox(File propertyFile) {
		try {
			loadProperties(propertyFile);
			initTwitter();
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(1);
		}

		initFriends();
	}

	private void loadProperties(File propertyFile) throws Exception {
		String[] expectedPropertyKeys = new String[] { "debug",
				"oauth.consumerKey", "oauth.consumerSecret",
				"oauth.accessToken", "oauth.accessTokenSecret", "username" };

		props = new Properties();
		props.load(new FileInputStream(propertyFile));

		// make sure all expectedPropertyKeys are there.
		for (String key : expectedPropertyKeys) {
			if (!props.containsKey(key)) {
				throw new Exception("ERROR: Missing '" + key
						+ "' property on property file - "
						+ propertyFile.getAbsolutePath());
			}
		}
	}

	private void initFriends() {
		FRIENDS = new HashSet<Long>();
		SCREEN_NAME = props.getProperty("username");
		fFriends = new File(SCREEN_NAME + ".friends.txt");
		loadFriends();
	}

	private void loadFriends() {
		if (!fFriends.exists() || fFriends.length() == 0) {
			getFriendsFromInternet();
			saveFriends();
		} else {
			try {
				FileInputStream fis = new FileInputStream(fFriends);
				ObjectInputStream ois = new ObjectInputStream(fis);
				while (true) {
					try {
						FRIENDS.add(ois.readLong());
					} catch (Exception e) {
						fis.close();
						break;
					}
				}
			} catch (Exception e) {
				return;
			}
		}
	}

	private void saveFriends() {
		ObjectOutputStream oos = null;
		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(fFriends);
			oos = new ObjectOutputStream(fos);
			for (Long lid : FRIENDS) {
				oos.writeLong(lid);
			}

			try {
				oos.flush();
				oos.close();
				System.out.println("Saved " + FRIENDS.size() + " friends for @" + SCREEN_NAME);
			} catch (IOException e) {
				e.printStackTrace();
			}
		} catch (Exception e) {

		}
	}

	private void getFriendsFromInternet() {
		loadFriendCursor = -1;
		while (getNextFriendsIDs()) {
		}
	}

	private boolean getNextFriendsIDs() {
		try {
			IDs friendsIDs = twitter.getFriendsIDs(SCREEN_NAME,
					loadFriendCursor);
			long[] iDs = friendsIDs.getIDs();
			for (long lid : iDs) {
				FRIENDS.add(lid);
			}
			loadFriendCursor = friendsIDs.getNextCursor();
			return friendsIDs.hasNext();
		} catch (TwitterException e) {
			return false;
		}
	}

	private void initTwitter() throws TwitterException {
		// do #FF method. Prints out as many tweets as necessary taking into
		// consideration
		// mentions, RTs, favorites.
		// start putting all users found on a List<String, Integer>
		// add points for RTs(3), mentions (2) and favs(1)
		Configuration configuration = new ConfigurationBuilder()
				.setOAuthConsumerKey(props.getProperty("oauth.consumerKey"))
				.setOAuthConsumerSecret(
						props.getProperty("oauth.consumerSecret"))
				.setOAuthAccessToken(props.getProperty("oauth.accessToken"))
				.setOAuthAccessTokenSecret(
						props.getProperty("oauth.accessTokenSecret")).build();

		twitter = new TwitterFactory(configuration).getInstance();
		twitter.verifyCredentials();
	}

	@SuppressWarnings("unused")
	private void followNewUsers(List<Status> statuses) {
		for (Status status : statuses) {
			long userid;

			// don't follow your tail!
			if (status.getUser().getScreenName().equalsIgnoreCase(SCREEN_NAME)) {
				continue;
			}

			if (!FRIENDS.contains(userid = status.getUser().getId())) {
				try {
					twitter.createFriendship(userid, true);
					FRIENDS.add(userid);
				} catch (TwitterException e) {
				}
			}
		}
		saveFriends();
	}

	private List<Status> getMentions() throws TwitterException {
		List<Status> statuses = twitter.getMentions();
		return statuses;
	}
}
