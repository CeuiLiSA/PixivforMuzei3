/*
 *     This file is part of PixivforMuzei3.
 *
 *     PixivforMuzei3 is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program  is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.antony.muzei.pixiv;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SeekBarPreference;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class SettingsActivity extends AppCompatActivity
{
	private SharedPreferences.OnSharedPreferenceChangeListener prefChangeListener;
	private String newCreds, oldCreds;
	private String oldUpdateMode, newUpdateMode;
	private String oldTag, newTag;
	private String oldArtist, newArtist;

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		// If Muzei is not installed, this will redirect the user to Muzei's Play Store listing
		if (!isMuzeiInstalled())
		{
			final String appPackageName = "net.nurik.roman.muzei"; // getPackageName() from Context or Activity object
			try
			{
				startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + appPackageName)));
			} catch (android.content.ActivityNotFoundException ex)
			{
				startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + appPackageName)));
			}
		}

		SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

		// Stores user toggleable variables into a temporary store for later comparison in onStop()
		// If the value of the preference on Activity creation is different to Activity stop, then take certain action
		oldCreds = sharedPrefs.getString("pref_loginPassword", "");
		newCreds = oldCreds;

		oldUpdateMode = sharedPrefs.getString("pref_updateMode", "");
		newUpdateMode = oldUpdateMode;

		oldTag = sharedPrefs.getString("pref_tagSearch", "");
		newTag = oldTag;

		oldArtist = sharedPrefs.getString("pref_artistId", "");
		newArtist = oldArtist;

		prefChangeListener = (sharedPreferences, key) ->
		{
			switch (key)
			{
				case "pref_loginPassword":
					newCreds = sharedPrefs.getString("pref_loginPassword", "");
					break;
				case "pref_updateMode":
					newUpdateMode = sharedPrefs.getString("pref_updateMode", "");
					break;
				case "pref_tagSearch":
					newTag = sharedPrefs.getString("pref_tagSearch", "");
					break;
				case "pref_artistId":
					newArtist = sharedPrefs.getString("pref_artistId", "");
					break;
			}
		};
	}

	@Override
	public void onResume()
	{
		super.onResume();
		SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		sharedPrefs.registerOnSharedPreferenceChangeListener(prefChangeListener);
	}

	@Override
	public void onPause()
	{
		super.onPause();
		SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener);
	}

	// Functions in here action only on app exit
	@Override
	public void onStop()
	{
		super.onStop();
		// If new user credentials were entered and saved, then clear and invalidate existing stored user credentials
		if (!oldCreds.equals(newCreds))
		{
			SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
			SharedPreferences.Editor editor = sharedPrefs.edit();
			editor.remove("accessToken");
			editor.remove("refreshToken");
			editor.remove("deviceToken");
			editor.remove("accessTokenIssueTime");
			editor.commit();
			Toast.makeText(getApplicationContext(), getString(R.string.toast_newCredentials), Toast.LENGTH_SHORT).show();
		}

		// Automatic cache clearing at 1AM every night for as long as the setting is toggled active
		SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		if (sharedPrefs.getBoolean("pref_autoClearMode", false))
		{
			// Calculates the hours to midnight
			SimpleDateFormat simpleDateFormat = new SimpleDateFormat("kk");
			int hoursToMidnight = 24 - Integer.parseInt(simpleDateFormat.format(new Date()));

			// Builds and submits the work request
			Constraints constraints = new Constraints.Builder()
					.setRequiredNetworkType(NetworkType.CONNECTED)
					.build();
			PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(ClearCacheWorker.class, 24, TimeUnit.HOURS)
					.setInitialDelay(hoursToMidnight, TimeUnit.HOURS)
					.addTag("PIXIV_CACHE_AUTO")
					.setConstraints(constraints)
					.build();
			WorkManager.getInstance(getApplicationContext())
					.enqueueUniquePeriodicWork("PIXIV_CACHE_AUTO", ExistingPeriodicWorkPolicy.KEEP, request);
		} else
		{
			WorkManager.getInstance(getApplicationContext()).cancelAllWorkByTag("PIXIV_CACHE_AUTO");
		}

		// If user has changed update, filter mode, or search tag:
		//  Immediately stop any pending work, clear the Provider of any Artwork, and then toast
		if (!oldUpdateMode.equals(newUpdateMode) || !oldTag.equals(newTag)
				|| !oldArtist.equals(newArtist))
		{
			WorkManager.getInstance().cancelUniqueWork("ANTONY");
			File dir = getApplicationContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES);
			String[] children = dir.list();
			for (String child : children)
			{
				new File(dir, child).delete();
			}
			PixivArtWorker.enqueueLoad(true);
			if (!oldUpdateMode.equals(newUpdateMode))
			{
				Toast.makeText(getApplicationContext(), getString(R.string.toast_newUpdateMode), Toast.LENGTH_SHORT).show();
			} else if (!oldArtist.equals(newArtist))
			{
				Toast.makeText(getApplicationContext(), getString(R.string.toast_newArtist), Toast.LENGTH_SHORT).show();
			} else if (!oldTag.equals(newTag))
			{
				Toast.makeText(getApplicationContext(), getString(R.string.toast_newTag), Toast.LENGTH_SHORT).show();
			} else
			{
				Toast.makeText(getApplicationContext(), getString(R.string.toast_newFilterSelect), Toast.LENGTH_SHORT).show();
			}
		}
	}

	public boolean isMuzeiInstalled()
	{
		boolean found = true;
		try
		{
			getApplicationContext().getPackageManager().getPackageInfo("net.nurik.roman.muzei", 0);
		} catch (PackageManager.NameNotFoundException ex)
		{
			found = false;
		}
		return found;
	}

	// Functions in here action immediately on user interaction
	public static class SettingsFragment extends PreferenceFragmentCompat
	{
		@Override
		public void onCreatePreferences(Bundle savedInstanceState, String rootKey)
		{
			setPreferencesFromResource(R.xml.main_preference_layout, rootKey);
			SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getContext());

			// Immediately clear image cache Preference
			findPreference(getString(R.string.button_clearCache)).setOnPreferenceClickListener(preference ->
			{
				WorkManager.getInstance().cancelUniqueWork("ANTONY");
				File dir = getContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES);

				String[] children = dir.list();
				for (String child : children)
				{
					new File(dir, child).delete();
				}
				PixivArtWorker.enqueueLoad(true);
				Toast.makeText(getContext(), getString(R.string.toast_clearingCache), Toast.LENGTH_SHORT).show();
				return true;
			});

			// Show authentication status as summary string below login button
			if (sharedPrefs.getString("accessToken", "").isEmpty())
			{
				findPreference("pref_loginId").setSummary(getString(R.string.prefSummary_authFail));
				//loginId.setSummary(Long.toString(System.currentTimeMillis()));
			} else
			{
				String summaryString = getString(R.string.prefSummary_authSuccess) + " " + sharedPrefs.getString("pref_loginId", "");
				findPreference("pref_loginId").setSummary(summaryString);
//                Uri profileImageUri = Uri.parse(sharedPrefs.getString("profileImageUri", ""));
//                loginId.setIcon();
			}

			// Reveal the tag_search or artist_id EditTextPreference and write the summary if update mode matches
			String updateMode = sharedPrefs.getString("pref_updateMode", "daily");
			if (Arrays.asList("follow", "bookmark", "tag_search", "artist", "recommended")
					.contains(updateMode))
			{
				findPreference("pref_authFilterSelect").setVisible(true);
				findPreference("prefCat_loginSettings").setVisible(true);
				if (updateMode.equals("tag_search"))
				{
					Preference tagSearch = findPreference("pref_tagSearch");
					tagSearch.setVisible(true);
					tagSearch.setSummary(sharedPrefs.getString("pref_tagSearch", ""));
				} else if (updateMode.equals("artist"))
				{
					Preference artistId = findPreference("pref_artistId");
					artistId.setVisible(true);
					artistId.setSummary(sharedPrefs.getString("pref_artistId", ""));
				}
			} else
			{
				findPreference("pref_rankingFilterSelect").setVisible(true);
			}


			// Reveals UI elements as needed depending on Update Mode selection
			findPreference("pref_updateMode").setOnPreferenceChangeListener((preference, newValue) ->
			{
				// If any of the auth feed modes, reveal login Preference Category, reveal the auth NSFW filtering,
				// and hide the ranking NSFW filtering
				if (Arrays.asList("follow", "bookmark", "tag_search", "artist", "recommended")
						.contains(newValue))
				{
					findPreference("prefCat_loginSettings").setVisible(true);
					findPreference("pref_authFilterSelect").setVisible(true);
					findPreference("pref_rankingFilterSelect").setVisible(false);
				} else
				{
					findPreference("pref_rankingFilterSelect").setVisible(true);
					findPreference("prefCat_loginSettings").setVisible(false);
					findPreference("pref_authFilterSelect").setVisible(false);
				}

				if (newValue.equals("tag_search"))
				{
					findPreference("pref_tagSearch").setVisible(true);
				} else
				{
					findPreference("pref_tagSearch").setVisible(false);
				}

				if (newValue.equals("artist"))
				{
					findPreference("pref_artistId").setVisible(true);
				} else
				{
					findPreference("pref_artistId").setVisible(false);
				}

				return true;
			});

			// All this is needed for the arbitrary selection NSFW filtering
			// Resets to default SFW filtering is all options are unchecked
			// Prints a summary string based on selection
			// Updates authFilterSelectPref summary as user updates it
			MultiSelectListPreference authFilterSelectPref = findPreference("pref_authFilterSelect");
			authFilterSelectPref.setOnPreferenceChangeListener((preference, newValue) ->
			{
				// Resets to SFW on empty selection
				// for some reason a length of 2 is an empty selection
				if (newValue.toString().length() == 2)
				{
					Set<String> defaultSet = new HashSet<>();
					defaultSet.add("2");
					authFilterSelectPref.setValues(defaultSet);

					SharedPreferences.Editor editor = sharedPrefs.edit();
					editor.putStringSet("pref_authFilterSelect", defaultSet);
					editor.commit();
					authFilterSelectPref.setSummary("SFW");
					return false;
				}

				// Prints a comma delimited string of user selections. There is no trailing comma
				// TODO there's gotta be a better wau of doing this
				String str = newValue.toString();
				ArrayList<Integer> arrayList = new ArrayList<>();
				for (int i = 0; i < str.length(); i++)
				{
					if (Character.isDigit(str.charAt(i)))
					{
						arrayList.add(Character.getNumericValue(str.charAt(i)));
					}
				}
				String[] authFilterEntriesPossible = getResources().getStringArray(R.array.pref_authFilterLevel_entries);
				StringBuilder stringBuilderAuth = new StringBuilder();
				for (int i = 0; i < arrayList.size(); i++)
				{
					stringBuilderAuth.append(authFilterEntriesPossible[(arrayList.get(i) - 2) / 2]);
					if (i != arrayList.size() - 1)
					{
						stringBuilderAuth.append(", ");
					}
				}
				String summaryAuth = stringBuilderAuth.toString();

				authFilterSelectPref.setSummary(summaryAuth);

				return true;
			});

			// Generates the authFilterSelectPref summary during activity startup
			// TODO combine this with the above summary setting code section
			Set<String> chosenLevelsSet = sharedPrefs.getStringSet("pref_authFilterSelect", null);
			String[] chosenLevels = chosenLevelsSet.toArray(new String[0]);
			String[] entriesAvailableAuth = getResources().getStringArray(R.array.pref_authFilterLevel_entries);
			StringBuilder stringBuilderAuth = new StringBuilder();
			for (int i = 0; i < chosenLevels.length; i++)
			{
				stringBuilderAuth.append(entriesAvailableAuth[(Integer.parseInt(chosenLevels[i]) - 2) / 2]);
				if (i != chosenLevels.length - 1)
				{
					stringBuilderAuth.append(", ");
				}
			}
			String summaryAuth = stringBuilderAuth.toString();
			authFilterSelectPref.setSummary(summaryAuth);

			// updates ranking nsfw select summary on preference change
			MultiSelectListPreference rankingFilterSelectPref = findPreference("pref_rankingFilterSelect");
			rankingFilterSelectPref.setOnPreferenceChangeListener((preference, newValue) ->
			{
				// for some reason 2 is an empty selection
				if (newValue.toString().length() == 2)
				{
					Log.v("MANUAL", "pref change empty set");
					Set<String> defaultSet = new HashSet<>();
					defaultSet.add("0");
					rankingFilterSelectPref.setValues(defaultSet);

					SharedPreferences.Editor editor = sharedPrefs.edit();
					editor.putStringSet("pref_rankingFilterSelect", defaultSet);
					editor.commit();
					rankingFilterSelectPref.setSummary("SFW");
					return false;
				}

				String str = newValue.toString();
				ArrayList<Integer> arrayList = new ArrayList<>();
				for (int i = 0; i < str.length(); i++)
				{
					if (Character.isDigit(str.charAt(i)))
					{
						arrayList.add(Character.getNumericValue(str.charAt(i)));
					}
				}
				String[] rankingEntriesAvailable = getResources().getStringArray(R.array.pref_rankingFilterLevel_entries);
				StringBuilder stringBuilderRanking = new StringBuilder();
				for (int i = 0; i < arrayList.size(); i++)
				{
					stringBuilderRanking.append(rankingEntriesAvailable[arrayList.get(i)]);
					if (i != arrayList.size() - 1)
					{
						stringBuilderRanking.append(", ");
					}
				}
				String summaryRanking = stringBuilderRanking.toString();

				rankingFilterSelectPref.setSummary(summaryRanking);

				return true;
			});

			// Generates the ranking NSFW filter summary during activity startup
			Set<String> chosenLevelsSetRanking = sharedPrefs.getStringSet("pref_rankingFilterSelect", null);
			String[] chosenLevelsRanking = chosenLevelsSetRanking.toArray(new String[0]);
			String[] entriesAvailableRanking = getResources().getStringArray(R.array.pref_rankingFilterLevel_entries);
			StringBuilder stringBuilderRanking = new StringBuilder();
			for (int i = 0; i < chosenLevelsRanking.length; i++)
			{
				stringBuilderRanking.append(entriesAvailableRanking[Integer.parseInt(chosenLevelsRanking[i])]);
				if (i != chosenLevelsRanking.length - 1)
				{
					stringBuilderRanking.append(", ");
				}
			}
			String summaryRanking = stringBuilderRanking.toString();
			rankingFilterSelectPref.setSummary(summaryRanking);


			Preference externalStoragePref = findPreference("pref_storeInExtStorage");
			externalStoragePref.setOnPreferenceClickListener(preference ->
			{
				if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
						!= PackageManager.PERMISSION_GRANTED)
				{
					ActivityCompat.requestPermissions(getActivity(),
							new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
							1);
				}
				return true;
			});

			externalStoragePref.setOnPreferenceChangeListener(((preference, newValue) ->
					ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
							== PackageManager.PERMISSION_GRANTED));


			// Artwork minimum views slider
			// Updates the summary in real time as the user drags the thumb
			// Increments of 500, hence the scalar
			SeekBarPreference minimumViewSliderPref = findPreference("prefSlider_minViews");
			minimumViewSliderPref.setUpdatesContinuously(true);
			minimumViewSliderPref.setSummary(Integer.toString(
					sharedPrefs.getInt("prefSlider_minViews", 0) * 500));
			minimumViewSliderPref.setOnPreferenceChangeListener((((preference, newValue) ->
			{
				minimumViewSliderPref.setSummary(Integer.toString((Integer) newValue * 500));
				return true;
			})));

			SeekBarPreference numToDownloadSlider = findPreference("prefSlider_numToDownload");
			numToDownloadSlider.setUpdatesContinuously(true);
			numToDownloadSlider.setSummary(Integer.toString(
					sharedPrefs.getInt("prefSlider_numToDownload", 2)));
			numToDownloadSlider.setOnPreferenceChangeListener((((preference, newValue) ->
			{
				numToDownloadSlider.setSummary(Integer.toString((Integer) newValue));
				return true;
			})));

			// Hide app icon if switch is activated
//			if (!sharedPrefs.getBoolean("pref_hideLauncherIcon", false))
//			{
//				PackageManager p = getContext().getPackageManager();
//				ComponentName componentName = new ComponentName("com.antony.muzei.pixiv", "com.antony.muzei.pixiv.SettingsActivity"); // activity which is first time open in manifiest file which is declare as <category android:name="android.intent.category.LAUNCHER" />
//				p.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
//			} else
//			{
//				PackageManager p = getContext().getPackageManager();
//				ComponentName componentName = new ComponentName("com.antony.muzei.pixiv", "com.antony.muzei.pixiv.SettingsActivity");
//				p.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
//			}
		}
	}
}
